package com.example.engine

import com.example.model.DeliveryStage
import com.example.model.LiveOrderState
import com.example.model.RegexConfig
import java.util.Locale
import kotlin.math.abs

data class ParsedNotificationInput(
  val packageName: String,
  val appName: String,
  val title: String,
  val text: String,
  val bigText: String,
  val orderHintKey: String? = null,
  val timestampMillis: Long = System.currentTimeMillis(),
) {
  val combinedText: String
    get() = listOf(title, text, bigText)
      .map { it.trim() }
      .filter { it.isNotEmpty() }
      .distinct()
      .joinToString(" ")
}

data class RegexMatchPreview(
  val matched: Boolean,
  val stage: DeliveryStage?,
  val progress: Int,
  val chipShortText: String,
  val matchedRuleName: String,
  val capturedMinutes: Int? = null,
  val capturedDistanceMeters: Int? = null,
)

class RegexProgressEngine {

  private val orderIdRegex =
    Regex("(?:订单号[:：]?\\s*([A-Za-z0-9-]{4,18})|#(\\d{1,6})|取餐码[:：]?\\s*([A-Za-z0-9-]{2,10}))")

  /**
   * Resolve which live order a notification belongs to, so that one real order keeps
   * updating the SAME live notification instead of spawning a new one per status push,
   * while concurrent orders stay separate.
   *
   * Priority:
   * 1. An already-resolved order key (the monitor screen passes orderKey back as the
   *    hint when simulating the next stage, so it must be used as-is).
   * 2. An explicit order id in the text (`#1234`, `订单号: ...`, `取餐码: ...`).
   * 3. The merchant name (title first, then body). Same merchant + same app = same
   *    order; a fresh "new order" signal while that merchant's order is already
   *    DELIVERING opens a numbered key (`<base>#2`, `#3`, ...) so two orders from
   *    the same merchant coexist.
   * 4. The text mentions the merchant of an existing active order.
   * 5. Package default key, with the same "new order while delivering" split.
   */
  fun resolveOrderKey(
    input: ParsedNotificationInput,
    existingOrders: Map<String, LiveOrderState>,
    config: RegexConfig = RegexConfig(),
  ): String {
    val packageName = input.packageName
    val hint = input.orderHintKey

    // 1. Already a full order key — use it unchanged.
    if (
      !hint.isNullOrBlank() &&
        (hint.startsWith("$packageName:") || hint.startsWith("$packageName#"))
    ) {
      return hint
    }

    val combined = input.combinedText

    // 2. Explicit order id.
    val extractedId = orderIdRegex.find(combined)?.groupValues
      ?.drop(1)
      ?.firstOrNull { it.isNotBlank() }

    if (!extractedId.isNullOrBlank()) {
      return "$packageName#$extractedId"
    }

    // 3. Merchant name from this notification. The order key uses the merchant's
    // core name (branch suffix stripped, e.g. "麦当劳（中关村店）" -> "麦当劳") so
    // that a title/text wording difference between updates cannot split one order.
    val merchant = extractMerchantName(input)
    if (!merchant.isNullOrBlank()) {
      return resolveBaseKey(
        baseKey = "$packageName:m:${merchantKeyName(merchant)}",
        input = input,
        existingOrders = existingOrders,
        config = config,
      )
    }

    // 4. The text mentions the merchant of an existing active order (by core name,
    // so "麦当劳的订单配送中" still attaches to "麦当劳（中关村店）").
    val mentionedOrder =
      existingOrders.values
        .filter {
          it.packageName == packageName &&
            !it.userDismissed &&
            !it.merchantName.isNullOrBlank() &&
            combined.contains(merchantKeyName(it.merchantName))
        }
        .maxByOrNull { it.lastUpdatedMillis }
    if (mentionedOrder != null) return mentionedOrder.orderKey

    // 5. Package default.
    return resolveBaseKey(
      baseKey = "$packageName:default",
      input = input,
      existingOrders = existingOrders,
      config = config,
    )
  }

  /**
   * Attach to the latest active order under [baseKey], unless this notification looks
   * like a brand-new order starting (stage 1 only) while the latest order has already
   * moved past PREPARING — then open the next numbered key so both orders coexist.
   */
  private fun resolveBaseKey(
    baseKey: String,
    input: ParsedNotificationInput,
    existingOrders: Map<String, LiveOrderState>,
    config: RegexConfig,
  ): String {
    val actives =
      existingOrders.values
        .filter {
          it.packageName == input.packageName &&
            !it.userDismissed &&
            (it.orderKey == baseKey || it.orderKey.startsWith("$baseKey#"))
        }
        .sortedByDescending { it.lastUpdatedMillis }
    if (actives.isEmpty()) return baseKey

    val latest = actives.first()
    if (looksLikeNewOrderStart(input.combinedText, config) && latest.stage != DeliveryStage.PREPARING) {
      var suffix = 2
      while (existingOrders.containsKey("$baseKey#$suffix")) suffix++
      return "$baseKey#$suffix"
    }
    return latest.orderKey
  }

