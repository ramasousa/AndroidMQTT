package com.raulsousa.pulso.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.raulsousa.pulso.domain.DeviceId
import com.raulsousa.pulso.ui.automation.AutomationScreen
import com.raulsousa.pulso.ui.components.ConnectionPill
import com.raulsousa.pulso.ui.dashboard.DashboardScreen
import com.raulsousa.pulso.ui.device.DeviceDetailScreen
import com.raulsousa.pulso.ui.inspector.InspectorScreen
import com.raulsousa.pulso.ui.settings.SettingsScreen

private enum class Destination(val route: String, val label: String, val icon: ImageVector) {
    DASHBOARD("dashboard", "Casa", Icons.Filled.Home),
    AUTOMATION("automation", "Automações", Icons.Filled.Bolt),
    INSPECTOR("inspector", "Inspetor", Icons.Filled.Terminal),
    SETTINGS("settings", "Ajustes", Icons.Filled.Settings),
}

private const val DEVICE_ROUTE = "device/{deviceId}"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PulsoApp(viewModel: PulsoViewModel = viewModel(factory = PulsoViewModel.Factory)) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination

    val home by viewModel.home.collectAsStateWithLifecycle()
    val connection by viewModel.connection.collectAsStateWithLifecycle()
    val trace by viewModel.trace.collectAsStateWithLifecycle()
    val firings by viewModel.firings.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val message by viewModel.messages.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(message) {
        val text = message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(text)
        viewModel.consumeMessage()
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    ConnectionPill(
                        state = connection,
                        demoMode = settings?.demoMode == true,
                    )
                },
            )
        },
        bottomBar = {
            NavigationBar {
                Destination.entries.forEach { destination ->
                    NavigationBarItem(
                        selected = currentRoute?.hierarchy?.any { it.route == destination.route } == true,
                        onClick = {
                            navController.navigate(destination.route) {
                                // Sem isto, cada troca de aba empilha uma tela
                                // nova e o botão "voltar" vira um labirinto.
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(destination.icon, contentDescription = destination.label) },
                        label = { Text(destination.label) },
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Destination.DASHBOARD.route,
            modifier = Modifier.fillMaxSize(),
        ) {
            composable(Destination.DASHBOARD.route) {
                DashboardScreen(
                    home = home,
                    discoveryPrefix = settings?.profile?.discoveryPrefix ?: "homeassistant",
                    telemetry = { viewModel.telemetry(it) },
                    onToggle = viewModel::toggle,
                    onOpen = { navController.navigate("device/${it.value}") },
                    contentPadding = padding,
                )
            }

            composable(Destination.AUTOMATION.route) {
                AutomationScreen(
                    rules = settings?.rules.orEmpty(),
                    firings = firings,
                    home = home,
                    onToggleRule = { rule, enabled ->
                        val updated = settings?.rules.orEmpty().map {
                            if (it.id == rule.id) it.copy(enabled = enabled) else it
                        }
                        viewModel.saveRules(updated)
                    },
                    contentPadding = padding,
                )
            }

            composable(Destination.INSPECTOR.route) {
                InspectorScreen(
                    trace = trace,
                    subscriptions = viewModel.manualSubscriptions(),
                    onWatch = viewModel::watch,
                    onUnwatch = viewModel::unwatch,
                    onPublish = viewModel::publish,
                    onClear = viewModel::clearTrace,
                    contentPadding = padding,
                )
            }

            composable(Destination.SETTINGS.route) {
                val current = settings
                if (current == null) {
                    Box(Modifier.fillMaxSize().padding(padding))
                } else {
                    SettingsScreen(
                        settings = current,
                        onSaveProfile = viewModel::saveProfile,
                        onDemoMode = viewModel::setDemoMode,
                        onKeepAlive = viewModel::setKeepAlive,
                        contentPadding = padding,
                    )
                }
            }

            composable(DEVICE_ROUTE) { entry ->
                val id = entry.arguments?.getString("deviceId")?.let(::DeviceId)
                val device = id?.let { home.device(it) }
                if (device == null) {
                    // O dispositivo pode sumir enquanto a tela está aberta
                    // (discovery de remoção): volta em vez de estourar.
                    LaunchedEffect(Unit) { navController.popBackStack() }
                } else {
                    DeviceDetailScreen(
                        device = device,
                        samples = viewModel.telemetry(device.id),
                        onToggle = { viewModel.toggle(device.id) },
                        onBrightness = { viewModel.setBrightness(device.id, it) },
                        contentPadding = PaddingValues(
                            top = padding.calculateTopPadding() + 8.dp,
                            bottom = padding.calculateBottomPadding() + 24.dp,
                        ),
                    )
                }
            }
        }
    }
}
