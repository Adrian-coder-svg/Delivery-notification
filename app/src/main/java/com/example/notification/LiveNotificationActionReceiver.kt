package com.example.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.LiveDeliveryApp

class LiveNotificationActionReceiver : BroadcastReceiver() {

  override fun onReceive(context: Context, intent: Intent) {
    val orderKey = intent.getStringExtra(EXTRA_ORDER_KEY) ?: return
    val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1)
    val app = context.applicationContext as? LiveDeliveryApp ?: return

    when (intent.action) {
      ACTION_DISMISS_LIVE_ORDER -> {
        app.container.repository.dismissOrder(orderKey, notificationId)
      }
      ACTION_NOTIFICATION_DELETED -> {
        app.container.repository.markUserDismissed(orderKey)
      }
    }
  }

  companion object {
    const val ACTION_DISMISS_LIVE_ORDER = "com.example.action.DISMISS_LIVE_ORDER"
    const val ACTION_NOTIFICATION_DELETED = "com.example.action.NOTIFICATION_DELETED"
    const val EXTRA_ORDER_KEY = "extra_order_key"
    const val EXTRA_NOTIFICATION_ID = "extra_notification_id"
  }
}
