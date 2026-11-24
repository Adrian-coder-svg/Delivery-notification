package com.example.data

import com.example.model.DeliveryStage
import com.example.model.LiveOrderState
import org.json.JSONArray
import org.json.JSONObject

/**
 * JSON codec for persisting in-flight live orders, so a killed process can
 * restore them (and re-post the live notification) instead of dropping the
 * delivery. Entries that fail to decode are skipped, never failing the whole
 * restore.
 */
object ActiveOrderJsonCodec {

  fun encode(orders: List<LiveOrderState>): String {
    val arr = JSONArray()
    orders.forEach { order ->
      arr.put(
        JSONObject().apply {
          put("orderKey", order.orderKey)
          put("notificationId", order.notificationId)
          put("packageName", order.packageName)
          put("appName", order.appName)
          put("accentColorHex", order.accentColorHex)
          putOpt("merchantName", order.merchantName)
          putOpt("pickupAtMillis", order.pickupAtMillis)
          putOpt("sourceNotificationKey", order.sourceNotificationKey)
          put("title", order.title)
          put("contentText", order.contentText)
          put("stage", order.stage.name)
          put("progress", order.progress)
          put("chipShortText", order.chipShortText)
          putOpt("initialMinutes", order.initialMinutes)
          putOpt("remainingMinutes", order.remainingMinutes)
          putOpt("initialDistanceMeters", order.initialDistanceMeters)
          putOpt("remainingDistanceMeters", order.remainingDistanceMeters)
          putOpt("etaTimestampMillis", order.etaTimestampMillis)
          put("matchedRuleName", order.matchedRuleName)
          put("lastUpdatedMillis", order.lastUpdatedMillis)
          putOpt("deliveredAtMillis", order.deliveredAtMillis)
          putOpt("autoDismissAtMillis", order.autoDismissAtMillis)
          put("isPromotedOngoing", order.isPromotedOngoing)
          putOpt("promotionDiagnostic", order.promotionDiagnostic)
          put("userDismissed", order.userDismissed)
        },
      )
    }
    return arr.toString()
  }

  fun decode(raw: String): List<LiveOrderState> {
    return runCatching {
      val arr = JSONArray(raw)
      buildList {
        for (i in 0 until arr.length()) {
          decodeOne(arr.optJSONObject(i) ?: continue)?.let(::add)
        }
      }
    }.getOrDefault(emptyList())
  }

  private fun decodeOne(obj: JSONObject): LiveOrderState? {
    return runCatching {
      val stage =
        runCatching { DeliveryStage.valueOf(obj.getString("stage")) }
          .getOrDefault(DeliveryStage.DELIVERING)
      LiveOrderState(
        orderKey = obj.getString("orderKey"),
        notificationId = obj.getInt("notificationId"),
        packageName = obj.getString("packageName"),
        appName = obj.getString("appName"),
        accentColorHex = obj.getLong("accentColorHex"),
        merchantName = obj.optStringOrNull("merchantName"),
        pickupAtMillis = obj.optLongOrNull("pickupAtMillis"),
        sourceNotificationKey = obj.optStringOrNull("sourceNotificationKey"),
        title = obj.optString("title", ""),
        contentText = obj.optString("contentText", ""),
        stage = stage,
        progress = obj.optInt("progress", 0),
        chipShortText = obj.optString("chipShortText", ""),
        initialMinutes = obj.optIntOrNull("initialMinutes"),
        remainingMinutes = obj.optIntOrNull("remainingMinutes"),
        initialDistanceMeters = obj.optIntOrNull("initialDistanceMeters"),
        remainingDistanceMeters = obj.optIntOrNull("remainingDistanceMeters"),
        etaTimestampMillis = obj.optLongOrNull("etaTimestampMillis"),
        matchedRuleName = obj.optString("matchedRuleName", ""),
        lastUpdatedMillis = obj.optLong("lastUpdatedMillis", System.currentTimeMillis()),
        deliveredAtMillis = obj.optLongOrNull("deliveredAtMillis"),
        autoDismissAtMillis = obj.optLongOrNull("autoDismissAtMillis"),
        isPromotedOngoing = obj.optBoolean("isPromotedOngoing", false),
        promotionDiagnostic = obj.optStringOrNull("promotionDiagnostic") ?: "",
        userDismissed = obj.optBoolean("userDismissed", false),
      )
    }.getOrNull()
  }

  private fun JSONObject.optStringOrNull(key: String): String? =
    if (isNull(key)) null else optString(key, null)

  private fun JSONObject.optLongOrNull(key: String): Long? =
    if (isNull(key)) null else optLong(key)

  private fun JSONObject.optIntOrNull(key: String): Int? =
    if (isNull(key)) null else optInt(key)
}
