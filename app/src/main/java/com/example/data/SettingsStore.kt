package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.model.LiveOrderState
import com.example.model.MonitoredApp
import com.example.model.RegexConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.liveDeliveryDataStore: DataStore<Preferences> by
  preferencesDataStore(name = "live_delivery_settings")

data class UserSettings(
  val monitoredApps: List<MonitoredApp> = MonitoredApp.PRESETS,
  val regexConfig: RegexConfig = RegexConfig(),
  val autoDismissSeconds: Int = DEFAULT_AUTO_DISMISS_SECONDS,
  val autoTickCountdown: Boolean = true,
  val debounceMillis: Long = DEFAULT_DEBOUNCE_MILLIS,
) {
  companion object {
    const val MIN_AUTO_DISMISS_SECONDS = 5
    const val MAX_AUTO_DISMISS_SECONDS = 3600
    const val DEFAULT_AUTO_DISMISS_SECONDS = 300
    const val DEFAULT_DEBOUNCE_MILLIS = 1500L
  }
}

class SettingsStore(private val context: Context) {

  private object Keys {
    val MONITORED_APPS_JSON = stringPreferencesKey("monitored_apps_json")
    val REMOVED_PRESET_PACKAGES = stringPreferencesKey("removed_preset_packages")
    val ACTIVE_ORDERS_JSON = stringPreferencesKey("active_orders_json")
    val STAGE1_REGEX = stringPreferencesKey("stage1_regex")
    val STAGE2_STATIC_REGEX = stringPreferencesKey("stage2_static_regex")
    val STAGE2_ETA_REGEX = stringPreferencesKey("stage2_eta_regex")
    val STAGE2_DIST_REGEX = stringPreferencesKey("stage2_dist_regex")
    val STAGE3_REGEX = stringPreferencesKey("stage3_regex")
    val AUTO_DISMISS_SECONDS = intPreferencesKey("auto_dismiss_seconds")
    val AUTO_TICK_COUNTDOWN = booleanPreferencesKey("auto_tick_countdown")
    val DEBOUNCE_MILLIS = longPreferencesKey("debounce_millis")
  }

  val settingsFlow: Flow<UserSettings> =
    context.liveDeliveryDataStore.data.map { prefs ->
      // Merge in presets added by app updates (e.g. 淘宝闪购), unless the user
      // explicitly removed that preset before.
      val removedPresets =
        prefs[Keys.REMOVED_PRESET_PACKAGES]
          ?.split(",")
          ?.map { it.trim() }
          ?.filter { it.isNotEmpty() }
          ?.toSet()
          .orEmpty()
      val storedApps =
        prefs[Keys.MONITORED_APPS_JSON]?.let(::decodeMonitoredApps)
          ?: MonitoredApp.PRESETS
      val apps =
        storedApps +
          MonitoredApp.PRESETS.filter { preset ->
            preset.packageName !in removedPresets &&
              storedApps.none { it.packageName.equals(preset.packageName, ignoreCase = true) }
          }

      val regexConfig =
        RegexConfig(
          stage1PreparingRegex = prefs[Keys.STAGE1_REGEX] ?: RegexConfig.DEFAULT_STAGE_1_REGEX,
          stage2StaticDeliveringRegex =
            prefs[Keys.STAGE2_STATIC_REGEX] ?: RegexConfig.DEFAULT_STAGE_2_STATIC_REGEX,
          stage2EtaRegex = prefs[Keys.STAGE2_ETA_REGEX] ?: RegexConfig.DEFAULT_STAGE_2_ETA_REGEX,
          stage2DistanceRegex =
            prefs[Keys.STAGE2_DIST_REGEX] ?: RegexConfig.DEFAULT_STAGE_2_DISTANCE_REGEX,
          stage3DeliveredRegex = prefs[Keys.STAGE3_REGEX] ?: RegexConfig.DEFAULT_STAGE_3_REGEX,
        )

      UserSettings(
        monitoredApps = apps,
        regexConfig = regexConfig,
        autoDismissSeconds =
          (prefs[Keys.AUTO_DISMISS_SECONDS] ?: UserSettings.DEFAULT_AUTO_DISMISS_SECONDS)
            .coerceIn(UserSettings.MIN_AUTO_DISMISS_SECONDS, UserSettings.MAX_AUTO_DISMISS_SECONDS),
        autoTickCountdown = prefs[Keys.AUTO_TICK_COUNTDOWN] ?: true,
        debounceMillis = (prefs[Keys.DEBOUNCE_MILLIS] ?: UserSettings.DEFAULT_DEBOUNCE_MILLIS)
          .coerceIn(300L, 10_000L),
      )
    }

