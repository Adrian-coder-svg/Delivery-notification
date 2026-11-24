package com.example.model

enum class DeliveryStage(val label: String, val badgeText: String) {
  PREPARING("Stage 1 · 备餐/接单", "备餐中"),
  DELIVERING("Stage 2 · 配送中", "配送中"),
  DELIVERED("Stage 3 · 已送达", "已送达"),
}

data class MonitoredApp(
  val packageName: String,
  val displayName: String,
  val accentColorHex: Long,
  val enabled: Boolean = true,
  val isPreset: Boolean = false,
) {
  companion object {
    val PRESETS: List<MonitoredApp> =
      listOf(
        MonitoredApp(
          packageName = "com.sankuai.meituan",
          displayName = "美团",
          accentColorHex = 0xFFFFC300,
          enabled = true,
          isPreset = true,
        ),
        MonitoredApp(
          packageName = "me.ele",
          displayName = "饿了么",
          accentColorHex = 0xFF0093FF,
          enabled = true,
          isPreset = true,
        ),
        MonitoredApp(
          packageName = "com.jingdong.app.mall",
          displayName = "京东",
          accentColorHex = 0xFFE1251B,
          enabled = true,
          isPreset = true,
        ),
      )
  }
}

data class RegexConfig(
  val stage1PreparingRegex: String = DEFAULT_STAGE_1_REGEX,
  val stage2StaticDeliveringRegex: String = DEFAULT_STAGE_2_STATIC_REGEX,
  val stage2EtaRegex: String = DEFAULT_STAGE_2_ETA_REGEX,
  val stage2DistanceRegex: String = DEFAULT_STAGE_2_DISTANCE_REGEX,
  val stage3DeliveredRegex: String = DEFAULT_STAGE_3_REGEX,
) {
  companion object {
    const val DEFAULT_STAGE_1_REGEX =
      ".*(已付款|商家已接单|骑手已接单|商家备餐中|骑手正赶往商家).*"
    const val DEFAULT_STAGE_2_STATIC_REGEX =
      ".*(骑手送餐中|骑手正前往目的地|配送中|正在派送).*"
    const val DEFAULT_STAGE_2_ETA_REGEX =
      "预计\\s*(\\d+)\\s*(min|分钟)"
    const val DEFAULT_STAGE_2_DISTANCE_REGEX =
      "还有\\s*(\\d+(?:\\.\\d+)?)\\s*(km|公里|m|米)"
    const val DEFAULT_STAGE_3_REGEX =
      ".*(已送达|订单已完成|感谢使用).*"
  }
}

data class LiveOrderState(
  val orderKey: String,
  val notificationId: Int,
  val packageName: String,
  val appName: String,
  val accentColorHex: Long,
  val title: String,
  val contentText: String,
  val stage: DeliveryStage,
  val progress: Int,
  val chipShortText: String,
  val initialMinutes: Int? = null,
  val remainingMinutes: Int? = null,
  val initialDistanceMeters: Int? = null,
  val remainingDistanceMeters: Int? = null,
  val etaTimestampMillis: Long? = null,
  val matchedRuleName: String,
  val lastUpdatedMillis: Long,
  val deliveredAtMillis: Long? = null,
  val autoDismissAtMillis: Long? = null,
  val isPromotedOngoing: Boolean = false,
  val promotionDiagnostic: String = "",
  val userDismissed: Boolean = false,
)

data class InterceptLogEntry(
  val id: String,
  val timestampMillis: Long,
  val packageName: String,
  val appName: String,
  val title: String,
  val body: String,
  val outcome: String,
  val stage: DeliveryStage? = null,
  val progress: Int? = null,
  val isSimulated: Boolean = false,
)

data class SystemPermissionStatus(
  val isNotificationListenerEnabled: Boolean = false,
  val canPostNotifications: Boolean = false,
  val canPostPromotedNotifications: Boolean = false,
  val isIgnoringBatteryOptimizations: Boolean = false,
  val isListenerServiceBound: Boolean = false,
  val sdkInt: Int = 0,
  val manufacturer: String = "",
)
