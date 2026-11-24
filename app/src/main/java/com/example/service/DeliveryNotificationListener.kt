package com.example.service

import android.app.Notification
import android.content.ComponentName
import android.service.notification.NotificationListenerService
import android.service.notification.RankingMap
import android.service.notification.StatusBarNotification
import android.util.Log
import com.example.LiveDeliveryApp

class DeliveryNotificationListener : NotificationListenerService() {

  override fun onListenerConnected() {
    super.onListenerConnected()
    Log.i(TAG, "NotificationListener connected")
    val app = application as? LiveDeliveryApp ?: return
    app.container.repository.setListenerConnected(true)

    // Inspect active notifications once upon connection to catch in-progress deliveries
    runCatching { activeNotifications }.getOrNull()?.forEach { sbn ->
      dispatchIfMonitored(sbn, app)
    }
  }

  override fun onListenerDisconnected() {
    super.onListenerDisconnected()
    Log.w(TAG, "NotificationListener disconnected — requesting rebind")
    (application as? LiveDeliveryApp)?.container?.repository?.setListenerConnected(false)
    runCatching {
      requestRebind(ComponentName(this, DeliveryNotificationListener::class.java))
    }.onFailure { err ->
      Log.e(TAG, "requestRebind failed", err)
    }
  }

  override fun onNotificationPosted(sbn: StatusBarNotification?) {
    if (sbn == null) return
    val app = application as? LiveDeliveryApp ?: return
    dispatchIfMonitored(sbn, app)
  }

  override fun onNotificationRemoved(
    sbn: StatusBarNotification?,
    rankingMap: RankingMap?,
    reason: Int,
  ) {
    if (sbn == null) return
    val app = application as? LiveDeliveryApp ?: return
    val sourcePackage = sbn.packageName ?: return
    if (sourcePackage == packageName) return
    if (!app.container.repository.isMonitoredPackage(sourcePackage)) return
    // Only an explicit removal takes the live order down: the user swiping the
    // source notification away, the source app cancelling it, or the user
    // clearing all notifications. A tap (REASON_CLICK) opens the app and the
    // delivery continues, so it is ignored.
    if (
      reason != NotificationListenerService.REASON_CANCEL &&
        reason != NotificationListenerService.REASON_CANCEL_ALL &&
        reason != NotificationListenerService.REASON_APP_CANCEL
    ) {
      return
    }
    app.container.repository.dismissOrderBySourceKey(sourcePackage, sbn.key)
  }

  private fun dispatchIfMonitored(sbn: StatusBarNotification, app: LiveDeliveryApp) {
    val sourcePackage = sbn.packageName ?: return
    if (sourcePackage == packageName) return
    if (!app.container.repository.isMonitoredPackage(sourcePackage)) return

    val extras = sbn.notification?.extras ?: return
    val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
    val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
    val bigText =
      extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
        ?: extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
          ?.joinToString(" ") { it?.toString().orEmpty() }
          .orEmpty()

    if (title.isBlank() && text.isBlank() && bigText.isBlank()) return

    app.container.repository.processNotification(
      packageName = sourcePackage,
      title = title,
      text = text,
      bigText = bigText,
      orderHintKey = sbn.key,
      isSimulated = false,
    )
  }

  companion object {
    private const val TAG = "DeliveryNotifListener"
  }
}
