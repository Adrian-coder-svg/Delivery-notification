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
 */
class SamsungNowBarAdapter(private val context: Context) {

  private val notificationManager: NotificationManager =
    context.getSystemService(NotificationManager::class.java)

  fun ensureNotificationChannel() {
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
    return if (Build.VERSION.SDK_INT >= 36) {
      runCatching { notificationManager.canPostPromotedNotifications() }.getOrDefault(false)
    } else {
      false
    }
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

    val openAppIntent =
      Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        putExtra(EXTRA_ORDER_KEY, order.orderKey)
      }
    val contentPendingIntent =
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
    val dismissPendingIntent =
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
    val deletePendingIntent =
      PendingIntent.getBroadcast(
        context,
        order.notificationId + 200_000,
        deleteIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )

    val launchSourceIntent =
      context.packageManager.getLaunchIntentForPackage(order.packageName)
    val openSourcePendingIntent =
      launchSourceIntent?.let {
        PendingIntent.getActivity(
          context,
          order.notificationId + 300_000,
          it,
          PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
      }

    val brandColorInt = order.accentColorHex.toInt()
    val stageColorInt =
      when (order.stage) {
        DeliveryStage.PREPARING -> AndroidColor.parseColor("#F59E0B")
        DeliveryStage.DELIVERING -> brandColorInt
        DeliveryStage.DELIVERED -> AndroidColor.parseColor("#10B981")
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
    notificationManager.cancel(notificationId)
  }

  private fun buildAndroid16ProgressStyle(
    order: LiveOrderState,
    brandColorInt: Int,
  ): Notification.ProgressStyle {
    if (Build.VERSION.SDK_INT < 36) {
      throw IllegalStateException("ProgressStyle requires API 36+")
    }

    val prepColor = AndroidColor.parseColor("#F59E0B")
    val deliveryColor = brandColorInt
    val arriveColor = AndroidColor.parseColor("#10B981")

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
  }
}
