package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.model.DeliveryStage
import com.example.model.LiveOrderState
import com.example.model.MonitoredApp
import com.example.model.SystemPermissionStatus
import com.example.ui.components.NowBarPreviewCard
import com.example.ui.theme.PulseAmber
import com.example.ui.theme.PulseMint
import com.example.util.SystemPermissionHelper

@Composable
fun MonitorScreen(
  activeOrders: List<LiveOrderState>,
  monitoredApps: List<MonitoredApp>,
  permissionStatus: SystemPermissionStatus,
  nowMillis: Long,
  onRequestPostNotificationPermission: () -> Unit,
  onSimulateNotification: (packageName: String, title: String, body: String, orderHintKey: String) -> Unit,
  onDismissOrder: (orderKey: String, notificationId: Int) -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  var showPermissionChecklist by remember {
    mutableStateOf(
      !permissionStatus.isNotificationListenerEnabled ||
        !permissionStatus.canPostNotifications ||
        !permissionStatus.canPostPromotedNotifications,
    )
  }
  var showSimulatorPanel by remember { mutableStateOf(false) }

  LazyColumn(
    modifier = modifier.fillMaxSize(),
    contentPadding = PaddingValues(16.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    // Hero Header Banner
    item {
      HeroStatusBanner(
        permissionStatus = permissionStatus,
        activeCount = activeOrders.size,
        onToggleChecklist = { showPermissionChecklist = !showPermissionChecklist },
        isChecklistExpanded = showPermissionChecklist,
      )
    }

    // Expandable Permission & Keep-Alive Checklist
    item {
      AnimatedVisibility(visible = showPermissionChecklist) {
        PermissionChecklistCard(
          status = permissionStatus,
          onOpenListenerSettings = { SystemPermissionHelper.openNotificationListenerSettings(context) },
          onRequestPostNotifications = onRequestPostNotificationPermission,
          onOpenPromotedSettings = { SystemPermissionHelper.openPromotedNotificationSettings(context) },
          onRequestIgnoreBattery = { SystemPermissionHelper.requestIgnoreBatteryOptimizations(context) },
        )
      }
    }

    // Pipeline Simulator / Quick Test Bench
    item {
      PipelineTesterCard(
        monitoredApps = monitoredApps,
        expanded = showSimulatorPanel,
        onToggleExpand = { showSimulatorPanel = !showSimulatorPanel },
        onSimulate = onSimulateNotification,
      )
    }

    // Active Live Orders Section
    if (activeOrders.isEmpty()) {
      item {
        EmptyOrdersCard(
          onRunSampleFlow = {
            val targetApp = monitoredApps.firstOrNull { it.enabled } ?: MonitoredApp.PRESETS.first()
            onSimulateNotification(
              targetApp.packageName,
              "${targetApp.displayName}外卖订单 #1024",
              "骑手正前往目的地，预计10分钟送达",
              "demo_1024",
            )
          },
        )
      }
    } else {
      item {
        Text(
          text = "活跃实时窗订单 (${activeOrders.size})",
          style = MaterialTheme.typography.titleMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(horizontal = 4.dp),
        )
      }
      items(activeOrders, key = { it.orderKey }) { order ->
        NowBarPreviewCard(
          order = order,
          nowMillis = nowMillis,
          onDismiss = { onDismissOrder(order.orderKey, order.notificationId) },
          onAdvanceSimulation = {
            val nextBody =
              when (order.stage) {
                DeliveryStage.PREPARING -> "骑手已取餐正赶往您的位置，预计10分钟送达"
                DeliveryStage.DELIVERING -> {
                  val curMin = order.remainingMinutes
                  when {
                    curMin != null && curMin > 5 -> "骑手距离您更近了，预计${(curMin - 4).coerceAtLeast(1)}分钟送达"
                    curMin != null && curMin > 2 -> "骑手即将到达，还有350米"
                    else -> "您的外卖已送达，感谢使用${order.appName}"
                  }
                }
                DeliveryStage.DELIVERED -> null
              }
            if (nextBody != null) {
              onSimulateNotification(order.packageName, order.title, nextBody, order.orderKey)
            }
          },
        )
      }
    }
  }
}

@Composable
private fun HeroStatusBanner(
  permissionStatus: SystemPermissionStatus,
  activeCount: Int,
  onToggleChecklist: () -> Unit,
  isChecklistExpanded: Boolean,
) {
  val allCoreReady =
    permissionStatus.isNotificationListenerEnabled && permissionStatus.canPostNotifications

  Box(
    modifier =
      Modifier
        .fillMaxWidth()
        .clip(MaterialTheme.shapes.large)
        .background(
          Brush.linearGradient(
            colors =
              listOf(
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.9f),
                MaterialTheme.colorScheme.surfaceVariant,
                MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f),
              ),
          ),
        )
        .clickable(onClick = onToggleChecklist)
        .padding(18.dp),
  ) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          Box(
            modifier =
              Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(if (allCoreReady) PulseMint else PulseAmber),
          )
          Text(
            text =
              if (permissionStatus.isListenerServiceBound) "监听引擎运行中"
              else if (permissionStatus.isNotificationListenerEnabled) "监听服务已授权待唤醒"
              else "需要配置系统通知权限",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
          )
        }
        Icon(
          imageVector =
            if (isChecklistExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
          contentDescription = "展开或收起权限检查",
        )
      }

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        MiniStatPill(
          label = "API ${permissionStatus.sdkInt}",
          highlight = permissionStatus.sdkInt >= 36,
        )
        MiniStatPill(
          label = if (permissionStatus.canPostPromotedNotifications) "NowBar 就绪" else "标准/待授权",
          highlight = permissionStatus.canPostPromotedNotifications,
        )
        MiniStatPill(
          label = "活跃订单 $activeCount",
          highlight = activeCount > 0,
        )
      }
    }
  }
}

