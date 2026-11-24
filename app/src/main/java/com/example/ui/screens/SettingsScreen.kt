package com.example.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.data.UserSettings
import com.example.model.SystemPermissionStatus
import com.example.util.SystemPermissionHelper
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
  settings: UserSettings,
  permissionStatus: SystemPermissionStatus,
  onSetAutoDismissSeconds: (Int) -> Unit,
  onSetAutoTickCountdown: (Boolean) -> Unit,
  onSetDebounceMillis: (Long) -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  var inputText by remember(settings.autoDismissSeconds) {
    mutableStateOf(settings.autoDismissSeconds.toString())
  }

  val minutesPart = settings.autoDismissSeconds / 60
  val secondsPart = settings.autoDismissSeconds % 60
  val humanDuration =
    if (minutesPart > 0) "${settings.autoDismissSeconds} 秒 (${minutesPart} 分 ${secondsPart} 秒)"
    else "${settings.autoDismissSeconds} 秒"

  LazyColumn(
    modifier = modifier.fillMaxSize(),
    contentPadding = PaddingValues(16.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    item {
      Text(
        text = "实时窗策略与系统诊断",
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
      )
    }

    // Stage 3 Auto-dismiss delay X (5s ~ 3600s, default 300s)
    item {
      Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border =
          androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
          ),
      ) {
        Column(
          modifier = Modifier.padding(16.dp),
          verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          Text(
            text = "Stage 3 · 送达后自动销毁延时 (X)",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
          )
          Text(
            text = "匹配到「已送达」后进度设为 100%，并在持续 $humanDuration 后自动关闭实时通知（范围 5 ~ 3600 秒，默认 300 秒）。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )

          Slider(
            value = settings.autoDismissSeconds.toFloat(),
            onValueChange = { raw ->
              val rounded = raw.roundToInt().coerceIn(5, 3600)
              inputText = rounded.toString()
              onSetAutoDismissSeconds(rounded)
            },
            valueRange = 5f..3600f,
            modifier = Modifier.testTag("auto_dismiss_slider"),
          )

          Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            OutlinedTextField(
              value = inputText,
              onValueChange = { raw ->
                val filtered = raw.filter { it.isDigit() }
                inputText = filtered
                filtered.toIntOrNull()?.let { parsed ->
                  if (parsed in 5..3600) {
                    onSetAutoDismissSeconds(parsed)
                  }
                }
              },
              label = { Text("自定义秒数 (5-3600)") },
              keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
              singleLine = true,
              modifier =
                Modifier
                  .weight(1f)
                  .testTag("auto_dismiss_input"),
            )
            Text(
              text = humanDuration,
              style = MaterialTheme.typography.labelMedium,
              color = MaterialTheme.colorScheme.primary,
            )
          }

          FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
          ) {
            listOf(
              15 to "15秒",
              60 to "1分钟",
              300 to "5分钟(默认)",
              900 to "15分钟",
              3600 to "1小时",
            ).forEach { (sec, label) ->
              FilterChip(
                selected = settings.autoDismissSeconds == sec,
                onClick = {
                  inputText = sec.toString()
                  onSetAutoDismissSeconds(sec)
                },
                label = { Text(label) },
              )
            }
          }
        }
      }
    }

    // Countdown & Debounce Settings
    item {
      Card(
        shape = MaterialTheme.shapes.large,
        colors =
          CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
          ),
      ) {
        Column(
          modifier = Modifier.padding(16.dp),
          verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
          ) {
            Column(modifier = Modifier.weight(1f)) {
              Text(
                text = "每分钟平滑倒计时推演",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
              )
              Text(
                text = "外卖 App 未发新通知时，后台协程每 60 秒自动递减剩余分钟并刷新进度条",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
            Switch(
              checked = settings.autoTickCountdown,
              onCheckedChange = onSetAutoTickCountdown,
              modifier = Modifier.testTag("auto_tick_switch"),
            )
          }

          Text(
            text = "通知防抖去重窗口 (${settings.debounceMillis} ms)",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
          )
          FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(500L, 1500L, 3000L, 5000L).forEach { ms ->
              FilterChip(
                selected = settings.debounceMillis == ms,
                onClick = { onSetDebounceMillis(ms) },
                label = { Text("${ms}ms") },
              )
            }
          }
        }
      }
    }

    // Native Android 16 & Samsung Now Bar Diagnostics
    item {
      Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border =
          androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
          ),
      ) {
        Column(
          modifier = Modifier.padding(16.dp),
          verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          Text(
            text = "Android 16 Live Updates & 三星 Now Bar 诊断",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
          )
          DiagnosticLine("设备厂商 / SDK", "${permissionStatus.manufacturer} · API ${permissionStatus.sdkInt}")
          DiagnosticLine(
            "NotificationListenerService",
            if (permissionStatus.isListenerServiceBound) "已绑定活跃" else if (permissionStatus.isNotificationListenerEnabled) "已授权待唤醒" else "未授权",
          )
          DiagnosticLine(
            "canPostPromotedNotifications()",
            if (permissionStatus.canPostPromotedNotifications) "true (系统实时窗已启用)" else "false (请在系统设置开启或检查系统版本)",
          )
          DiagnosticLine(
            "电池优化白名单",
            if (permissionStatus.isIgnoringBatteryOptimizations) "已忽略电池优化" else "受系统休眠限制",
          )

          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            OutlinedButton(
              onClick = { SystemPermissionHelper.openNotificationListenerSettings(context) },
              modifier = Modifier.weight(1f).minimumInteractiveComponentSize(),
            ) {
              Text("通知读取设置")
            }
            OutlinedButton(
              onClick = { SystemPermissionHelper.openPromotedNotificationSettings(context) },
              modifier = Modifier.weight(1f).minimumInteractiveComponentSize(),
            ) {
              Text("实时更新设置")
            }
          }
        }
      }
    }
  }
}

@Composable
private fun DiagnosticLine(label: String, value: String) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
  ) {
    Text(
      text = label,
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
      text = value,
      style = MaterialTheme.typography.labelMedium,
    )
  }
}
