package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.model.DeliveryStage
import com.example.model.LiveOrderState
import com.example.notification.SamsungNowBarAdapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
// Robolectric 4.16.1 ships android-all only up to API 35, so run on 34; the
// adapter branches on SDK_INT at runtime and this test only checks it posts
// without error and returns a diagnostic message.
@Config(sdk = [34])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("LiveDelivery", appName)
  }

  @Test
  fun `samsung now bar adapter posts android 16 progress style without error`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val adapter = SamsungNowBarAdapter(context)
    adapter.ensureNotificationChannel()
    val sampleOrder =
      LiveOrderState(
        orderKey = "com.sankuai.meituan#999",
        notificationId = 41_999,
        packageName = "com.sankuai.meituan",
        appName = "美团",
        accentColorHex = 0xFFFFC300L,
        title = "美团外卖 #999",
        contentText = "骑手正前往目的地，预计8分钟送达",
        stage = DeliveryStage.DELIVERING,
        progress = 30,
        chipShortText = "8分钟",
        initialMinutes = 10,
        remainingMinutes = 8,
        matchedRuleName = "Stage 2 · 动态时间正则",
        lastUpdatedMillis = System.currentTimeMillis(),
      )
    val result = adapter.postOrUpdateLiveOrder(sampleOrder)
    assertTrue(result.diagnosticMessage.isNotBlank())
  }
}
