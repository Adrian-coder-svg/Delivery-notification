package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.engine.RegexMatchPreview
import com.example.engine.RegexProgressEngine
import com.example.model.DeliveryStage
import com.example.model.MonitoredApp
import com.example.model.RegexConfig
import com.example.ui.theme.PulseAmber
import com.example.ui.theme.PulseMint

@Composable
fun RulesScreen(
  monitoredApps: List<MonitoredApp>,
  regexConfig: RegexConfig,
  onToggleApp: (String, Boolean) -> Unit,
  onAddApp: (packageName: String, displayName: String, accentColorHex: Long) -> Unit,
  onRemoveApp: (String) -> Unit,
  onRestorePresets: () -> Unit,
  onSaveRegex: (RegexConfig) -> Unit,
  onResetRegex: () -> Unit,
  onPreviewRegex: (rawText: String, simulatedInitialMinutes: Int?) -> RegexMatchPreview,
  modifier: Modifier = Modifier,
) {
  var showAddDialog by remember { mutableStateOf(false) }

  var stage1 by remember(regexConfig) { mutableStateOf(regexConfig.stage1PreparingRegex) }
  var stage2Static by remember(regexConfig) { mutableStateOf(regexConfig.stage2StaticDeliveringRegex) }
  var stage2Eta by remember(regexConfig) { mutableStateOf(regexConfig.stage2EtaRegex) }
  var stage2Dist by remember(regexConfig) { mutableStateOf(regexConfig.stage2DistanceRegex) }
  var stage3 by remember(regexConfig) { mutableStateOf(regexConfig.stage3DeliveredRegex) }
  var savedBanner by remember { mutableStateOf(false) }

  var sandboxInput by remember { mutableStateOf("骑手正前往目的地，预计8分钟送达") }
  val sandboxResult = remember(sandboxInput, stage1, stage2Static, stage2Eta, stage2Dist, stage3) {
    val draftConfig = RegexConfig(stage1, stage2Static, stage2Eta, stage2Dist, stage3)
    RegexProgressEngine().preview(sandboxInput, draftConfig, simulatedInitialMinutes = 10)
  }

  val allRegexValid =
    listOf(stage1, stage2Static, stage2Eta, stage2Dist, stage3).all {
      RegexProgressEngine.isValidRegex(it)
    }

  LazyColumn(
    modifier = modifier.fillMaxSize(),
    contentPadding = PaddingValues(16.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    // Monitored Apps Header
    item {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = "监控外卖与应用包名",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
          )
          Text(
            text = "支持动态增删目标包名，实时过滤非目标通知",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          IconButton(
            onClick = onRestorePresets,
            modifier =
              Modifier
                .minimumInteractiveComponentSize()
                .testTag("restore_preset_apps_btn"),
          ) {
            Icon(imageVector = Icons.Rounded.Refresh, contentDescription = "恢复预设应用")
          }
          Button(
            onClick = { showAddDialog = true },
            modifier =
              Modifier
                .minimumInteractiveComponentSize()
                .testTag("add_monitored_app_btn"),
          ) {
            Icon(imageVector = Icons.Rounded.Add, contentDescription = null)
            Spacer(modifier = Modifier.width(4.dp))
            Text("添加")
          }
        }
      }
    }

    // Monitored App Items
    items(monitoredApps, key = { it.packageName }) { app ->
      Card(
        shape = MaterialTheme.shapes.medium,
        colors =
          CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
          ),
      ) {
        Row(
          modifier =
            Modifier
              .fillMaxWidth()
              .padding(horizontal = 14.dp, vertical = 10.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          Box(
            modifier =
              Modifier
                .size(14.dp)
                .clip(CircleShape)
                .background(Color(app.accentColorHex)),
          )
          Column(modifier = Modifier.weight(1f)) {
            Text(
              text = app.displayName,
              style = MaterialTheme.typography.titleMedium,
              fontWeight = FontWeight.SemiBold,
            )
            Text(
              text = app.packageName,
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          Switch(
            checked = app.enabled,
            onCheckedChange = { onToggleApp(app.packageName, it) },
            modifier = Modifier.testTag("toggle_app_${app.packageName}"),
          )
          IconButton(
            onClick = { onRemoveApp(app.packageName) },
            modifier =
              Modifier
                .minimumInteractiveComponentSize()
                .testTag("remove_app_${app.packageName}"),
          ) {
            Icon(
              imageVector = Icons.Rounded.DeleteOutline,
              contentDescription = "删除 ${app.displayName}",
              tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      }
    }

    // Three-Stage Regex Engine Section
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
          Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
          ) {
            Column(modifier = Modifier.weight(1f)) {
              Text(
                text = "三段式正则进度引擎",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
              )
              Text(
                text = "Stage 1 (0%) → Stage 2 (1%~99%) → Stage 3 (100%)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
            TextButton(
              onClick = {
                onResetRegex()
                savedBanner = false
              },
              modifier = Modifier.minimumInteractiveComponentSize(),
            ) {
              Text("恢复默认")
            }
          }

          RegexField(
            label = "Stage 1 · 备餐/接单正则 (固定 0%)",
            value = stage1,
            onValueChange = {
              stage1 = it
              savedBanner = false
            },
          )
          RegexField(
            label = "Stage 2A · 静态配送正则 (默认 50%)",
            value = stage2Static,
            onValueChange = {
              stage2Static = it
              savedBanner = false
            },
          )
          RegexField(
            label = "Stage 2B · 动态时间捕获正则 (Group 1 = 分钟)",
            value = stage2Eta,
            onValueChange = {
              stage2Eta = it
              savedBanner = false
            },
          )
          RegexField(
            label = "Stage 2B · 动态距离捕获正则 (Group 1 = 数值, 2 = 单位)",
            value = stage2Dist,
            onValueChange = {
              stage2Dist = it
              savedBanner = false
            },
          )
          RegexField(
            label = "Stage 3 · 送达完结正则 (100% 并启动销毁计时)",
            value = stage3,
            onValueChange = {
              stage3 = it
              savedBanner = false
            },
          )

          Button(
            onClick = {
              if (allRegexValid) {
                onSaveRegex(RegexConfig(stage1, stage2Static, stage2Eta, stage2Dist, stage3))
                savedBanner = true
              }
            },
            enabled = allRegexValid,
            modifier =
              Modifier
                .fillMaxWidth()
                .minimumInteractiveComponentSize()
                .testTag("save_regex_btn"),
          ) {
            Icon(imageVector = Icons.Rounded.Save, contentDescription = null)
            Spacer(modifier = Modifier.width(6.dp))
            Text(if (savedBanner) "正则配置已保存并生效" else "保存正则配置")
          }
        }
      }
    }

    // Regex Sandbox Tester
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
          verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          Text(
            text = "正则沙盒即时测试 (模拟基准 10 分钟)",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
          )
          OutlinedTextField(
            value = sandboxInput,
            onValueChange = { sandboxInput = it },
            label = { Text("输入任意测试通知文本") },
            modifier = Modifier.fillMaxWidth(),
          )
          val badgeColor =
            when (sandboxResult.stage) {
              DeliveryStage.PREPARING -> PulseAmber
              DeliveryStage.DELIVERING -> MaterialTheme.colorScheme.primary
              DeliveryStage.DELIVERED -> PulseMint
              null -> MaterialTheme.colorScheme.onSurfaceVariant
            }
          Surface(
            color = badgeColor.copy(alpha = 0.14f),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
          ) {
            Column(
              modifier = Modifier.padding(12.dp),
              verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
              Text(
                text =
                  if (sandboxResult.matched)
                    "命中: ${sandboxResult.matchedRuleName} · 进度 ${sandboxResult.progress}% · 胶囊 [${sandboxResult.chipShortText}]"
                  else sandboxResult.matchedRuleName,
                style = MaterialTheme.typography.labelMedium,
                color = badgeColor,
              )
            }
          }
        }
      }
    }
  }

  if (showAddDialog) {
    AddPackageDialog(
      onDismiss = { showAddDialog = false },
      onConfirm = { pkg, name, color ->
        onAddApp(pkg, name, color)
        showAddDialog = false
      },
    )
  }
}