  /** Stage 1 only — no delivery/delivered signal in the same text. */
  private fun looksLikeNewOrderStart(text: String, config: RegexConfig): Boolean {
    if (matchesSafe(config.stage3DeliveredRegex, text)) return false
    if (findSafe(config.stage2EtaRegex, text) != null) return false
    if (findSafe(config.stage2DistanceRegex, text) != null) return false
    if (matchesSafe(config.stage2StaticDeliveringRegex, text)) return false
    return matchesSafe(config.stage1PreparingRegex, text)
  }

  /**
   * Extract the merchant (store) name from a notification. Meituan/Ele.me usually put
   * the merchant name in the notification title; fall back to patterns in the body.
   * Returns null when nothing looks like a merchant — a missing merchant must never
   * be guessed, because a wrong name would merge unrelated orders.
   */
  fun extractMerchantName(input: ParsedNotificationInput): String? {
    merchantFromTitle(input.title, input.appName)?.let { return it }
    merchantFromText(input.text, input.appName)?.let { return it }
    merchantFromText(input.bigText, input.appName)?.let { return it }
    return null
  }

  /** The notification title is the merchant name unless it is generic/status text. */
  private fun merchantFromTitle(title: String, appName: String): String? {
    val trimmed = title.trim()
    if (!isPlausibleMerchant(trimmed, appName)) return null
    if (trimmed.length > 24) return null
    return trimmed
  }

  /** Strip a branch suffix ("麦当劳（中关村店）" -> "麦当劳") for order keying. */
  private fun merchantKeyName(name: String): String =
    name.substringBefore("（").substringBefore("(").trim()

  private fun merchantFromText(text: String, appName: String): String? {
    if (text.isBlank()) return null
    // 【麦当劳】
    Regex("""【([^】]{2,20})】""")
      .findAll(text)
      .mapNotNull { it.groupValues.getOrNull(1)?.trim() }
      .firstOrNull { isPlausibleMerchant(it, appName) }
      ?.let { return it }
    // 麦当劳已接单 / 麦当劳正在备餐 / 麦当劳配送中 (merchant directly before a status verb)
    Regex("""([^\s,，。：:；;\n]{2,20})(已接单|正在备餐|已出餐|配送中|已送达|预计|骑手已|商家已)""")
      .findAll(text)
      .mapNotNull { it.groupValues.getOrNull(1)?.trim() }
      .firstOrNull { isPlausibleMerchant(it, appName) }
      ?.let { return it }
    // 麦当劳的订单已接单
    Regex("""([^\s,，。：:；;\n]{2,20})的订单(已|正|即)""")
      .findAll(text)
      .mapNotNull { it.groupValues.getOrNull(1)?.trim() }
      .firstOrNull { isPlausibleMerchant(it, appName) }
      ?.let { return it }
    return null
  }

  private fun isPlausibleMerchant(name: String, appName: String): Boolean {
    if (name.length < 2 || name.length > 24) return false
    if (name.equals(appName, ignoreCase = true)) return false
    if (GENERIC_MERCHANT_WORDS.any { name.contains(it) }) return false
    if (name.contains("订单") || name.contains("通知")) return false
    return true
  }

  /**
   * Display title for the live notification: keep a real merchant title, replace a
   * generic title (e.g. "美团外卖") with the extracted merchant, fall back to the
   * raw title, and return null when there is neither (per-stage fallback applies).
   */
  private fun resolveDisplayTitle(title: String, merchant: String?, appName: String): String? {
    val trimmed = title.trim()
    if (trimmed.isEmpty()) return merchant
    if (merchant != null && merchantFromTitle(trimmed, appName) == null) return merchant
    return trimmed
  }

