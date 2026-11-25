package com.pnprojects.tarangfx.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddBox
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.rounded.AddBox
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.pnprojects.tarangfx.ui.convert.ConvertScreen
import com.pnprojects.tarangfx.ui.convert.VideoToAudioScreen
import com.pnprojects.tarangfx.ui.denoise.AiDenoiseScreen
import com.pnprojects.tarangfx.ui.fx.FxEqStudioScreen
import com.pnprojects.tarangfx.ui.home.HomeScreen
import com.pnprojects.tarangfx.ui.merge.MergeScreen
import com.pnprojects.tarangfx.ui.metadata.TagEditorScreen
import com.pnprojects.tarangfx.ui.mix.MixScreen
import com.pnprojects.tarangfx.ui.outputs.OutputsScreen
import com.pnprojects.tarangfx.ui.picker.FilePickerScreen
import com.pnprojects.tarangfx.ui.record.RecordScreen
import com.pnprojects.tarangfx.ui.settings.SettingsScreen
import com.pnprojects.tarangfx.ui.split.SplitScreen
import com.pnprojects.tarangfx.ui.theme.AppColors
import com.pnprojects.tarangfx.ui.trim.TrimScreen
import kotlinx.coroutines.flow.StateFlow

@Composable
fun AppNavHost(incomingFile: StateFlow<Long?>, onIncomingConsumed: () -> Unit) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val showBottomBar = route == Routes.EDIT || route == Routes.OUTPUTS

    val incoming by incomingFile.collectAsState()
    LaunchedEffect(incoming) {
        if (incoming != null) {
            nav.navigate(Tool.TRIM.editorRoute)
            onIncomingConsumed()
        }
    }

    Scaffold(
        containerColor = AppColors.Canvas,
        bottomBar = { if (showBottomBar) BottomBar(nav, route) },
    ) { padding ->
        Box(Modifier.fillMaxSize().background(AppColors.Canvas).padding(bottom = padding.calculateBottomPadding())) {
            NavHost(nav, startDestination = Routes.EDIT) {
                composable(Routes.EDIT) {
                    HomeScreen(
                        onTool = { tool ->
                            nav.navigate(if (tool.needsPicker) Routes.picker(tool) else tool.editorRoute)
                        },
                        onSettings = { nav.navigate(Routes.SETTINGS) },
                    )
                }
                composable(Routes.OUTPUTS) {
                    OutputsScreen(
                        onOpenInEditor = { nav.navigate(Tool.TRIM.editorRoute) },
                        onEditTags = { nav.navigate(Tool.TAGS.editorRoute) },
                        onSettings = { nav.navigate(Routes.SETTINGS) },
                    )
                }
                composable(Routes.SETTINGS) { SettingsScreen(onBack = { nav.popBackStack() }) }
                composable(Routes.PICKER, arguments = listOf(navArgument("tool") { type = NavType.StringType })) { entry ->
                    val tool = Tool.valueOf(entry.arguments?.getString("tool") ?: Tool.TRIM.name)
                    FilePickerScreen(
                        tool = tool, appendMode = false,
                        onBack = { nav.popBackStack() },
                        onPicked = {
                            nav.navigate(tool.editorRoute) { popUpTo(Routes.PICKER) { inclusive = true } }
                        },
                    )
                }
                composable(Routes.APPEND_PICKER, arguments = listOf(navArgument("tool") { type = NavType.StringType })) { entry ->
                    val tool = Tool.valueOf(entry.arguments?.getString("tool") ?: Tool.MERGE.name)
                    FilePickerScreen(tool = tool, appendMode = true, onBack = { nav.popBackStack() }, onPicked = { nav.popBackStack() })
                }
                val back: () -> Unit = { nav.popBackStack() }
                val toOutputs: () -> Unit = { nav.navigateToOutputs() }
                composable(Tool.TRIM.editorRoute) { TrimScreen(ringtoneMode = false, onBack = back, onOpenOutputs = toOutputs) }
                composable(Tool.RINGTONE.editorRoute) { TrimScreen(ringtoneMode = true, onBack = back, onOpenOutputs = toOutputs) }
                composable(Tool.SPLIT.editorRoute) { SplitScreen(onBack = back, onOpenOutputs = toOutputs) }
                composable(Tool.MERGE.editorRoute) {
                    MergeScreen(onBack = back, onAddAudio = { nav.navigate(Routes.appendPicker(Tool.MERGE)) }, onOpenOutputs = toOutputs)
                }
                composable(Tool.MIX.editorRoute) {
                    MixScreen(onBack = back, onAddAudio = { nav.navigate(Routes.appendPicker(Tool.MIX)) }, onOpenOutputs = toOutputs)
                }
                composable(Tool.RECORD.editorRoute) { RecordScreen(onBack = back, onOpenOutputs = toOutputs) }
                composable(Tool.DENOISE.editorRoute) { AiDenoiseScreen(onBack = back, onOpenOutputs = toOutputs) }
                composable(Tool.FX.editorRoute) { FxEqStudioScreen(onBack = back, onOpenOutputs = toOutputs) }
                composable(Tool.CONVERT.editorRoute) { ConvertScreen(onBack = back, onOpenOutputs = toOutputs) }
                composable(Tool.VIDEO.editorRoute) { VideoToAudioScreen(onBack = back, onOpenOutputs = toOutputs) }
                composable(Tool.TAGS.editorRoute) { TagEditorScreen(onBack = back, onOpenOutputs = toOutputs) }
            }
        }
    }
}

private fun NavHostController.navigateToOutputs() {
    navigate(Routes.OUTPUTS) {
        popUpTo(Routes.EDIT) { saveState = true }
        launchSingleTop = true
    }
}

@Composable
private fun BottomBar(nav: NavHostController, route: String?) {
    NavigationBar(
        containerColor = AppColors.Surface,
        modifier = Modifier.clip(RoundedCornerShape(topStart = 24.dp(), topEnd = 24.dp())),
    ) {
        val items = listOf(
            Triple(Routes.EDIT, "Edit", Icons.Rounded.AddBox to Icons.Outlined.AddBox),
            Triple(Routes.OUTPUTS, "Outputs", Icons.Rounded.LibraryMusic to Icons.Outlined.LibraryMusic),
        )
        items.forEach { (r, label, icons) ->
            val selected = route == r
            NavigationBarItem(
                selected = selected,
                onClick = {
                    if (!selected) nav.navigate(r) {
                        popUpTo(Routes.EDIT) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                icon = { Icon(if (selected) icons.first else icons.second, label) },
                label = { Text(label, style = MaterialTheme.typography.labelMedium) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = AppColors.TextPrimary, selectedTextColor = AppColors.TextPrimary,
                    unselectedIconColor = AppColors.TextDisabled, unselectedTextColor = AppColors.TextDisabled,
                    indicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                ),
            )
        }
    }
}

private fun Int.dp() = androidx.compose.ui.unit.Dp(this.toFloat())
