package com.example.util

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.example.model.SystemPermissionStatus
import com.example.service.DeliveryNotificationListener

object SystemPermissionHelper {

  fun inspect(context: Context, isServiceBound: Boolean): SystemPermissionStatus {
    val notificationManager = context.getSystemService(NotificationManager::class.java)
    val powerManager = context.getSystemService(PowerManager::class.java)

    val canPostNotif =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
          PackageManager.PERMISSION_GRANTED && notificationManager.areNotificationsEnabled()
      } else {
        notificationManager.areNotificationsEnabled()
      }

    val canPostPromoted =
      if (Build.VERSION.SDK_INT >= 36) {
        runCatching { notificationManager.canPostPromotedNotifications() }.getOrDefault(false)
      } else {
        false
      }

    val ignoringBattery =
      powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: false

    val listenerEnabled = isNotificationListenerEnabled(context)

    return SystemPermissionStatus(
      isNotificationListenerEnabled = listenerEnabled,
      canPostNotifications = canPostNotif,
      canPostPromotedNotifications = canPostPromoted,
      isIgnoringBatteryOptimizations = ignoringBattery,
      isListenerServiceBound = isServiceBound,
      sdkInt = Build.VERSION.SDK_INT,
      manufacturer = Build.MANUFACTURER.orEmpty(),
    )
  }

  fun isNotificationListenerEnabled(context: Context): Boolean {
    val flat =
      Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
        ?: return false
    val expected = ComponentName(context, DeliveryNotificationListener::class.java)
    return flat.split(":").any { entry ->
      ComponentName.unflattenFromString(entry) == expected
    }
  }

  fun openNotificationListenerSettings(context: Context) {
    val intent =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).apply {
          putExtra(
            Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
            ComponentName(context, DeliveryNotificationListener::class.java).flattenToString(),
          )
        }
      } else {
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
      }
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }.onFailure {
      context.startActivity(
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
          addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
      )
    }
  }

  fun openPromotedNotificationSettings(context: Context) {
    val intent =
      if (Build.VERSION.SDK_INT >= 36) {
        Intent(Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS).apply {
          putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        }
      } else {
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
          putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        }
      }
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }.onFailure {
      openAppNotificationSettings(context)
    }
  }

  fun openAppNotificationSettings(context: Context) {
    val intent =
      Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
    context.startActivity(intent)
  }

  @SuppressLint("BatteryLife")
  fun requestIgnoreBatteryOptimizations(context: Context) {
    val intent =
      Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
        data = Uri.parse("package:${context.packageName}")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
    runCatching { context.startActivity(intent) }.onFailure {
      context.startActivity(
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
          addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
      )
    }
  }
}
