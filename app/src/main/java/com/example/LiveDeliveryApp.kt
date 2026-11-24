package com.example

import android.app.Application
import com.example.data.DeliveryRepository
import com.example.data.SettingsStore
import com.example.engine.RegexProgressEngine
import com.example.notification.SamsungNowBarAdapter

class AppContainer(application: Application) {
  val settingsStore = SettingsStore(application)
  val nowBarAdapter = SamsungNowBarAdapter(application)
  val regexEngine = RegexProgressEngine()
  val repository =
    DeliveryRepository(
      settingsStore = settingsStore,
      nowBarAdapter = nowBarAdapter,
      regexEngine = regexEngine,
    )
}

class LiveDeliveryApp : Application() {
  lateinit var container: AppContainer
    private set

  override fun onCreate() {
    super.onCreate()
    container = AppContainer(this)
    container.nowBarAdapter.ensureNotificationChannel()
  }
}