  suspend fun saveMonitoredApps(apps: List<MonitoredApp>) {
    context.liveDeliveryDataStore.edit { prefs ->
      prefs[Keys.MONITORED_APPS_JSON] = encodeMonitoredApps(apps)
    }
  }

  /** Remember a preset the user removed, so app updates never re-add it. */
  suspend fun recordRemovedPreset(packageName: String) {
    context.liveDeliveryDataStore.edit { prefs ->
      val current =
        prefs[Keys.REMOVED_PRESET_PACKAGES]
          ?.split(",")
          ?.map { it.trim() }
          ?.filter { it.isNotEmpty() }
          ?.toSet()
          .orEmpty()
      prefs[Keys.REMOVED_PRESET_PACKAGES] = (current + packageName).joinToString(",")
    }
  }

  /** Clear a previous removal record (user re-added the app). */
  suspend fun clearRemovedPreset(packageName: String) {
    context.liveDeliveryDataStore.edit { prefs ->
      val current =
        prefs[Keys.REMOVED_PRESET_PACKAGES]
          ?.split(",")
          ?.map { it.trim() }
          ?.filter { it.isNotEmpty() && !it.equals(packageName, ignoreCase = true) }
          ?.toSet()
          .orEmpty()
      prefs[Keys.REMOVED_PRESET_PACKAGES] = current.joinToString(",")
    }
  }

  /** Persist in-flight live orders for restore after a process restart. */
  suspend fun saveActiveOrders(orders: List<LiveOrderState>) {
    context.liveDeliveryDataStore.edit { prefs ->
      prefs[Keys.ACTIVE_ORDERS_JSON] = ActiveOrderJsonCodec.encode(orders)
    }
  }

  suspend fun loadActiveOrders(): List<LiveOrderState> {
    val raw = context.liveDeliveryDataStore.data.first()[Keys.ACTIVE_ORDERS_JSON] ?: return emptyList()
    return ActiveOrderJsonCodec.decode(raw)
  }

  suspend fun saveRegexConfig(config: RegexConfig) {
    context.liveDeliveryDataStore.edit { prefs ->
      prefs[Keys.STAGE1_REGEX] = config.stage1PreparingRegex
      prefs[Keys.STAGE2_STATIC_REGEX] = config.stage2StaticDeliveringRegex
      prefs[Keys.STAGE2_ETA_REGEX] = config.stage2EtaRegex
      prefs[Keys.STAGE2_DIST_REGEX] = config.stage2DistanceRegex
      prefs[Keys.STAGE3_REGEX] = config.stage3DeliveredRegex
    }
  }

  suspend fun resetRegexDefaults() {
    saveRegexConfig(RegexConfig())
  }

  suspend fun setAutoDismissSeconds(seconds: Int) {
    val clamped =
      seconds.coerceIn(
        UserSettings.MIN_AUTO_DISMISS_SECONDS,
        UserSettings.MAX_AUTO_DISMISS_SECONDS,
      )
    context.liveDeliveryDataStore.edit { prefs ->
      prefs[Keys.AUTO_DISMISS_SECONDS] = clamped
    }
  }

  suspend fun setAutoTickCountdown(enabled: Boolean) {
    context.liveDeliveryDataStore.edit { prefs ->
      prefs[Keys.AUTO_TICK_COUNTDOWN] = enabled
    }
  }

  suspend fun setDebounceMillis(millis: Long) {
    context.liveDeliveryDataStore.edit { prefs ->
      prefs[Keys.DEBOUNCE_MILLIS] = millis.coerceIn(300L, 10_000L)
    }
  }

  private fun encodeMonitoredApps(apps: List<MonitoredApp>): String {
    val arr = JSONArray()
    apps.forEach { app ->
      arr.put(
        JSONObject().apply {
          put("packageName", app.packageName)
          put("displayName", app.displayName)
          put("accentColorHex", app.accentColorHex)
          put("enabled", app.enabled)
          put("isPreset", app.isPreset)
        }
      )
    }
    return arr.toString()
  }

  private fun decodeMonitoredApps(raw: String): List<MonitoredApp>? {
    return runCatching {
      val arr = JSONArray(raw)
      buildList {
        for (i in 0 until arr.length()) {
          val obj = arr.getJSONObject(i)
          add(
            MonitoredApp(
              packageName = obj.getString("packageName"),
              displayName = obj.getString("displayName"),
              accentColorHex = obj.getLong("accentColorHex"),
              enabled = obj.optBoolean("enabled", true),
              isPreset = obj.optBoolean("isPreset", false),
            )
          )
        }
      }
    }.getOrNull()
  }
}
