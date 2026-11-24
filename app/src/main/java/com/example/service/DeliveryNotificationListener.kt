package com.example.service

import android.app.Notification
import android.content.ComponentName
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.example.LiveDeliveryApp

class DeliveryNotificationListener : NotificationListenerService() {

  override fun onListenerConnected() {
    super.onListenerConnected()
    Log.i(TAG, "NotificationListener connected")
    (application as? LiveDeliveryApp)?.container?.repository?.setListenerConnected(true)
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
    val sourcePackage = sbn.packageName ?: return
    if (sourcePackage == packageName) return

    val notification = sbn.notification ?: return
    val extras = notification.extras ?: return

    val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
    val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
    val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()

    if (title.isBlank() && text.isBlank() && bigText.isBlank()) return

    val app = application as? LiveDeliveryApp ?: return
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