  fun evaluate(
    input: ParsedNotificationInput,
    orderKey: String,
    existingOrder: LiveOrderState?,
    config: RegexConfig,
    accentColorHex: Long,
    autoDismissSeconds: Int,
  ): LiveOrderState? {
    val fullText = input.combinedText
    if (fullText.isBlank()) return null

    val displayBody = input.bigText.ifBlank { input.text }.ifBlank { input.title }
    val notificationId = existingOrder?.notificationId
      ?: (BASE_NOTIFICATION_ID + abs(orderKey.hashCode() % 50_000))
    val activeExisting =
      existingOrder?.takeIf { it.stage != DeliveryStage.DELIVERED && !it.userDismissed }

    // Merchant for order keying/display: extracted from this notification, or kept
    // from the existing order when this update carries no merchant name.
    val merchant = extractMerchantName(input) ?: existingOrder?.merchantName
    val titleOrMerchant = resolveDisplayTitle(input.title, merchant, input.appName)

    // 1. Check Stage 3 (Delivered - 100%)
    if (matchesSafe(config.stage3DeliveredRegex, fullText)) {
      val now = input.timestampMillis
      return LiveOrderState(
        orderKey = orderKey,
        notificationId = notificationId,
        packageName = input.packageName,
        appName = input.appName,
        accentColorHex = accentColorHex,
        merchantName = merchant,
        title = titleOrMerchant ?: "${input.appName}订单",
        contentText = displayBody,
        stage = DeliveryStage.DELIVERED,
        progress = 100,
        chipShortText = "已送达",
        initialMinutes = activeExisting?.initialMinutes,
        remainingMinutes = 0,
        initialDistanceMeters = activeExisting?.initialDistanceMeters,
        remainingDistanceMeters = 0,
        etaTimestampMillis = null,
        matchedRuleName = "Stage 3 · 送达完结正则",
        lastUpdatedMillis = now,
        deliveredAtMillis = now,
        autoDismissAtMillis = now + autoDismissSeconds * 1000L,
        isPromotedOngoing = existingOrder?.isPromotedOngoing ?: false,
        userDismissed = false,
      )
    }

    // 2. Check Stage 2 Case B: Dynamic ETA (minutes)
    val etaMatch = findSafe(config.stage2EtaRegex, fullText)
    if (etaMatch != null) {
      val remainingMinutes = etaMatch.groupValues.getOrNull(1)?.toIntOrNull()?.coerceAtLeast(1)
      if (remainingMinutes != null) {
        val initialMinutes =
          if (activeExisting?.initialMinutes != null) {
            maxOf(activeExisting.initialMinutes, remainingMinutes)
          } else {
            remainingMinutes.coerceAtLeast(1)
          }
        val progress = calculateTimeProgress(initialMinutes, remainingMinutes)
        val etaMillis = input.timestampMillis + remainingMinutes * 60_000L
        return LiveOrderState(
          orderKey = orderKey,
          notificationId = notificationId,
          packageName = input.packageName,
          appName = input.appName,
          accentColorHex = accentColorHex,
          merchantName = merchant,
          title = titleOrMerchant ?: "${input.appName}配送中",
          contentText = displayBody,
          stage = DeliveryStage.DELIVERING,
          progress = progress,
          chipShortText = "${remainingMinutes}分钟",
          initialMinutes = initialMinutes,
          remainingMinutes = remainingMinutes,
          initialDistanceMeters = activeExisting?.initialDistanceMeters,
          remainingDistanceMeters = activeExisting?.remainingDistanceMeters,
          etaTimestampMillis = etaMillis,
          matchedRuleName = "Stage 2 · 动态时间正则 (基准 ${initialMinutes}m)",
          lastUpdatedMillis = input.timestampMillis,
          userDismissed = false,
        )
      }
    }

    // 3. Check Stage 2 Case B: Dynamic Distance
    val distMatch = findSafe(config.stage2DistanceRegex, fullText)
    if (distMatch != null) {
      val rawValue = distMatch.groupValues.getOrNull(1)?.toDoubleOrNull()
      val unit = distMatch.groupValues.getOrNull(2).orEmpty().lowercase(Locale.ROOT)
      if (rawValue != null) {
        val meters =
          if (unit == "km" || unit == "公里") {
            (rawValue * 1000.0).toInt().coerceAtLeast(1)
          } else {
            rawValue.toInt().coerceAtLeast(1)
          }
        val existingInitialDist = activeExisting?.initialDistanceMeters
        val initialDist =
          if (existingInitialDist != null) {
            maxOf(existingInitialDist, meters)
          } else {
            null
          }
        val progress =
          if (initialDist != null) {
            calculateDistanceProgress(initialDist, meters)
          } else {
            estimateInitialDistanceProgress(meters)
          }
        val chipText = formatDistanceChip(meters)
        return LiveOrderState(
          orderKey = orderKey,
          notificationId = notificationId,
          packageName = input.packageName,
          appName = input.appName,
          accentColorHex = accentColorHex,
          merchantName = merchant,
          title = titleOrMerchant ?: "${input.appName}配送中",
          contentText = displayBody,
          stage = DeliveryStage.DELIVERING,
          progress = progress,
          chipShortText = chipText,
          initialMinutes = existingOrder?.initialMinutes,
          remainingMinutes = existingOrder?.remainingMinutes,
          initialDistanceMeters = initialDist ?: maxOf(meters, 1500),
          remainingDistanceMeters = meters,
          etaTimestampMillis = existingOrder?.etaTimestampMillis,
          matchedRuleName = "Stage 2 · 动态距离正则 ($chipText)",
          lastUpdatedMillis = input.timestampMillis,
          userDismissed = false,
        )
      }
    }

    // 4. Check Stage 2 Case A: Static Delivering (no specific time/distance)
    if (matchesSafe(config.stage2StaticDeliveringRegex, fullText)) {
      val preservedProgress =
        existingOrder
          ?.takeIf { it.stage == DeliveryStage.DELIVERING && it.progress > 50 }
          ?.progress
          ?: 50
      return LiveOrderState(
        orderKey = orderKey,
        notificationId = notificationId,
        packageName = input.packageName,
        appName = input.appName,
        accentColorHex = accentColorHex,
        merchantName = merchant,
        title = titleOrMerchant ?: "${input.appName}配送中",
        contentText = displayBody,
        stage = DeliveryStage.DELIVERING,
        progress = preservedProgress,
        chipShortText = "配送中",
        initialMinutes = existingOrder?.initialMinutes,
        remainingMinutes = existingOrder?.remainingMinutes,
        initialDistanceMeters = existingOrder?.initialDistanceMeters,
        remainingDistanceMeters = existingOrder?.remainingDistanceMeters,
        etaTimestampMillis = existingOrder?.etaTimestampMillis,
        matchedRuleName = "Stage 2 · 静态配送正则",
        lastUpdatedMillis = input.timestampMillis,
        userDismissed = false,
      )
    }

    // 5. Check Stage 1: Preparing / Accepted (0%)
    if (matchesSafe(config.stage1PreparingRegex, fullText)) {
      val shortText =
        if (fullText.contains("已接单") || fullText.contains("已付款")) "已接单" else "备餐中"
      return LiveOrderState(
        orderKey = orderKey,
        notificationId = notificationId,
        packageName = input.packageName,
        appName = input.appName,
        accentColorHex = accentColorHex,
        merchantName = merchant,
        title = titleOrMerchant ?: "${input.appName}备餐中",
        contentText = displayBody,
        stage = DeliveryStage.PREPARING,
        progress = 0,
        chipShortText = shortText,
        matchedRuleName = "Stage 1 · 备餐/接单正则",
        lastUpdatedMillis = input.timestampMillis,
        userDismissed = false,
      )
    }

    return null
  }

