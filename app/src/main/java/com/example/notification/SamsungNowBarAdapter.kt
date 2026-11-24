package com.example.notification

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.model.DeliveryStage
import com.example.model.LiveOrderState
import java.util.concurrent.ConcurrentHashMap

data class LivePostResult(
  val posted: Boolean,
  val isPromotedOngoing: Boolean,
  val hasPromotableCharacteristics: Boolean,
  val diagnosticMessage: String,
)

/**
 * Adapter targeting Android 16 (API 36) native Live Updates (`Notification.ProgressStyle` +
 * `setRequestPromotedOngoing` + `setShortCriticalText`) and Samsung One UI 8/8.5+ Now Bar.
 *
 * Never uses custom RemoteViews (which One UI 8+ downgrades/blocks for Now Bar).
 *
 * Per-post work is kept minimal: the notification channel is ensured once per
 * process, the per-order PendingIntents are cached by notification id, the
 * source-app launch intent is cached per package, and the promoted-notification
 * capability is re-checked at most once a minute.
 */
class SamsungNowBarAdapter(private val context: Context) {

  private val notificationManager: NotificationManager =
    context.getSystemService(NotificationManager::class.java)

  private var channelEnsured = false

  private data class CachedIntents(
    val content: PendingIntent,
    val dismiss: PendingIntent,
    val delete: PendingIntent,
  )

  private val pendingIntentCache = ConcurrentHashMap<Int, CachedIntents>()
  private val launchIntentCache = ConcurrentHashMap<String, PendingIntent?>()

  @Volatile private var lastPromoteCheckMillis = 0L
  @Volatile private var lastPromoteCheckResult = false

