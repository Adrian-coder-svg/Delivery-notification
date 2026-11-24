package com.example.data

import com.example.engine.ParsedNotificationInput
import com.example.engine.RegexProgressEngine
import com.example.model.DeliveryStage
import com.example.model.InterceptLogEntry
import com.example.model.LiveOrderState
import com.example.model.MonitoredApp
import com.example.model.RegexConfig
import com.example.notification.SamsungNowBarAdapter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class DeliveryRepository(
  private val settingsStore: SettingsStore,
  private val nowBarAdapter: SamsungNowBarAdapter,
  private val regexEngine: RegexProgressEngine = RegexProgressEngine(),
) {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  val settings: StateFlow<UserSettings> =
    settingsStore.settingsFlow.stateIn(
      scope = scope,
      started = SharingStarted.Eagerly,
      initialValue = UserSettings(),
    )

  private val ordersMap = ConcurrentHashMap<String, LiveOrderState>()
  private val lastContentFingerprint = ConcurrentHashMap<String, Pair<String, Long>>()
  private val countdownJobs = ConcurrentHashMap<String, Job>()
  private val autoDismissJobs = ConcurrentHashMap<String, Job>()

  private val _activeOrders = MutableStateFlow<List<LiveOrderState>>(emptyList())
  val activeOrders: StateFlow<List<LiveOrderState>> = _activeOrders.asStateFlow()

  private val _interceptLogs = MutableStateFlow<List<InterceptLogEntry>>(emptyList())
  val interceptLogs: StateFlow<List<InterceptLogEntry>> = _interceptLogs.asStateFlow()

  private val _isListenerConnected = MutableStateFlow(false)
  val isListenerConnected: StateFlow<Boolean> = _isListenerConnected.asStateFlow()

  // True once the persisted orders have been restored; publishing before that
  // would overwrite the saved snapshot with an incomplete order set.
  private val restoreCompleted = AtomicBoolean(false)

  init {
    scope.launch { restoreActiveOrders() }
  }

  /**
   * Restore in-flight orders saved before a process restart: re-post their live
   * notifications and restart their countdown / auto-dismiss jobs. Orders that
   * arrived while restoring win (putIfAbsent), so a duplicate can never regress
   * a newer state.
   */
  private suspend fun restoreActiveOrders() {
    val saved = runCatching { settingsStore.loadActiveOrders() }.getOrDefault(emptyList())
    if (saved.isEmpty()) {
      restoreCompleted.set(true)
      return
    }
    val now = System.currentTimeMillis()
    val currentSettings =
      runCatching { settingsStore.settingsFlow.first() }.getOrDefault(settings.value)
    saved.forEach { order ->
      if (order.userDismissed) return@forEach
      if (order.stage == DeliveryStage.DELIVERED) {
        val remainingDismiss = (order.autoDismissAtMillis ?: 0L) - now
        if (remainingDismiss <= 0L) {
          nowBarAdapter.cancelNotification(order.notificationId)
          return@forEach
        }
      }
      // Recompute the remaining time from the ETA deadline: the stored value is
      // stale by however long the process was dead.
      val refreshed =
        if (order.stage == DeliveryStage.DELIVERING && order.etaTimestampMillis != null) {
          val remaining =
            ((order.etaTimestampMillis - now) / 60_000L).toInt().coerceAtLeast(1)
          val initial = order.initialMinutes ?: remaining
          order.copy(
            remainingMinutes = remaining,
            progress = regexEngine.calculateTimeProgress(initial, remaining),
            lastUpdatedMillis = now,
          )
        } else {
          order
        }
      if (ordersMap.putIfAbsent(refreshed.orderKey, refreshed) != null) return@forEach
      val postResult = nowBarAdapter.postOrUpdateLiveOrder(refreshed)
      val enriched =
        refreshed.copy(
          isPromotedOngoing = postResult.isPromotedOngoing,
          promotionDiagnostic = postResult.diagnosticMessage,
        )
      ordersMap[refreshed.orderKey] = enriched
      val dismissDelay =
        if (enriched.stage == DeliveryStage.DELIVERED) {
          (enriched.autoDismissAtMillis ?: 0L) - now
        } else {
          null
        }
      manageStageCoroutines(enriched, currentSettings, dismissDelayMillis = dismissDelay)
    }
    publishOrders()
    restoreCompleted.set(true)
  }

  fun setListenerConnected(connected: Boolean) {
    _isListenerConnected.value = connected
  }

  fun isMonitoredPackage(packageName: String): Boolean {
    return settings.value.monitoredApps.any {
      it.packageName.equals(packageName, ignoreCase = true) && it.enabled
    }
  }

  fun processNotification(
    packageName: String,
    title: String,
    text: String,
    bigText: String,
    orderHintKey: String? = null,
    isSimulated: Boolean = false,
  ) {
    val currentSettings = settings.value
    val monitoredApp =
      currentSettings.monitoredApps.firstOrNull {
        it.packageName.equals(packageName, ignoreCase = true) && it.enabled
      } ?: return

    val input =
      ParsedNotificationInput(
        packageName = monitoredApp.packageName,
        appName = monitoredApp.displayName,
        title = title,
        text = text,
        bigText = bigText,
        orderHintKey = orderHintKey,
      )

    val combinedText = input.combinedText
    if (combinedText.isBlank()) return

    val orderKey =
      regexEngine.resolveOrderKey(input, ordersMap, currentSettings.regexConfig)

    // Debounce duplicate notifications to avoid Live Notification flicker
    val lastSeen = lastContentFingerprint[orderKey]
    val now = input.timestampMillis
    if (
      lastSeen != null &&
        lastSeen.first == combinedText &&
        (now - lastSeen.second) < currentSettings.debounceMillis
    ) {
      appendLog(
        InterceptLogEntry(
          id = UUID.randomUUID().toString(),
          timestampMillis = now,
          packageName = monitoredApp.packageName,
          appName = monitoredApp.displayName,
          title = title,
          body = bigText.ifBlank { text },
          outcome = "防抖跳过 (重复通知)",
          isSimulated = isSimulated,
        )
      )
      return
    }
    lastContentFingerprint[orderKey] = combinedText to now

    val existingOrder = ordersMap[orderKey]
    if (existingOrder?.userDismissed == true && existingOrder.contentText == (bigText.ifBlank { text })) {
      return
    }

    val nextState =
      regexEngine.evaluate(
        input = input,
        orderKey = orderKey,
        existingOrder = existingOrder,
        config = currentSettings.regexConfig,
        accentColorHex = monitoredApp.accentColorHex,
        autoDismissSeconds = currentSettings.autoDismissSeconds,
      )

    if (nextState == null) {
      appendLog(
        InterceptLogEntry(
          id = UUID.randomUUID().toString(),
          timestampMillis = now,
          packageName = monitoredApp.packageName,
          appName = monitoredApp.displayName,
          title = title,
          body = bigText.ifBlank { text },
          outcome = "未命中配送正则 (已忽略)",
          isSimulated = isSimulated,
        )
      )
      return
    }

    // Skip redundant notification update if stage, progress, and chip text haven't changed
    if (
      existingOrder != null &&
        !existingOrder.userDismissed &&
        existingOrder.stage == nextState.stage &&
        existingOrder.progress == nextState.progress &&
        existingOrder.chipShortText == nextState.chipShortText &&
        existingOrder.contentText == nextState.contentText
    ) {
      appendLog(
        InterceptLogEntry(
          id = UUID.randomUUID().toString(),
          timestampMillis = now,
          packageName = monitoredApp.packageName,
          appName = monitoredApp.displayName,
          title = title,
          body = bigText.ifBlank { text },
          outcome = "状态未变化 (防闪烁跳过)",
          stage = nextState.stage,
          progress = nextState.progress,
          isSimulated = isSimulated,
        )
      )
      return
    }

    val postResult = nowBarAdapter.postOrUpdateLiveOrder(nextState)
    val enriched =
      nextState.copy(
        isPromotedOngoing = postResult.isPromotedOngoing,
        promotionDiagnostic = postResult.diagnosticMessage,
      )
    ordersMap[orderKey] = enriched
    publishOrders()

    manageStageCoroutines(enriched, currentSettings)

    appendLog(
      InterceptLogEntry(
        id = UUID.randomUUID().toString(),
        timestampMillis = now,
        packageName = monitoredApp.packageName,
        appName = monitoredApp.displayName,
        title = title,
        body = bigText.ifBlank { text },
        outcome = "${nextState.matchedRuleName} · ${nextState.progress}% [${nextState.chipShortText}]",
        stage = nextState.stage,
        progress = nextState.progress,
        isSimulated = isSimulated,
      )
    )
  }

  private fun manageStageCoroutines(
    order: LiveOrderState,
    currentSettings: UserSettings,
    dismissDelayMillis: Long? = null,
  ) {
    // Cancel any existing Stage 2 ticker or Stage 3 auto-dismiss job for this order
    countdownJobs.remove(order.orderKey)?.cancel()
    autoDismissJobs.remove(order.orderKey)?.cancel()

    when (order.stage) {
      DeliveryStage.PREPARING -> Unit
      DeliveryStage.DELIVERING -> {
        if (currentSettings.autoTickCountdown && (order.remainingMinutes ?: 0) > 1) {
          val job =
            scope.launch {
              while (isActive) {
                delay(60_000L)
                val latest = ordersMap[order.orderKey] ?: break
                if (latest.userDismissed || latest.stage != DeliveryStage.DELIVERING) break
                if ((latest.remainingMinutes ?: 0) <= 1) break

                val ticked = regexEngine.applyMinuteTick(latest)
                val postResult = nowBarAdapter.postOrUpdateLiveOrder(ticked)
                val updated =
                  ticked.copy(
                    isPromotedOngoing = postResult.isPromotedOngoing,
                    promotionDiagnostic = postResult.diagnosticMessage,
                  )
                ordersMap[order.orderKey] = updated
                publishOrders()
              }
            }
          countdownJobs[order.orderKey] = job
        }
      }
      DeliveryStage.DELIVERED -> {
        val delaySeconds = currentSettings.autoDismissSeconds
        // Restored orders only wait out the remaining time, not a full delay.
        val dismissDelay = dismissDelayMillis ?: delaySeconds * 1000L
        val job =
          scope.launch {
            delay(dismissDelay)
            nowBarAdapter.cancelNotification(order.notificationId)
            ordersMap[order.orderKey]?.let { current ->
              ordersMap[order.orderKey] = current.copy(userDismissed = true)
              publishOrders()
            }
            appendLog(
              InterceptLogEntry(
                id = UUID.randomUUID().toString(),
                timestampMillis = System.currentTimeMillis(),
                packageName = order.packageName,
                appName = order.appName,
                title = order.title,
                body = "送达后持续 ${dismissDelay / 1000} 秒，已自动销毁实时通知",
                outcome = "Stage 3 · 自动销毁完成",
                stage = DeliveryStage.DELIVERED,
                progress = 100,
              )
            )
          }
        autoDismissJobs[order.orderKey] = job
      }
    }
  }

  fun dismissOrder(orderKey: String, notificationId: Int? = null) {
    countdownJobs.remove(orderKey)?.cancel()
    autoDismissJobs.remove(orderKey)?.cancel()
    val existing = ordersMap[orderKey]
    val targetNotifId = notificationId ?: existing?.notificationId
    if (targetNotifId != null && targetNotifId > 0) {
      nowBarAdapter.cancelNotification(targetNotifId)
    }
    if (existing != null) {
      ordersMap[orderKey] = existing.copy(userDismissed = true)
      publishOrders()
    }
  }

  /**
   * The source app removed its notification (user swiped it away or the app
   * cancelled it): take the live notification down with it, so it never lingers
   * after the order is gone. Matches on the last seen source notification key.
   */
  fun dismissOrderBySourceKey(packageName: String, sourceNotificationKey: String) {
    ordersMap.values
      .filter {
        it.packageName.equals(packageName, ignoreCase = true) &&
          !it.userDismissed &&
          it.sourceNotificationKey == sourceNotificationKey
      }
      .forEach { dismissOrder(it.orderKey, it.notificationId) }
  }

  fun markUserDismissed(orderKey: String) {
    countdownJobs.remove(orderKey)?.cancel()
    autoDismissJobs.remove(orderKey)?.cancel()
    ordersMap[orderKey]?.let { existing ->
      ordersMap[orderKey] = existing.copy(userDismissed = true)
      publishOrders()
    }
  }

  fun removeCompletedOrder(orderKey: String) {
    countdownJobs.remove(orderKey)?.cancel()
    autoDismissJobs.remove(orderKey)?.cancel()
    ordersMap.remove(orderKey)?.let { nowBarAdapter.cancelNotification(it.notificationId) }
    publishOrders()
  }

  fun clearLogs() {
    _interceptLogs.value = emptyList()
  }

  suspend fun toggleMonitoredApp(packageName: String, enabled: Boolean) {
    val updated =
      settings.value.monitoredApps.map { app ->
        if (app.packageName == packageName) app.copy(enabled = enabled) else app
      }
    settingsStore.saveMonitoredApps(updated)
  }

  suspend fun addMonitoredApp(app: MonitoredApp) {
    val current = settings.value.monitoredApps
    if (current.any { it.packageName.equals(app.packageName, ignoreCase = true) }) return
    settingsStore.clearRemovedPreset(app.packageName)
    settingsStore.saveMonitoredApps(current + app)
  }

  suspend fun removeMonitoredApp(packageName: String) {
    val updated = settings.value.monitoredApps.filterNot { it.packageName == packageName }
    settingsStore.recordRemovedPreset(packageName)
    settingsStore.saveMonitoredApps(updated)
  }

  suspend fun saveRegexConfig(config: RegexConfig) {
    settingsStore.saveRegexConfig(config)
  }

  suspend fun resetRegexDefaults() {
    settingsStore.resetRegexDefaults()
  }

  suspend fun setAutoDismissSeconds(seconds: Int) {
    settingsStore.setAutoDismissSeconds(seconds)
  }

  suspend fun setAutoTickCountdown(enabled: Boolean) {
    settingsStore.setAutoTickCountdown(enabled)
  }

  suspend fun setDebounceMillis(millis: Long) {
    settingsStore.setDebounceMillis(millis)
  }

  private fun publishOrders() {
    _activeOrders.value =
      ordersMap.values
        .filterNot { it.userDismissed }
        .sortedByDescending { it.lastUpdatedMillis }
    // Persist the in-flight set so a killed process can restore it. Skipped
    // until the restore has finished, to never overwrite the snapshot with a
    // partial order set.
    if (restoreCompleted.get()) {
      val snapshot = ordersMap.values.filterNot { it.userDismissed }
      scope.launch { runCatching { settingsStore.saveActiveOrders(snapshot) } }
    }
  }

  private fun appendLog(entry: InterceptLogEntry) {
    _interceptLogs.update { current -> (listOf(entry) + current).take(60) }
  }
}