  fun applyMinuteTick(order: LiveOrderState, nowMillis: Long = System.currentTimeMillis()): LiveOrderState {
    if (order.stage != DeliveryStage.DELIVERING) return order
    val currentRemaining = order.remainingMinutes ?: return order
    if (currentRemaining <= 1) return order

    val nextRemaining = (currentRemaining - 1).coerceAtLeast(1)
    val initial = (order.initialMinutes ?: currentRemaining).coerceAtLeast(1)
    val nextProgress = calculateTimeProgress(initial, nextRemaining)
    return order.copy(
      remainingMinutes = nextRemaining,
      progress = nextProgress,
      chipShortText = "${nextRemaining}分钟",
      etaTimestampMillis = nowMillis + nextRemaining * 60_000L,
      lastUpdatedMillis = nowMillis,
      matchedRuleName = "Stage 2 · 协程平滑递减 (基准 ${initial}m)",
    )
  }

  fun preview(
    rawText: String,
    config: RegexConfig,
    simulatedInitialMinutes: Int? = null,
  ): RegexMatchPreview {
    if (rawText.isBlank()) {
      return RegexMatchPreview(false, null, 0, "--", "未输入文本")
    }
    if (matchesSafe(config.stage3DeliveredRegex, rawText)) {
      return RegexMatchPreview(true, DeliveryStage.DELIVERED, 100, "已送达", "Stage 3 · 送达完结正则")
    }
    val etaMatch = findSafe(config.stage2EtaRegex, rawText)
    if (etaMatch != null) {
      val mins = etaMatch.groupValues.getOrNull(1)?.toIntOrNull()?.coerceAtLeast(1)
      if (mins != null) {
        val initial = maxOf(simulatedInitialMinutes ?: mins, mins)
        val progress = calculateTimeProgress(initial, mins)
        return RegexMatchPreview(
          matched = true,
          stage = DeliveryStage.DELIVERING,
          progress = progress,
          chipShortText = "${mins}分钟",
          matchedRuleName = "Stage 2 · 动态时间正则 (基准 ${initial}m)",
          capturedMinutes = mins,
        )
      }
    }
    val distMatch = findSafe(config.stage2DistanceRegex, rawText)
    if (distMatch != null) {
      val value = distMatch.groupValues.getOrNull(1)?.toDoubleOrNull()
      val unit = distMatch.groupValues.getOrNull(2).orEmpty().lowercase(Locale.ROOT)
      if (value != null) {
        val meters = if (unit == "km" || unit == "公里") (value * 1000).toInt() else value.toInt()
        val progress = estimateInitialDistanceProgress(meters)
        val chip = formatDistanceChip(meters)
        return RegexMatchPreview(
          matched = true,
          stage = DeliveryStage.DELIVERING,
          progress = progress,
          chipShortText = chip,
          matchedRuleName = "Stage 2 · 动态距离正则",
          capturedDistanceMeters = meters,
        )
      }
    }
    if (matchesSafe(config.stage2StaticDeliveringRegex, rawText)) {
      return RegexMatchPreview(true, DeliveryStage.DELIVERING, 50, "配送中", "Stage 2 · 静态配送正则")
    }
    if (matchesSafe(config.stage1PreparingRegex, rawText)) {
      val shortText = if (rawText.contains("已接单") || rawText.contains("已付款")) "已接单" else "备餐中"
      return RegexMatchPreview(true, DeliveryStage.PREPARING, 0, shortText, "Stage 1 · 备餐/接单正则")
    }
    return RegexMatchPreview(false, null, 0, "未匹配", "无规则命中 (将忽略)")
  }

