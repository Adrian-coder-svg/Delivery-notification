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

  @Test
  fun newOrderAfterDeliveredDoesNotInheritStaleBaseline() {
    val delivered =
      engine.evaluate(
        input =
          ParsedNotificationInput(
            packageName = "com.sankuai.meituan",
            appName = "美团",
            title = "美团外卖",
            text = "您的订单已送达，感谢使用",
            bigText = "",
          ),
        orderKey = "com.sankuai.meituan:default",
        existingOrder = null,
        config = config,
        accentColorHex = 0xFFFFC300L,
        autoDismissSeconds = 300,
      )!!.copy(initialMinutes = 20)

    val freshOrder =
      engine.evaluate(
        input =
          ParsedNotificationInput(
            packageName = "com.sankuai.meituan",
            appName = "美团",
            title = "美团外卖",
            text = "骑手正前往目的地，预计10分钟送达",
            bigText = "",
          ),
        orderKey = "com.sankuai.meituan:default",
        existingOrder = delivered,
        config = config,
        accentColorHex = 0xFFFFC300L,
        autoDismissSeconds = 300,
      )!!

    assertEquals(DeliveryStage.DELIVERING, freshOrder.stage)
    assertEquals(10, freshOrder.initialMinutes)
    assertEquals(10, freshOrder.progress)

    val orderProgress = freshOrder.toOrderProgress(rawText = "骑手正前往目的地，预计10分钟送达")
    assertEquals("美团外卖", orderProgress.title)
    assertEquals("骑手正前往目的地，预计10分钟送达", orderProgress.text)
    assertEquals(DeliveryStage.DELIVERING, orderProgress.stage)
    assertEquals(10, orderProgress.progress)
    assertEquals("10分钟", orderProgress.chipShortText)
  }

  @Test
  fun sameOrderWithoutIdStaysInOneLiveNotification() {
    val firstInput =
      ParsedNotificationInput(
        packageName = "com.sankuai.meituan",
        appName = "美团",
        title = "麦当劳（中关村店）",
        text = "商家已接单，商家备餐中",
        bigText = "",
        orderHintKey = "0|com.sankuai.meituan|1001|null|1001",
        timestampMillis = 1_700_000_000_000L,
      )
    val key1 = engine.resolveOrderKey(firstInput, emptyMap(), config)
    assertEquals("com.sankuai.meituan:m:麦当劳", key1)

    val firstState =
      engine.evaluate(
        input = firstInput,
        orderKey = key1,
        existingOrder = null,
        config = config,
        accentColorHex = 0xFFFFC300L,
        autoDismissSeconds = 300,
      )!!
    assertEquals(DeliveryStage.PREPARING, firstState.stage)
    assertEquals("麦当劳（中关村店）", firstState.merchantName)

    // Same order, new source notification (different sbn key), progress update.
    val secondInput =
      firstInput.copy(
        text = "骑手正前往目的地，预计10分钟送达",
        orderHintKey = "0|com.sankuai.meituan|2002|null|2002",
        timestampMillis = 1_700_000_060_000L,
      )
    val key2 = engine.resolveOrderKey(secondInput, mapOf(key1 to firstState), config)
    assertEquals(key1, key2)

    val secondState =
      engine.evaluate(
        input = secondInput,
        orderKey = key2,
        existingOrder = firstState,
        config = config,
        accentColorHex = 0xFFFFC300L,
        autoDismissSeconds = 300,
      )!!
    assertEquals(firstState.notificationId, secondState.notificationId)
    assertEquals(DeliveryStage.DELIVERING, secondState.stage)
  }

  @Test
  fun twoOrdersFromDifferentMerchantsStaySeparate() {
    val firstInput =
      ParsedNotificationInput(
        packageName = "com.sankuai.meituan",
        appName = "美团",
        title = "麦当劳（中关村店）",
        text = "商家已接单，商家备餐中",
        bigText = "",
        timestampMillis = 1_700_000_000_000L,
      )
    val keyA = engine.resolveOrderKey(firstInput, emptyMap(), config)
    val firstState =
      engine.evaluate(
        input = firstInput,
        orderKey = keyA,
        existingOrder = null,
        config = config,
        accentColorHex = 0xFFFFC300L,
        autoDismissSeconds = 300,
      )!!

    val secondInput =
      ParsedNotificationInput(
        packageName = "com.sankuai.meituan",
        appName = "美团",
        title = "肯德基（西单店）",
        text = "商家已接单，商家备餐中",
        bigText = "",
        timestampMillis = 1_700_000_030_000L,
      )
    val keyB = engine.resolveOrderKey(secondInput, mapOf(keyA to firstState), config)
    assertEquals("com.sankuai.meituan:m:肯德基", keyB)
    assertEquals(false, keyA == keyB)
  }

  @Test
  fun secondOrderFromSameMerchantWhileFirstDeliveringGetsNewKey() {
    val firstInput =
      ParsedNotificationInput(
        packageName = "com.sankuai.meituan",
        appName = "美团",
        title = "麦当劳（中关村店）",
        text = "商家已接单，商家备餐中",
        bigText = "",
        timestampMillis = 1_700_000_000_000L,
      )
    val key1 = engine.resolveOrderKey(firstInput, emptyMap(), config)
    val preparing =
      engine.evaluate(
        input = firstInput,
        orderKey = key1,
        existingOrder = null,
        config = config,
        accentColorHex = 0xFFFFC300L,
        autoDismissSeconds = 300,
      )!!

    val deliveringInput =
      firstInput.copy(
        text = "骑手正前往目的地，预计10分钟送达",
        timestampMillis = 1_700_000_060_000L,
      )
    val delivering =
      engine.evaluate(
        input = deliveringInput,
        orderKey = key1,
        existingOrder = preparing,
        config = config,
        accentColorHex = 0xFFFFC300L,
        autoDismissSeconds = 300,
      )!!
    assertEquals(DeliveryStage.DELIVERING, delivering.stage)

    // A new order from the same merchant starts while the first is still delivering.
    val newOrderInput =
      firstInput.copy(
        text = "商家已接单，商家备餐中",
        timestampMillis = 1_700_000_120_000L,
      )
    val key2 = engine.resolveOrderKey(newOrderInput, mapOf(key1 to delivering), config)
    assertEquals("$key1#2", key2)
  }

  @Test
  fun fullOrderKeyHintIsUsedAsIs() {
    val input =
      ParsedNotificationInput(
        packageName = "com.sankuai.meituan",
        appName = "美团",
        title = "麦当劳（中关村店）",
        text = "骑手已取餐正赶往您的位置，预计10分钟送达",
        bigText = "",
        orderHintKey = "com.sankuai.meituan:m:麦当劳（中关村店）",
      )
    assertEquals(
      "com.sankuai.meituan:m:麦当劳（中关村店）",
      engine.resolveOrderKey(input, emptyMap(), config),
    )
  }

  @Test
  fun merchantExtractionFromGenericTitleAndBody() {
    val merchantInput =
      ParsedNotificationInput(
        packageName = "com.sankuai.meituan",
        appName = "美团",
        title = "美团外卖",
        text = "麦当劳已接单，正在备餐",
        bigText = "",
      )
    assertEquals("麦当劳", engine.extractMerchantName(merchantInput))

    val bracketInput =
      ParsedNotificationInput(
        packageName = "me.ele",
        appName = "饿了么",
        title = "饿了么",
        text = "【老乡鸡】您的订单商家已接单",
        bigText = "",
      )
    assertEquals("老乡鸡", engine.extractMerchantName(bracketInput))

    val promoInput =
      ParsedNotificationInput(
        packageName = "com.sankuai.meituan",
        appName = "美团",
        title = "周末神券来袭",
        text = "满30减15立即领取",
        bigText = "",
      )
    assertEquals(null, engine.extractMerchantName(promoInput))
  }

  @Test
  fun genericTitleFallsBackToMerchantInEvaluate() {
    val input =
      ParsedNotificationInput(
        packageName = "com.sankuai.meituan",
        appName = "美团",
        title = "美团外卖",
        text = "商家已接单，麦当劳正在备餐",
        bigText = "",
      )
    val state =
      engine.evaluate(
        input = input,
        orderKey = "com.sankuai.meituan:m:麦当劳",
        existingOrder = null,
        config = config,
        accentColorHex = 0xFFFFC300L,
        autoDismissSeconds = 300,
      )!!
    assertEquals("麦当劳", state.title)
    assertEquals("麦当劳", state.merchantName)
    assertEquals(DeliveryStage.PREPARING, state.stage)
  }

  @Test
  fun updateMentioningKnownMerchantAttachesToThatOrder() {
    val firstInput =
      ParsedNotificationInput(
        packageName = "com.sankuai.meituan",
        appName = "美团",
        title = "麦当劳（中关村店）",
        text = "商家已接单，商家备餐中",
        bigText = "",
        timestampMillis = 1_700_000_000_000L,
      )
    val key1 = engine.resolveOrderKey(firstInput, emptyMap(), config)
    val firstState =
      engine.evaluate(
        input = firstInput,
        orderKey = key1,
        existingOrder = null,
        config = config,
        accentColorHex = 0xFFFFC300L,
        autoDismissSeconds = 300,
      )!!

    // Generic title, body only mentions the merchant in passing.
    val updateInput =
      ParsedNotificationInput(
        packageName = "com.sankuai.meituan",
        appName = "美团",
        title = "美团外卖",
        text = "骑手已取餐，麦当劳的订单配送中",
        bigText = "",
        timestampMillis = 1_700_000_060_000L,
      )
    val key2 = engine.resolveOrderKey(updateInput, mapOf(key1 to firstState), config)
    assertEquals(key1, key2)
  }
}
