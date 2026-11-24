package com.example

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.ui.MainViewModel
import com.example.ui.screens.LogsScreen
import com.example.ui.screens.MonitorScreen
import com.example.ui.screens.RulesScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.theme.LiveDeliveryTheme
import com.example.util.SystemPermissionHelper

enum class LiveDeliveryRoute(
  val route: String,
  val label: String,
  val selectedIcon: ImageVector,
  val unselectedIcon: ImageVector,
) {
  MONITOR("monitor", "实时监控", Icons.Rounded.NotificationsActive, Icons.Outlined.NotificationsActive),
  RULES("rules", "应用与正则", Icons.Rounded.Tune, Icons.Outlined.Tune),
  LOGS("logs", "拦截日志", Icons.Rounded.History, Icons.Outlined.History),
  SETTINGS("settings", "系统设置", Icons.Rounded.Settings, Icons.Outlined.Settings),
}

class MainActivity : ComponentActivity() {
  private val viewModel: MainViewModel by viewModels()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      LiveDeliveryTheme {
        LiveDeliveryRoot(viewModel = viewModel)
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveDeliveryRoot(viewModel: MainViewModel) {
  val context = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current
  val navController = rememberNavController()

  val settings by viewModel.settings.collectAsStateWithLifecycle()
  val activeOrders by viewModel.activeOrders.collectAsStateWithLifecycle()
  val interceptLogs by viewModel.interceptLogs.collectAsStateWithLifecycle()
  val permissionStatus by viewModel.permissionStatus.collectAsStateWithLifecycle()

  val notificationPermissionLauncher =
    rememberLauncherForActivityResult(
      contract = ActivityResultContracts.RequestPermission(),
    ) {
      viewModel.refreshPermissions(context)
    }

  DisposableEffect(lifecycleOwner) {
    val observer = LifecycleEventObserver { _, event ->
      if (event == Lifecycle.Event.ON_RESUME) {
        viewModel.refreshPermissions(context)
      }
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
  }

  val navBackStackEntry by navController.currentBackStackEntryAsState()
  val currentDestination = navBackStackEntry?.destination

  val navigateToRoute: (String) -> Unit = { route ->
    navController.navigate(route) {
      popUpTo(navController.graph.findStartDestination().id) {
        saveState = true
      }
      launchSingleTop = true
      restoreState = true
    }
  }

  BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
    val useRail = maxWidth >= 600.dp

    Scaffold(
      contentWindowInsets = WindowInsets(0, 0, 0, 0),
      topBar = {
        CenterAlignedTopAppBar(
          title = {
            Text(
              text = "LiveDelivery",
              style = MaterialTheme.typography.headlineSmall,
              fontWeight = FontWeight.Bold,
            )
          },
          colors =
            TopAppBarDefaults.centerAlignedTopAppBarColors(
              containerColor = MaterialTheme.colorScheme.background,
            ),
        )
      },
      bottomBar = {
        if (!useRail) {
          NavigationBar {
            LiveDeliveryRoute.entries.forEach { dest ->
              val selected =
                currentDestination?.hierarchy?.any { it.route == dest.route } == true
              NavigationBarItem(
                selected = selected,
                onClick = { navigateToRoute(dest.route) },
                icon = {
                  Icon(
                    imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                    contentDescription = dest.label,
                  )
                },
                label = { Text(dest.label) },
                modifier = Modifier.testTag("nav_${dest.route}"),
              )
            }
          }
        }
      },
    ) { innerPadding ->
      Row(
        modifier =
          Modifier
            .fillMaxSize()
            .padding(innerPadding),
      ) {
        if (useRail) {
          NavigationRail {
            LiveDeliveryRoute.entries.forEach { dest ->
              val selected =
                currentDestination?.hierarchy?.any { it.route == dest.route } == true
              NavigationRailItem(
                selected = selected,
                onClick = { navigateToRoute(dest.route) },
                icon = {
                  Icon(
                    imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                    contentDescription = dest.label,
                  )
                },
                label = { Text(dest.label) },
                modifier = Modifier.testTag("rail_${dest.route}"),
              )
            }
          }
        }

        Box(
          modifier = Modifier.fillMaxSize(),
          contentAlignment = Alignment.TopCenter,
        ) {
          NavHost(
            navController = navController,
            startDestination = LiveDeliveryRoute.MONITOR.route,
            modifier = Modifier.widthIn(max = 760.dp).fillMaxSize(),
          ) {
            composable(LiveDeliveryRoute.MONITOR.route) {
              MonitorScreen(
                activeOrders = activeOrders,
                monitoredApps = settings.monitoredApps,
                permissionStatus = permissionStatus,
                tickerMillis = viewModel.tickerMillis,
                onRequestPostNotificationPermission = {
                  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                  } else {
                    SystemPermissionHelper.openAppNotificationSettings(context)
                  }
                },
                onSimulateNotification = { pkg, title, body, hintKey ->
                  viewModel.simulateNotification(pkg, title, body, hintKey)
                },
                onDismissOrder = { orderKey, notifId ->
                  viewModel.dismissOrder(orderKey, notifId)
                },
              )
            }
            composable(LiveDeliveryRoute.RULES.route) {
              RulesScreen(
                monitoredApps = settings.monitoredApps,
                regexConfig = settings.regexConfig,
                onToggleApp = viewModel::toggleMonitoredApp,
                onAddApp = viewModel::addMonitoredApp,
                onRemoveApp = viewModel::removeMonitoredApp,
                onRestorePresets = viewModel::restorePresetApps,
                onSaveRegex = viewModel::saveRegexConfig,
                onResetRegex = viewModel::resetRegexDefaults,
                onPreviewRegex = viewModel::previewRegex,
              )
            }
            composable(LiveDeliveryRoute.LOGS.route) {
              LogsScreen(
                logs = interceptLogs,
                onClearLogs = viewModel::clearLogs,
              )
            }
            composable(LiveDeliveryRoute.SETTINGS.route) {
              SettingsScreen(
                settings = settings,
                permissionStatus = permissionStatus,
                onSetAutoDismissSeconds = viewModel::setAutoDismissSeconds,
                onSetAutoTickCountdown = viewModel::setAutoTickCountdown,
                onSetDebounceMillis = viewModel::setDebounceMillis,
              )
            }
          }
        }
      }
    }
  }
}