  /**
   * Computes Stage 2 progress (1% ~ 99%).
   * - If initialMinutes == 10: strictly +10% per 1 minute reduced (10m->10%, 9m->20%, ..., 2m->90%, 1m->99%).
   * - General formula: (((initialMinutes - remainingMinutes) / initialMinutes) * 90 + 10).coerceIn(10, 95).
   */
  fun calculateTimeProgress(initialMinutes: Int, remainingMinutes: Int): Int {
    val safeInitial = initialMinutes.coerceAtLeast(1)
    val elapsed = (safeInitial - remainingMinutes).coerceAtLeast(0)
    return if (safeInitial == 10) {
      (10 + elapsed * 10).coerceIn(10, 99)
    } else {
      (((elapsed.toFloat() / safeInitial) * 90f) + 10f).toInt().coerceIn(10, 95)
    }
  }

  fun calculateDistanceProgress(initialMeters: Int, remainingMeters: Int): Int {
    val safeInitial = initialMeters.coerceAtLeast(1)
    val covered = (safeInitial - remainingMeters).coerceAtLeast(0)
    return (((covered.toFloat() / safeInitial) * 90f) + 10f).toInt().coerceIn(10, 95)
  }

  private fun estimateInitialDistanceProgress(meters: Int): Int =
    when {
      meters <= 200 -> 90
      meters <= 500 -> 75
      meters <= 1000 -> 55
      meters <= 2000 -> 35
      else -> 15
    }

  private fun formatDistanceChip(meters: Int): String =
    if (meters < 1000) {
      "${meters}m"
    } else {
      val km = meters / 1000f
      val formatted = String.format(Locale.US, "%.1f", km).removeSuffix(".0")
      "${formatted}km"
    }

  private fun matchesSafe(pattern: String, text: String): Boolean {
    if (pattern.isBlank()) return false
    return runCatching {
      Regex(pattern, setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        .containsMatchIn(text)
    }.getOrDefault(false)
  }

  private fun findSafe(pattern: String, text: String): MatchResult? {
    if (pattern.isBlank()) return null
    return runCatching {
      Regex(pattern, setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        .find(text)
    }.getOrNull()
  }

  companion object {
    private const val BASE_NOTIFICATION_ID = 41_000

    /** Words that mark a title/body as status or promo text, never a merchant name. */
    private val GENERIC_MERCHANT_WORDS =
      listOf(
        "已接单", "已付款", "备餐", "配送", "送达", "骑手", "预计", "分钟", "公里",
        "完成", "感谢", "外卖", "跑腿", "取餐", "送餐", "正在", "即将", "到达",
        "出发", "已取", "已出", "到店", "自取", "打包", "催单", "退款", "取消",
        "超时", "准时", "评价", "红包", "优惠", "神券", "会员", "商家",
      )

    fun isValidRegex(pattern: String): Boolean =
      pattern.isNotBlank() && runCatching { Regex(pattern) }.isSuccess
  }
}