@Composable
private fun RegexField(
  label: String,
  value: String,
  onValueChange: (String) -> Unit,
) {
  val isValid = RegexProgressEngine.isValidRegex(value)
  OutlinedTextField(
    value = value,
    onValueChange = onValueChange,
    label = { Text(label) },
    isError = !isValid,
    supportingText =
      if (!isValid) {
        { Text("正则表达式语法无效或为空") }
      } else null,
    textStyle = MaterialTheme.typography.labelMedium,
    modifier = Modifier.fillMaxWidth(),
  )
}

@Composable
private fun AddPackageDialog(
  onDismiss: () -> Unit,
  onConfirm: (packageName: String, displayName: String, accentColorHex: Long) -> Unit,
) {
  var pkgName by remember { mutableStateOf("") }
  var appName by remember { mutableStateOf("") }
  val swatchColors =
    listOf(0xFFFF6B35L, 0xFFFFC300L, 0xFF0093FFL, 0xFFE1251BL, 0xFF10B981L, 0xFF8B5CF6L)
  var selectedColor by remember { mutableLongStateOf(swatchColors.first()) }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("添加监控应用包名") },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
          value = pkgName,
          onValueChange = { pkgName = it },
          label = { Text("Android 包名 (如 com.sankuai.meituan.takeoutnew)") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
          value = appName,
          onValueChange = { appName = it },
          label = { Text("显示名称 (如 美团外卖)") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )
        Text("选择胶囊主题色", style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
          swatchColors.forEach { hex ->
            val selected = hex == selectedColor
            Box(
              modifier =
                Modifier
                  .size(34.dp)
                  .clip(CircleShape)
                  .background(Color(hex))
                  .then(
                    if (selected) Modifier.border(3.dp, Color.White, CircleShape)
                    else Modifier
                  )
                  .clickable { selectedColor = hex },
            )
          }
        }
      }
    },
    confirmButton = {
      Button(
        onClick = { onConfirm(pkgName, appName, selectedColor) },
        enabled = pkgName.trim().contains('.'),
      ) {
        Text("添加")
      }
    },
    dismissButton = {
      OutlinedButton(onClick = onDismiss) {
        Text("取消")
      }
    },
  )
}
