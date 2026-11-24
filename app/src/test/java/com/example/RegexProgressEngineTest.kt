package com.example

import com.example.engine.ParsedNotificationInput
import com.example.engine.RegexProgressEngine
import com.example.model.DeliveryStage
import com.example.model.RegexConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RegexProgressEngineTest {

  private val engine = RegexProgressEngine()
  private val config = RegexConfig()

  @Test
  fun stage1PreparingSetsZeroProgress() {
    val input =
      ParsedNotificationInput(
        packageName = "com.sankuai.meituan",
        appName = "美团",
        title = "美团外卖 #101",
        text = "商家已接单，商家备餐中",
        bigText = "",
      )
    val state =
      engine.evaluate(
        input = input,
        orderKey = "com.sankuai.meituan#101",
        existingOrder = null,
        config = config,
        accentColorHex = 0xFFFFC300L,
        autoDismissSeconds = 300,
      )

    assertNotNull(state)
    assertEquals(DeliveryStage.PREPARING, state!!.stage)
    assertEquals(0, state.progress)
    assertEquals("已接单", state.chipShortText)
  }

  @Test
  fun stage2StaticDeliveringSets50Percent() {
    val input =
      ParsedNotificationInput(
        packageName = "me.ele",
        appName = "饿了么",
        title = "饿了么订单 #202",
        text = "骑手送餐中",
        bigText = "",
      )
    val state =
      engine.evaluate(
        input = input,
        orderKey = "me.ele#202",
        existingOrder = null,
        config = config,
        accentColorHex = 0xFF0093FFL,
        autoDismissSeconds = 300,
      )

    assertNotNull(state)
    assertEquals(DeliveryStage.DELIVERING, state!!.stage)
    assertEquals(50, state.progress)
    assertEquals("配送中", state.chipShortText)
  }

  @Test
  fun stage2TenMinuteBaselineIncreases10PercentPerMinute() {
    val initialInput =
      ParsedNotificationInput(
        packageName = "com.sankuai.meituan",
        appName = "美团",
        title = "美团外卖 #303",
        text = "骑手正前往目的地，预计10分钟送达",
        bigText = "",
      )
    val first =
      engine.evaluate(
        input = initialInput,
        orderKey = "com.sankuai.meituan#303",
        existingOrder = null,
        config = config,
        accentColorHex = 0xFFFFC300L,
        autoDismissSeconds = 300,
      )!!

    assertEquals(10, first.initialMinutes)
    assertEquals(10, first.progress)
    assertEquals("10分钟", first.chipShortText)

    val nineMin = engine.applyMinuteTick(first)
    assertEquals(9, nineMin.remainingMinutes)
    assertEquals(20, nineMin.progress)
    assertEquals("9分钟", nineMin.chipShortText)

    val eightMin = engine.applyMinuteTick(nineMin)
    assertEquals(8, eightMin.remainingMinutes)
    assertEquals(30, eightMin.progress)

    val fiveMinUpdate =
      engine.evaluate(
        input = initialInput.copy(text = "预计 5 min 送达"),
        orderKey = "com.sankuai.meituan#303",
        existingOrder = eightMin,
        config = config,
        accentColorHex = 0xFFFFC300L,
        autoDismissSeconds = 300,
      )!!
    assertEquals(60, fiveMinUpdate.progress)
    assertEquals("5分钟", fiveMinUpdate.chipShortText)
  }

  @Test
  fun stage3DeliveredSets100PercentAndAutoDismissTimestamp() {
    val now = 1_700_000_000_000L
    val input =
      ParsedNotificationInput(
        packageName = "com.jingdong.app.mall",
        appName = "京东",
        title = "京东秒送 #404",
        text = "您的订单已送达，感谢使用",
        bigText = "",
        timestampMillis = now,
      )
    val state =
      engine.evaluate(
        input = input,
        orderKey = "com.jingdong.app.mall#404",
        existingOrder = null,
        config = config,
        accentColorHex = 0xFFE1251BL,
        autoDismissSeconds = 120,
      )!!

    assertEquals(DeliveryStage.DELIVERED, state.stage)
    assertEquals(100, state.progress)
    assertEquals("已送达", state.chipShortText)
    assertEquals(now + 120_000L, state.autoDismissAtMillis)
  }

  @Test
  fun unrelatedNotificationIsIgnored() {
    val input =
      ParsedNotificationInput(
        packageName = "com.sankuai.meituan",
        appName = "美团",
        title = "周末神券来袭",
        text = "满30减15立即领取",
        bigText = "",
      )
    val state =
      engine.evaluate(
        input = input,
        orderKey = "com.sankuai.meituan:promo",
        existingOrder = null,
        config = config,
        accentColorHex = 0xFFFFC300L,
        autoDismissSeconds = 300,
      )
    assertNull(state)
  }
}