  fun ensureNotificationChannel() {
    if (channelEnsured) return
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val existing = notificationManager.getNotificationChannel(CHANNEL_ID)
      if (existing == null) {
        val channel =
          NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.channel_live_delivery_name),
            NotificationManager.IMPORTANCE_DEFAULT,
          ).apply {
            description = context.getString(R.string.channel_live_delivery_desc)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setShowBadge(true)
            enableLights(false)
            enableVibration(false)
            setSound(null, null)
          }
        notificationManager.createNotificationChannel(channel)
      }
    }
    channelEnsured = true
  }

  fun canPostNotifications(): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      val granted =
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
          PackageManager.PERMISSION_GRANTED
      if (!granted) return false
    }
    return notificationManager.areNotificationsEnabled()
  }

  fun canPostPromotedNotifications(): Boolean {
    if (Build.VERSION.SDK_INT < 36) return false
    // The capability only changes when the user toggles it in system settings,
    // so re-check at most once a minute instead of on every post.
    val now = System.currentTimeMillis()
    if (now - lastPromoteCheckMillis < 60_000L) return lastPromoteCheckResult
    val result =
      runCatching { notificationManager.canPostPromotedNotifications() }.getOrDefault(false)
    lastPromoteCheckResult = result
    lastPromoteCheckMillis = now
    return result
  }

  private fun cachedIntentsFor(order: LiveOrderState): CachedIntents {
    return pendingIntentCache.getOrPut(order.notificationId) {
      val openAppIntent =
        Intent(context, MainActivity::class.java).apply {
          flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
          putExtra(EXTRA_ORDER_KEY, order.orderKey)
        }
      val content =
        PendingIntent.getActivity(
          context,
          order.notificationId,
          openAppIntent,
          PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
      val dismissIntent =
        Intent(context, LiveNotificationActionReceiver::class.java).apply {
          action = LiveNotificationActionReceiver.ACTION_DISMISS_LIVE_ORDER
          putExtra(LiveNotificationActionReceiver.EXTRA_ORDER_KEY, order.orderKey)
          putExtra(LiveNotificationActionReceiver.EXTRA_NOTIFICATION_ID, order.notificationId)
        }
      val dismiss =
        PendingIntent.getBroadcast(
          context,
          order.notificationId + 100_000,
          dismissIntent,
          PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
      val deleteIntent =
        Intent(context, LiveNotificationActionReceiver::class.java).apply {
          action = LiveNotificationActionReceiver.ACTION_NOTIFICATION_DELETED
          putExtra(LiveNotificationActionReceiver.EXTRA_ORDER_KEY, order.orderKey)
          putExtra(LiveNotificationActionReceiver.EXTRA_NOTIFICATION_ID, order.notificationId)
        }
      val delete =
        PendingIntent.getBroadcast(
          context,
          order.notificationId + 200_000,
          deleteIntent,
          PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
      CachedIntents(content, dismiss, delete)
    }
  }

  private fun openSourcePendingIntentFor(packageName: String): PendingIntent? {
    // The source-app launch intent carries no per-order extras, so one cached
    // PendingIntent per package serves every order from that app. A missing app
    // is not cached (ConcurrentHashMap forbids null values), so it re-checks.
    launchIntentCache[packageName]?.let { return it }
    val pending =
      context.packageManager.getLaunchIntentForPackage(packageName)?.let { launchIntent ->
        PendingIntent.getActivity(
          context,
          packageName.hashCode(),
          launchIntent,
          PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
      }
    if (pending != null) launchIntentCache[packageName] = pending
    return pending
  }

  fun postOrUpdateLiveOrder(order: LiveOrderState): LivePostResult {
    ensureNotificationChannel()

    if (!canPostNotifications()) {
      return LivePostResult(
        posted = false,
        isPromotedOngoing = false,
        hasPromotableCharacteristics = false,
        diagnosticMessage = "缺少 POST_NOTIFICATIONS 权限",
      )
    }

    val cachedIntents = cachedIntentsFor(order)
    val contentPendingIntent = cachedIntents.content
    val dismissPendingIntent = cachedIntents.dismiss
    val deletePendingIntent = cachedIntents.delete
    val openSourcePendingIntent = openSourcePendingIntentFor(order.packageName)

    val brandColorInt = order.accentColorHex.toInt()
    val stageColorInt =
      when (order.stage) {
        DeliveryStage.PREPARING -> PREPARING_COLOR
        DeliveryStage.DELIVERING -> brandColorInt
        DeliveryStage.DELIVERED -> DELIVERED_COLOR
      }

    val nowBarExtras =
      Bundle().apply {
        putBoolean(Notification.EXTRA_REQUEST_PROMOTED_ONGOING, true)
        putString("android.shortCriticalText", order.chipShortText)
        // Samsung One UI 8/8.5 Now Bar & Live Notification compatibility metadata
        putBoolean("com.samsung.android.ongoing_activity", true)
        putInt("com.samsung.android.ongoing_activity.progress", order.progress.coerceIn(0, 100))
        putInt("com.samsung.android.ongoing_activity.max_progress", 100)
        putString("com.samsung.android.ongoing_activity.chip_text", order.chipShortText)
        putInt("com.samsung.android.ongoing_activity.chip_color", stageColorInt)
        putString("com.samsung.android.ongoing_activity.sub_text", order.merchantName ?: order.appName)
      }

    val builder =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        Notification.Builder(context, CHANNEL_ID)
      } else {
        @Suppress("DEPRECATION")
        Notification.Builder(context)
      }

    builder
      .setSmallIcon(R.drawable.ic_notification_delivery)
      .setContentTitle(order.title)
      .setContentText(order.contentText)
      .setSubText(order.merchantName ?: order.appName)
      .setContentIntent(contentPendingIntent)
      .setDeleteIntent(deletePendingIntent)
      .setCategory(Notification.CATEGORY_PROGRESS)
      .setVisibility(Notification.VISIBILITY_PUBLIC)
      .setOnlyAlertOnce(true)
      .setOngoing(order.stage != DeliveryStage.DELIVERED)
      .setColor(stageColorInt)
      .setLargeIcon(
        Icon.createWithResource(
          context,
          if (order.stage == DeliveryStage.DELIVERED) R.drawable.ic_stage_delivered
          else R.drawable.ic_tracker_rider,
        )
      )
      .addExtras(nowBarExtras)

    val etaMillis = order.etaTimestampMillis
    if (order.stage == DeliveryStage.DELIVERING && etaMillis != null && etaMillis > System.currentTimeMillis()) {
      builder.setWhen(etaMillis)
      builder.setShowWhen(true)
    } else {
      builder.setWhen(order.lastUpdatedMillis)
      builder.setShowWhen(true)
    }

    if (openSourcePendingIntent != null) {
      builder.addAction(
        Notification.Action.Builder(
          Icon.createWithResource(context, R.drawable.ic_notification_delivery),
          context.getString(R.string.action_open_source_app),
          openSourcePendingIntent,
        ).build()
      )
    }

    builder.addAction(
      Notification.Action.Builder(
        Icon.createWithResource(context, R.drawable.ic_stage_delivered),
        context.getString(R.string.action_dismiss_live),
        dismissPendingIntent,
      ).build()
    )

    if (Build.VERSION.SDK_INT >= 36) {
      val progressStyle = buildAndroid16ProgressStyle(order, brandColorInt)
      builder
        .setStyle(progressStyle)
        .setRequestPromotedOngoing(true)
        .setShortCriticalText(order.chipShortText.take(7))
    } else {
      // Legacy fallback on API < 36 emulators while keeping native Notification.Builder
      builder.setProgress(100, order.progress.coerceIn(0, 100), false)
    }

    val notification = builder.build()
    notificationManager.notify(order.notificationId, notification)

    val isPromotedFlag =
      if (Build.VERSION.SDK_INT >= 36) {
        (notification.flags and Notification.FLAG_PROMOTED_ONGOING) != 0 ||
          notification.isRequestPromotedOngoing
      } else {
        false
      }

    val canPromote = canPostPromotedNotifications()
    val diagnostic =
      when {
        Build.VERSION.SDK_INT >= 36 && canPromote ->
          "Android 16 ProgressStyle + Now Bar 已激活 (Promoted)"
        Build.VERSION.SDK_INT >= 36 && !canPromote ->
          "已构建 ProgressStyle，请在系统设置开启实时更新权限"
        else ->
          "API ${Build.VERSION.SDK_INT} 兼容模式 (升级至 Android 16 / One UI 8 可激活原生胶囊)"
      }

    return LivePostResult(
      posted = true,
      isPromotedOngoing = isPromotedFlag && canPromote,
      hasPromotableCharacteristics = Build.VERSION.SDK_INT >= 36,
      diagnosticMessage = diagnostic,
    )
  }

  fun cancelNotification(notificationId: Int) {
    pendingIntentCache.remove(notificationId)
    notificationManager.cancel(notificationId)
  }

  private fun buildAndroid16ProgressStyle(
    order: LiveOrderState,
    brandColorInt: Int,
  ): Notification.ProgressStyle {
    if (Build.VERSION.SDK_INT < 36) {
      throw IllegalStateException("ProgressStyle requires API 36+")
    }

    val prepColor = PREPARING_COLOR
    val deliveryColor = brandColorInt
    val arriveColor = DELIVERED_COLOR

    val trackerIcon =
      Icon.createWithResource(
        context,
        if (order.stage == DeliveryStage.DELIVERED) R.drawable.ic_stage_delivered
        else R.drawable.ic_tracker_rider,
      )

    return Notification.ProgressStyle()
      .setStyledByProgress(true)
      .setProgress(order.progress.coerceIn(0, 100))
      .setProgressTrackerIcon(trackerIcon)
      .setProgressSegments(
        listOf(
          Notification.ProgressStyle.Segment(15).setColor(prepColor),
          Notification.ProgressStyle.Segment(75).setColor(deliveryColor),
          Notification.ProgressStyle.Segment(10).setColor(arriveColor),
        )
      )
      .setProgressPoints(
        listOf(
          Notification.ProgressStyle.Point(15).setColor(prepColor),
          Notification.ProgressStyle.Point(90).setColor(arriveColor),
        )
      )
  }

  companion object {
    const val CHANNEL_ID = "live_delivery_updates"
    const val EXTRA_ORDER_KEY = "extra_order_key"

    private val PREPARING_COLOR: Int = AndroidColor.parseColor("#F59E0B")
    private val DELIVERED_COLOR: Int = AndroidColor.parseColor("#10B981")
  }
}
