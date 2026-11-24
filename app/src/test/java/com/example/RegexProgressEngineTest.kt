package com.example

import com.example.data.ActiveOrderJsonCodec
import com.example.engine.ParsedNotificationInput
import com.example.engine.RegexProgressEngine
import com.example.model.DeliveryStage
import com.example.model.LiveOrderState
import com.example.model.MonitoredApp
import com.example.model.RegexConfig
import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RegexProgressEngineTest {

  private val engine = RegexProgressEngine()
  private val config = RegexConfig()

  @Test
  fun stage1PreparingSetsZeroProgress() {
    val input =
      ParsedNotificationInput(
        packageName = "com.sankuai.meituan",
        appName = "美团",
        title = "美团外卖 #101",
        text = "商家已接单，商家备餐中",
        bigText = "",
      )
    val state =
      engine.evaluate(
        input = input,
        orderKey = "com.sankuai.meituan#101",
        existingOrder = null,
        config = config,
        accentColorHex = 0xFFFFC300L,
        autoDismissSeconds = 300,
      )

    assertNotNull(state)
    assertEquals(DeliveryStage.PREPARING, state!!.stage)
    assertEquals(0, state.progress)
    assertEquals("已接单", state.chipShortText)
  }

  @Test
  fun stage2StaticDeliveringSets50Percent() {
    val input =
      ParsedNotificationInput(
        packageName = "me.ele",
        appName = "饿了么",
        title = "饿了么订单 #202",
        text = "骑手送餐中",
        bigText = "",
      )
    val state =
      engine.evaluate(
        input = input,
        orderKey = "me.ele#202",
        existingOrder = null,
        config = config,
        accentColorHex = 0xFF0093FFL,
        autoDismissSeconds = 300,
      )

    assertNotNull(state)
    assertEquals(DeliveryStage.DELIVERING, state!!.stage)
    assertEquals(50, state.progress)
    assertEquals("配送中", state.chipShortText)
  }

  @Test
  fun stage2TenMinuteBaselineIncreases10PercentPerMinute() {
    val initialInput =
      ParsedNotificationInput(
        packageName = "com.sankuai.meituan",
        appName = "美团",
        title = "美团外卖 #303",
        text = "骑手正前往目的地，预计10分钟送达",
        bigText = "",
      )
    val first =
      engine.evaluate(
        input = initialInput,
        orderKey = "com.sankuai.meituan#303",
        existingOrder = null,
        config = config,
        accentColorHex = 0xFFFFC300L,
        autoDismissSeconds = 300,
      )!!

    assertEquals(10, first.initialMinutes)
    assertEquals(10, first.progress)
    assertEquals("10分钟", first.chipShortText)

    val nineMin = engine.applyMinuteTick(first)
    assertEquals(9, nineMin.remainingMinutes)
    assertEquals(20, nineMin.progress)
    assertEquals("9分钟", nineMin.chipShortText)

    val eightMin = engine.applyMinuteTick(nineMin)
    assertEquals(8, eightMin.remainingMinutes)
    assertEquals(30, eightMin.progress)

    val fiveMinUpdate =
      engine.evaluate(
        input = initialInput.copy(text = "预计 5 min 送达"),
        orderKey = "com.sankuai.meituan#303",
        existingOrder = eightMin,
        config = config,
        accentColorHex = 0xFFFFC300L,
        autoDismissSeconds = 300,
      )!!
    assertEquals(60, fiveMinUpdate.progress)
    assertEquals("5分钟", fiveMinUpdate.chipShortText)
  }

  @Test
  fun stage3DeliveredSets100PercentAndAutoDismissTimestamp() {
    val now = 1_700_000_000_000L
    val input =
      ParsedNotificationInput(
        packageName = "com.jingdong.app.mall",
        appName = "京东",
        title = "京东秒送 #404",
        text = "您的订单已送达，感谢使用",
        bigText = "",
        timestampMillis = now,
      )
    val state =
      engine.evaluate(
        input = input,
        orderKey = "com.jingdong.app.mall#404",
        existingOrder = null,
        config = config,
        accentColorHex = 0xFFE1251BL,
        autoDismissSeconds = 120,
      )!!

    assertEquals(DeliveryStage.DELIVERED, state.stage)
    assertEquals(100, state.progress)
    assertEquals("已送达", state.chipShortText)
    assertEquals(now + 120_000L, state.autoDismissAtMillis)
  }

  @Test
  fun unrelatedNotificationIsIgnored() {
    val input =
      ParsedNotificationInput(
        packageName = "com.sankuai.meituan",
        appName = "美团",
        title = "周末神券来袭",
        text = "满30减15立即领取",
        bigText = "",
      )
    val state =
      engine.evaluate(
        input = input,
        orderKey = "com.sankuai.meituan:promo",
        existingOrder = null,
        config = config,
        accentColorHex = 0xFFFFC300L,
        autoDismissSeconds = 300,
      )
    assertNull(state)
  }

  @Test
  fun newOrderAfterDeliveredDoesNotInheritStaleBaseline() {
    val delivered =
      engine.evaluate(
        input =
          ParsedNotificationInput(
            packageName = "com.sankuai.meituan",
            appName = "美团",
            title = "美团外卖",
            text = "您的订单已送达，感谢使用",
            bigText = "",
          ),
        orderKey = "com.sankuai.meituan:default",
        existingOrder = null,
        config = config,
        accentColorHex = 0xFFFFC300L,
        autoDismissSeconds = 300,
      )!!.copy(initialMinutes = 20)

    val freshOrder =
      engine.evaluate(
        input =
          ParsedNotificationInput(
            packageName = "com.sankuai.meituan",
            appName = "美团",
            title = "美团外卖",
            text = "骑手正前往目的地，预计10分钟送达",
            bigText = "",
          ),
        orderKey = "com.sankuai.meituan:default",
        existingOrder = delivered,
        config = config,
        accentColorHex = 0xFFFFC300L,
        autoDismissSeconds = 300,
      )!!

    assertEquals(DeliveryStage.DELIVERING, freshOrder.stage)
    assertEquals(10, freshOrder.initialMinutes)
    assertEquals(10, freshOrder.progress)

    val orderProgress = freshOrder.toOrderProgress(rawText = "骑手正前往目的地，预计10分钟送达")
    assertEquals("美团外卖", orderProgress.title)
    assertEquals("骑手正前往目的地，预计10分钟送达", orderProgress.text)
    assertEquals(DeliveryStage.DELIVERING, orderProgress.stage)
    assertEquals(10, orderProgress.progress)
    assertEquals("10分钟", orderProgress.chipShortText)
  }

  @Test
  fun sameOrderWithoutIdStaysInOneLiveNotification() {
    val firstInput =
      ParsedNotificationInput(
        packageName = "com.sankuai.meituan",
        appName = "美团",
        title = "麦当劳（中关村店）",
        text = "商家已接单，商家备餐中",
        bigText = "",
        orderHintKey = "0|com.sankuai.meituan|1001|null|1001",
        timestampMillis = 1_700_000_000_000L,
      )
    val key1 = engine.resolveOrderKey(firstInput, emptyMap(), config)
    assertEquals("com.sankuai.meituan:m:麦当劳", key1)

    val firstState =
      engine.evaluate(
        input = firstInput,
        orderKey = key1,
        existingOrder = null,
        config = config,
        accentColorHex = 0xFFFFC300L,
        autoDismissSeconds = 300,
      )!!
    assertEquals(DeliveryStage.PREPARING, firstState.stage)
    assertEquals("麦当劳（中关村店）", firstState.merchantName)

    // Same order, new source notification (different sbn key), progress update.
    val secondInput =
      firstInput.copy(
        text = "骑手正前往目的地，预计10分钟送达",
        orderHintKey = "0|com.sankuai.meituan|2002|null|2002",
        timestampMillis = 1_700_000_060_000L,
      )
    val key2 = engine.resolveOrderKey(secondInput, mapOf(key1 to firstState), config)
    assertEquals(key1, key2)

    val secondState =
      engine.evaluate(
        input = secondInput,
        orderKey = key2,
        existingOrder = firstState,
        config = config,
        accentColorHex = 0xFFFFC300L,
        autoDismissSeconds = 300,
      )!!
    assertEquals(firstState.notificationId, secondState.notificationId)
    assertEquals(DeliveryStage.DELIVERING, secondState.stage)
  }

  @Test
  fun twoOrdersFromDifferentMerchantsStaySeparate() {
    val firstInput =
      ParsedNotificationInput(
        packageName = "com.sankuai.meituan",
        appName = "美团",
        title = "麦当劳（中关村店）",
        text = "商家已接单，商家备餐中",
        bigText = "",
        timestampMillis = 1_700_000_000_000L,
      )
    val keyA = engine.resolveOrderKey(firstInput, emptyMap(), config)
    val firstState =
      engine.evaluate(
        input = firstInput,
        orderKey = keyA,
        existingOrder = null,
        config = config,
        accentColorHex = 0xFFFFC300L,
        autoDismissSeconds = 300,
      )!!

    val secondInput =
      ParsedNotificationInput(
        packageName = "com.sankuai.meituan",
        appName = "美团",
        title = "肯德基（西单店）",
        text = "商家已接单，商家备餐中",
        bigText = "",
        timestampMillis = 1_700_000_030_000L,
      )
    val keyB = engine.resolveOrderKey(secondInput, mapOf(keyA to firstState), config)
    assertEquals("com.sankuai.meituan:m:肯德基", keyB)
    assertEquals(false, keyA == keyB)
  }

  @Test
  fun secondOrderFromSameMerchantWhileFirstDeliveringGetsNewKey() {
    val firstInput =
      ParsedNotificationInput(
        packageName = "com.sankuai.meituan",
        appName = "美团",
        title = "麦当劳（中关村店）",
        text = "商家已接单，商家备餐中",
        bigText = "",
        timestampMillis = 1_700_000_000_000L,
      )
    val key1 = engine.resolveOrderKey(firstInput, emptyMap(), config)
    val preparing =
      engine.evaluate(
        input = firstInput,
        orderKey = key1,
        existingOrder = null,
        config = config,
        accentColorHex = 0xFFFFC300L,
        autoDismissSeconds = 300,
      )!!

    val deliveringInput =
      firstInput.copy(
        text = "骑手正前往目的地，预计10分钟送达",
        timestampMillis = 1_700_000_060_000L,
      )
    val delivering =
      engine.evaluate(
        input = deliveringInput,
        orderKey = key1,
        existingOrder = preparing,
        config = config,
        accentColorHex = 0xFFFFC300L,
        autoDismissSeconds = 300,
      )!!
    assertEquals(DeliveryStage.DELIVERING, delivering.stage)

    // A new order from the same merchant starts while the first is still delivering.
    val newOrderInput =
      firstInput.copy(
        text = "商家已接单，商家备餐中",
        timestampMillis = 1_700_000_120_000L,
      )
    val key2 = engine.resolveOrderKey(newOrderInput, mapOf(key1 to delivering), config)
    assertEquals("$key1#2", key2)
  }

  @Test
  fun fullOrderKeyHintIsUsedAsIs() {
    val input =
      ParsedNotificationInput(
        packageName = "com.sankuai.meituan",
        appName = "美团",
        title = "麦当劳（中关村店）",
        text = "骑手已取餐正赶往您的位置，预计10分钟送达",
        bigText = "",
        orderHintKey = "com.sankuai.meituan:m:麦当劳（中关村店）",
      )
    assertEquals(
      "com.sankuai.meituan:m:麦当劳（中关村店）",
      engine.resolveOrderKey(input, emptyMap(), config),
    )
  }

  @Test
  fun merchantExtractionFromGenericTitleAndBody() {
    val merchantInput =
      ParsedNotificationInput(
        packageName = "com.sankuai.meituan",
        appName = "美团",
        title = "美团外卖",
        text = "麦当劳已接单，正在备餐",
        bigText = "",
      )
    assertEquals("麦当劳", engine.extractMerchantName(merchantInput))

    val bracketInput =
      ParsedNotificationInput(
        packageName = "me.ele",
        appName = "饿了么",
        title = "饿了么",
        text = "【老乡鸡】您的订单商家已接单",
        bigText = "",
      )
    assertEquals("老乡鸡", engine.extractMerchantName(bracketInput))

    val promoInput =
      ParsedNotificationInput(
        packageName = "com.sankuai.meituan",
        appName = "美团",
        title = "周末神券来袭",
        text = "满30减15立即领取",
        bigText = "",
      )
    assertEquals(null, engine.extractMerchantName(promoInput))
  }

  @Test
  fun genericTitleFallsBackToMerchantInEvaluate() {
    val input =
      ParsedNotificationInput(
        packageName = "com.sankuai.meituan",
        appName = "美团",
        title = "美团外卖",
        text = "商家已接单，麦当劳正在备餐",
        bigText = "",
      )
    val state =
      engine.evaluate(
        input = input,
        orderKey = "com.sankuai.meituan:m:麦当劳",
        existingOrder = null,
        config = config,
        accentColorHex = 0xFFFFC300L,
        autoDismissSeconds = 300,
      )!!
    assertEquals("麦当劳", state.title)
    assertEquals("麦当劳", state.merchantName)
    assertEquals(DeliveryStage.PREPARING, state.stage)
  }

  @Test
  fun updateMentioningKnownMerchantAttachesToThatOrder() {
    val firstInput =
      ParsedNotificationInput(
        packageName = "com.sankuai.meituan",
        appName = "美团",
        title = "麦当劳（中关村店）",
        text = "商家已接单，商家备餐中",
        bigText = "",
        timestampMillis = 1_700_000_000_000L,
      )
    val key1 = engine.resolveOrderKey(firstInput, emptyMap(), config)
    val firstState =
      engine.evaluate(
        input = firstInput,
        orderKey = key1,
        existingOrder = null,
        config = config,
        accentColorHex = 0xFFFFC300L,
        autoDismissSeconds = 300,
      )!!

    // Generic title, body only mentions the merchant in passing.
    val updateInput =
      ParsedNotificationInput(
        packageName = "com.sankuai.meituan",
        appName = "美团",
        title = "美团外卖",
        text = "骑手已取餐，麦当劳的订单配送中",
        bigText = "",
        timestampMillis = 1_700_000_060_000L,
      )
    val key2 = engine.resolveOrderKey(updateInput, mapOf(key1 to firstState), config)
    assertEquals(key1, key2)
  }

  // ---- Third progress scheme: pickup anchor + latest ETA ----

  private fun noonMillis(): Long {
    return Calendar.getInstance().apply {
      set(2026, Calendar.NOVEMBER, 24, 12, 0, 0)
      set(Calendar.MILLISECOND, 0)
    }.timeInMillis
  }

  private fun evaluateMeituan(
    text: String,
    timestampMillis: Long = 1_700_000_000_000L,
    existingOrder: LiveOrderState? = null,
    orderKey: String = "com.sankuai.meituan:m:麦当劳",
  ) = engine.evaluate(
    input = ParsedNotificationInput(
      packageName = "com.sankuai.meituan",
      appName = "美团",
      title = "麦当劳",
      text = text,
      bigText = "",
      timestampMillis = timestampMillis,
    ),
    orderKey = orderKey,
    existingOrder = existingOrder,
    config = config,
    accentColorHex = 0xFFFFC300L,
    autoDismissSeconds = 300,
  )

  @Test
  fun rangeMinutesEtaUsesLatestTime() {
    val state = evaluateMeituan("骑手配送中，预计 30-45 分钟送达")!!
    assertEquals(DeliveryStage.DELIVERING, state.stage)
    assertEquals(45, state.remainingMinutes)
    assertEquals(45, state.initialMinutes)
    assertEquals(10, state.progress)
    assertEquals("45分钟", state.chipShortText)
    assertTrue(state.matchedRuleName.contains("最晚"))
  }

  @Test
  fun clockRangeEtaUsesLatestTime() {
    val state = evaluateMeituan("骑手配送中，预计 12:30-12:45 送达", timestampMillis = noonMillis())!!
    assertEquals(DeliveryStage.DELIVERING, state.stage)
    assertEquals(45, state.remainingMinutes)
    assertEquals(45, state.initialMinutes)
    assertEquals("45分钟", state.chipShortText)
    assertTrue(state.matchedRuleName.contains("最晚"))
  }

  @Test
  fun singleClockEta() {
    val state = evaluateMeituan("骑手配送中，预计 12:30 送达", timestampMillis = noonMillis())!!
    assertEquals(DeliveryStage.DELIVERING, state.stage)
    assertEquals(30, state.remainingMinutes)
    assertEquals(30, state.initialMinutes)
  }

  @Test
  fun clockRangeEtaInsideWindow() {
    val state =
      evaluateMeituan(
        "骑手配送中，预计 12:30-12:45 送达",
        timestampMillis = noonMillis() + 35 * 60_000L,
      )!!
    assertEquals(10, state.remainingMinutes)
  }

  @Test
  fun pickupOnlySetsDeliveringAndAnchor() {
    val pickupTime = 1_700_000_000_000L
    val state = evaluateMeituan("骑手已成功取到商品，正在火速赶往您身边", timestampMillis = pickupTime)!!
    assertEquals(DeliveryStage.DELIVERING, state.stage)
    assertEquals(50, state.progress)
    assertEquals("已取货", state.chipShortText)
    assertEquals(pickupTime, state.pickupAtMillis)
    assertTrue(state.matchedRuleName.contains("骑手已取货"))
  }

  @Test
  fun pickupAnchorsLaterEtaBaseline() {
    val pickupTime = 1_700_000_000_000L
    val pickedUp = evaluateMeituan("骑手已成功取到商品", timestampMillis = pickupTime)!!
    assertEquals(DeliveryStage.DELIVERING, pickedUp.stage)
    assertEquals(pickupTime, pickedUp.pickupAtMillis)

    // 10 minutes after pickup the ETA says 20 minutes left: the baseline is the
    // 30-minute span from pickup to ETA, so progress is 40, not 10.
    val delivering =
      evaluateMeituan(
        "骑手正前往目的地，预计20分钟送达",
        timestampMillis = pickupTime + 10 * 60_000L,
        existingOrder = pickedUp,
      )!!
    assertEquals(30, delivering.initialMinutes)
    assertEquals(20, delivering.remainingMinutes)
    assertEquals(40, delivering.progress)
    assertEquals(pickupTime, delivering.pickupAtMillis)
  }

  @Test
  fun pickupAndRangeInOneNotification() {
    val noon = noonMillis()
    val state =
      evaluateMeituan("骑手已成功取到商品，预计 12:30-12:45 送达", timestampMillis = noon)!!
    assertEquals(DeliveryStage.DELIVERING, state.stage)
    assertEquals(45, state.remainingMinutes)
    assertEquals(45, state.initialMinutes)
    assertEquals(noon, state.pickupAtMillis)
    assertTrue(state.matchedRuleName.contains("最晚"))
  }

  // ---- 淘宝闪购 preset ----

  @Test
  fun taobaoFlashPurchasePresetExists() {
    val preset = MonitoredApp.PRESETS.firstOrNull { it.packageName == "com.taobao.taobao" }
    assertNotNull(preset)
    assertEquals("淘宝闪购", preset!!.displayName)
    assertEquals(0xFFFF5000L, preset.accentColorHex)
    assertTrue(preset.enabled)
    assertTrue(preset.isPreset)
  }

  @Test
  fun taobaoOrderKeysByMerchant() {
    val input =
      ParsedNotificationInput(
        packageName = "com.taobao.taobao",
        appName = "淘宝闪购",
        title = "麦当劳",
        text = "商家已接单，商家备餐中",
        bigText = "",
        timestampMillis = 1_700_000_000_000L,
      )
    val key = engine.resolveOrderKey(input, emptyMap(), config)
    assertEquals("com.taobao.taobao:m:麦当劳", key)
  }

  // ---- Order persistence codec ----

  @Test
  fun activeOrderCodecRoundTrip() {
    val original =
      LiveOrderState(
        orderKey = "com.taobao.taobao:m:麦当劳",
        notificationId = 41234,
        packageName = "com.taobao.taobao",
        appName = "淘宝闪购",
        accentColorHex = 0xFFFF5000L,
        merchantName = "麦当劳（中关村店）",
        pickupAtMillis = 1_700_000_000_000L,
        sourceNotificationKey = "0|com.taobao.taobao|99|null|99",
        title = "麦当劳",
        contentText = "骑手已成功取到商品，预计 12:30-12:45 送达",
        stage = DeliveryStage.DELIVERING,
        progress = 40,
        chipShortText = "45分钟",
        initialMinutes = 45,
        remainingMinutes = 45,
        initialDistanceMeters = 2500,
        remainingDistanceMeters = 1200,
        etaTimestampMillis = 1_700_000_270_000L,
        matchedRuleName = "Stage 2 · 预计送达最晚时间",
        lastUpdatedMillis = 1_700_000_060_000L,
        deliveredAtMillis = null,
        autoDismissAtMillis = null,
        isPromotedOngoing = true,
        promotionDiagnostic = "Android 16 ProgressStyle + Now Bar 已激活 (Promoted)",
        userDismissed = false,
      )
    val decoded = ActiveOrderJsonCodec.decode(ActiveOrderJsonCodec.encode(listOf(original)))
    assertEquals(listOf(original), decoded)
  }

  @Test
  fun activeOrderCodecSkipsCorruptEntries() {
    val decoded = ActiveOrderJsonCodec.decode("[{\"orderKey\": 123}, {\"nope\": true}]")
    assertEquals(emptyList<LiveOrderState>(), decoded)
    assertEquals(emptyList<LiveOrderState>(), ActiveOrderJsonCodec.decode("not json"))
  }
}