@Composable
private fun MiniStatPill(label: String, highlight: Boolean) {
  Surface(
    color =
      if (highlight) PulseMint.copy(alpha = 0.18f)
      else MaterialTheme.colorScheme.surface.copy(alpha = 0.65f),
    shape = RoundedCornerShape(50),
  ) {
    Text(
      text = label,
      style = MaterialTheme.typography.labelSmall,
      color = if (highlight) PulseMint else MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
    )
  }
}

@Composable
private fun PermissionChecklistCard(
  status: SystemPermissionStatus,
  onOpenListenerSettings: () -> Unit,
  onRequestPostNotifications: () -> Unit,
  onOpenPromotedSettings: () -> Unit,
  onRequestIgnoreBattery: () -> Unit,
) {
  Card(
    shape = MaterialTheme.shapes.large,
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    border =
      androidx.compose.foundation.BorderStroke(
        1.dp,
        MaterialTheme.colorScheme.outline.copy(alpha = 0.45f),
      ),
  ) {
    Column(
      modifier = Modifier.padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Text(
        text = "后台保活与实时更新权限引导",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
      )
      PermissionRow(
        icon = Icons.Rounded.Security,
        title = "通知读取权限 (NotificationListener)",
        subtitle = "监听美团/饿了么/京东配送状态变更",
        granted = status.isNotificationListenerEnabled,
        buttonLabel = if (status.isNotificationListenerEnabled) "已开启" else "去授权",
        onClick = onOpenListenerSettings,
        testTag = "perm_listener_btn",
      )
      PermissionRow(
        icon = Icons.Rounded.NotificationsActive,
        title = "发送通知权限 (POST_NOTIFICATIONS)",
        subtitle = "允许推送 LiveDelivery 实时进度窗",
        granted = status.canPostNotifications,
        buttonLabel = if (status.canPostNotifications) "已开启" else "请求权限",
        onClick = onRequestPostNotifications,
        testTag = "perm_post_notif_btn",
      )
      PermissionRow(
        icon = Icons.Rounded.NotificationsActive,
        title = "实时更新胶囊 (POST_PROMOTED_NOTIFICATIONS)",
        subtitle = "Android 16 状态栏胶囊与三星 Now Bar 关键权限",
        granted = status.canPostPromotedNotifications,
        buttonLabel = if (status.canPostPromotedNotifications) "已开启" else "去设置",
        onClick = onOpenPromotedSettings,
        testTag = "perm_promoted_btn",
      )
      PermissionRow(
        icon = Icons.Rounded.BatteryChargingFull,
        title = "忽略电池优化 (保活防断连)",
        subtitle = "防止系统休眠断开监听与延时销毁协程",
        granted = status.isIgnoringBatteryOptimizations,
        buttonLabel = if (status.isIgnoringBatteryOptimizations) "已豁免" else "去关闭优化",
        onClick = onRequestIgnoreBattery,
        testTag = "perm_battery_btn",
      )
    }
  }
}

