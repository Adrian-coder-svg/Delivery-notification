package com.example.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.LiveDeliveryApp
import com.example.data.UserSettings
import com.example.engine.RegexMatchPreview
import com.example.model.InterceptLogEntry
import com.example.model.LiveOrderState
import com.example.model.MonitoredApp
import com.example.model.RegexConfig
import com.example.model.SystemPermissionStatus
import com.example.util.SystemPermissionHelper
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

  private val container = (application as LiveDeliveryApp).container
  private val repository = container.repository
  private val regexEngine = container.regexEngine

  val settings: StateFlow<UserSettings> = repository.settings

  val activeOrders: StateFlow<List<LiveOrderState>> = repository.activeOrders

  val interceptLogs: StateFlow<List<InterceptLogEntry>> = repository.interceptLogs

  val isListenerConnected: StateFlow<Boolean> = repository.isListenerConnected

  private val _permissionStatus =
    MutableStateFlow(
      SystemPermissionHelper.inspect(
        application,
        repository.isListenerConnected.value,
      )
    )
  val permissionStatus: StateFlow<SystemPermissionStatus> = _permissionStatus.asStateFlow()

  val tickerMillis: StateFlow<Long> =
    flow {
      while (true) {
        emit(System.currentTimeMillis())
        delay(1000L)
      }
    }.stateIn(
      scope = viewModelScope,
      started = SharingStarted.WhileSubscribed(5_000L),
      initialValue = System.currentTimeMillis(),
    )

  init {
    viewModelScope.launch {
      isListenerConnected.collect { connected ->
        _permissionStatus.value =
          SystemPermissionHelper.inspect(getApplication(), connected)
      }
    }
  }

  fun refreshPermissions(context: Context = getApplication()) {
    _permissionStatus.value =
      SystemPermissionHelper.inspect(context, repository.isListenerConnected.value)
  }

  fun simulateNotification(
    packageName: String,
    title: String,
    body: String,
    orderHintKey: String = "sim_order_1",
  ) {
    repository.processNotification(
      packageName = packageName,
      title = title,
      text = body,
      bigText = body,
      orderHintKey = orderHintKey,
      isSimulated = true,
    )
  }

  fun dismissOrder(orderKey: String, notificationId: Int) {
    repository.dismissOrder(orderKey, notificationId)
  }

  fun clearLogs() {
    repository.clearLogs()
  }

  fun toggleMonitoredApp(packageName: String, enabled: Boolean) {
    viewModelScope.launch {
      repository.toggleMonitoredApp(packageName, enabled)
    }
  }

  fun addMonitoredApp(packageName: String, displayName: String, accentColorHex: Long) {
    val trimmedPkg = packageName.trim()
    val trimmedName = displayName.trim().ifBlank { trimmedPkg.substringAfterLast('.') }
    if (trimmedPkg.isBlank()) return
    viewModelScope.launch {
      repository.addMonitoredApp(
        MonitoredApp(
          packageName = trimmedPkg,
          displayName = trimmedName,
          accentColorHex = accentColorHex,
          enabled = true,
          isPreset = false,
        )
      )
    }
  }

  fun removeMonitoredApp(packageName: String) {
    viewModelScope.launch {
      repository.removeMonitoredApp(packageName)
    }
  }

  fun restorePresetApps() {
    viewModelScope.launch {
      val current = settings.value.monitoredApps
      val merged =
        (MonitoredApp.PRESETS + current).distinctBy { it.packageName.lowercase() }
      container.settingsStore.saveMonitoredApps(merged)
    }
  }

  fun saveRegexConfig(config: RegexConfig) {
    viewModelScope.launch {
      repository.saveRegexConfig(config)
    }
  }

  fun resetRegexDefaults() {
    viewModelScope.launch {
      repository.resetRegexDefaults()
    }
  }

  fun setAutoDismissSeconds(seconds: Int) {
    viewModelScope.launch {
      repository.setAutoDismissSeconds(seconds)
    }
  }

  fun setAutoTickCountdown(enabled: Boolean) {
    viewModelScope.launch {
      repository.setAutoTickCountdown(enabled)
    }
  }

  fun setDebounceMillis(millis: Long) {
    viewModelScope.launch {
      repository.setDebounceMillis(millis)
    }
  }

  fun previewRegex(rawText: String, simulatedInitialMinutes: Int? = null): RegexMatchPreview {
    return regexEngine.preview(
      rawText = rawText,
      config = settings.value.regexConfig,
      simulatedInitialMinutes = simulatedInitialMinutes,
    )
  }
}