@Composable
private fun PermissionRow(
  icon: ImageVector,
  title: String,
  subtitle: String,
  granted: Boolean,
  buttonLabel: String,
  onClick: () -> Unit,
  testTag: String,
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Icon(
      imageVector = if (granted) Icons.Rounded.CheckCircle else icon,
      contentDescription = null,
      tint = if (granted) PulseMint else PulseAmber,
      modifier = Modifier.size(22.dp),
    )
    Column(modifier = Modifier.weight(1f)) {
      Text(text = title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
      Text(
        text = subtitle,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    OutlinedButton(
      onClick = onClick,
      modifier =
        Modifier
          .minimumInteractiveComponentSize()
          .testTag(testTag),
    ) {
      Text(buttonLabel)
    }
  }
}

@Composable
private fun PipelineTesterCard(
  monitoredApps: List<MonitoredApp>,
  expanded: Boolean,
  onToggleExpand: () -> Unit,
  onSimulate: (packageName: String, title: String, body: String, orderHintKey: String) -> Unit,
) {
  val enabledApps = monitoredApps.filter { it.enabled }.ifEmpty { MonitoredApp.PRESETS }
  var selectedApp by remember(enabledApps) { mutableStateOf(enabledApps.first()) }
  var customTitle by remember { mutableStateOf("美团外卖订单 #886") }
  var customBody by remember { mutableStateOf("骑手正前往目的地，预计10分钟送达") }

  Card(
    shape = MaterialTheme.shapes.large,
    colors =
      CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
      ),
  ) {
    Column(
      modifier = Modifier.padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Row(
        modifier =
          Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggleExpand)
            .minimumInteractiveComponentSize(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Icon(
            imageVector = Icons.Rounded.Science,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
          )
          Column {
            Text(
              text = "实时管道验证台 (真实发送系统 Live 通知)",
              style = MaterialTheme.typography.titleMedium,
              fontWeight = FontWeight.SemiBold,
            )
            Text(
              text = "点击展开模拟美团/饿了么/京东三段式状态流转",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
        Icon(
          imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
          contentDescription = "展开验证台",
        )
      }

      AnimatedVisibility(visible = expanded) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
          LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(enabledApps, key = { it.packageName }) { app ->
              FilterChip(
                selected = selectedApp.packageName == app.packageName,
                onClick = {
                  selectedApp = app
                  customTitle = "${app.displayName}配送订单 #886"
                },
                label = { Text(app.displayName) },
              )
            }
          }

          val presets =
            listOf(
              "1·备餐" to "商家已接单，商家备餐中",
              "2·静态" to "骑手送餐中，请保持电话畅通",
              "2·10min" to "骑手已取餐，预计10分钟送达",
              "2·7min" to "骑手加速中，预计7分钟送达",
              "2·450m" to "骑手距离您还有450米",
              "3·送达" to "您的订单已送达，感谢使用",
            )

          LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(presets) { (chipLabel, sampleBody) ->
              OutlinedButton(
                onClick = {
                  customBody = sampleBody
                  onSimulate(
                    selectedApp.packageName,
                    customTitle,
                    sampleBody,
                    "sim_${selectedApp.packageName}_886",
                  )
                },
                modifier = Modifier.minimumInteractiveComponentSize(),
              ) {
                Text(chipLabel)
              }
            }
          }

          OutlinedTextField(
            value = customTitle,
            onValueChange = { customTitle = it },
            label = { Text("模拟通知标题 (android.title)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
          )
          OutlinedTextField(
            value = customBody,
            onValueChange = { customBody = it },
            label = { Text("模拟通知正文 (android.text / bigText)") },
            modifier = Modifier.fillMaxWidth(),
          )

          Button(
            onClick = {
              onSimulate(
                selectedApp.packageName,
                customTitle,
                customBody,
                "sim_${selectedApp.packageName}_886",
              )
            },
            modifier =
              Modifier
                .fillMaxWidth()
                .minimumInteractiveComponentSize()
                .testTag("fire_simulated_live_notif"),
          ) {
            Icon(imageVector = Icons.Rounded.PlayArrow, contentDescription = null)
            Spacer(modifier = Modifier.width(6.dp))
            Text("注入解析引擎并推送系统 Live Notification")
          }
        }
      }
    }
  }
}

@Composable
private fun EmptyOrdersCard(onRunSampleFlow: () -> Unit) {
  Card(
    shape = MaterialTheme.shapes.large,
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
  ) {
    Column(
      modifier =
        Modifier
          .fillMaxWidth()
          .padding(18.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Image(
        painter = painterResource(id = R.drawable.img_delivery_empty_1795485530808),
        contentDescription = "等待捕获配送通知插画",
        contentScale = ContentScale.Crop,
        modifier =
          Modifier
            .fillMaxWidth()
            .height(170.dp)
            .clip(MaterialTheme.shapes.medium),
      )
      Text(
        text = "暂无进行中的实时配送订单",
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.SemiBold,
      )
      Text(
        text = "当美团、饿了么或京东发送备餐/配送通知时，LiveDelivery 将自动转换并挂载到系统状态栏胶囊与三星 Now Bar 实时窗。",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Button(
        onClick = onRunSampleFlow,
        modifier =
          Modifier
            .fillMaxWidth()
            .minimumInteractiveComponentSize()
            .testTag("empty_quick_test_btn"),
      ) {
        Icon(imageVector = Icons.Rounded.PlayArrow, contentDescription = null)
        Spacer(modifier = Modifier.width(6.dp))
        Text("立即发送一条测试 Live 配送进度")
      }
    }
  }
}
