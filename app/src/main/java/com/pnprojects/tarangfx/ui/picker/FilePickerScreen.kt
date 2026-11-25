package com.pnprojects.tarangfx.ui.picker

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pnprojects.tarangfx.domain.model.AudioFile
import com.pnprojects.tarangfx.ui.components.EmptyState
import com.pnprojects.tarangfx.ui.components.Fmt
import com.pnprojects.tarangfx.ui.components.PrimaryButton
import com.pnprojects.tarangfx.ui.components.ScreenTopBar
import com.pnprojects.tarangfx.ui.components.SelectPill
import com.pnprojects.tarangfx.ui.components.appTextFieldColors
import com.pnprojects.tarangfx.ui.navigation.Tool
import com.pnprojects.tarangfx.ui.theme.AppColors

@Composable
fun FilePickerScreen(tool: Tool, appendMode: Boolean, onBack: () -> Unit, onPicked: () -> Unit, vm: PickerViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val ui by vm.ui.collectAsStateWithLifecycle()
    val previewUri by vm.previewUri.collectAsStateWithLifecycle()
    val previewPlaying by vm.previewPlaying.collectAsStateWithLifecycle()
    val multi = tool.multiSelect

    val permissions = remember(tool) {
        if (Build.VERSION.SDK_INT >= 33) arrayOf(if (tool.video) Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_MEDIA_AUDIO)
        else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
    var granted by remember {
        mutableStateOf(permissions.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED })
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        granted = res.values.all { it }
        if (granted) vm.load(tool.video)
    }
    LaunchedEffect(Unit) {
        if (granted) vm.load(tool.video) else permLauncher.launch(permissions)
    }
    val docLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        vm.addDocuments(uris, multi) { if (vm.confirm(appendMode)) onPicked() }
    }

    var searching by remember { mutableStateOf(false) }
    val searchFocus = remember { FocusRequester() }
    LaunchedEffect(searching) { if (searching) searchFocus.requestFocus() }
    var sortMenu by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    LaunchedEffect(ui.tab, ui.sort, ui.query, ui.folder) { listState.scrollToItem(0) }

    Column(Modifier.fillMaxSize().background(AppColors.Canvas)) {
        ScreenTopBar(
            title = if (tool.video) "Select videos" else if (multi) "Select audio files" else "Select audio",
            onBack = onBack,
            actions = {
                IconButton(onClick = { searching = !searching }) { Icon(Icons.Rounded.Search, "Search", tint = AppColors.TextPrimary) }
                Box {
                    IconButton(onClick = { sortMenu = true }) { Icon(Icons.AutoMirrored.Rounded.Sort, "Sort", tint = AppColors.TextPrimary) }
                    DropdownMenu(sortMenu, { sortMenu = false }, containerColor = AppColors.SurfaceVariant) {
                        SortBy.entries.forEach { s ->
                            DropdownMenuItem(text = { Text(s.label, color = if (ui.sort == s) AppColors.Crimson else AppColors.TextPrimary) },
                                onClick = { vm.setSort(s); sortMenu = false })
                        }
                    }
                }
                IconButton(onClick = { docLauncher.launch(arrayOf(if (tool.video) "video/*" else "audio/*")) }) {
                    Icon(Icons.Rounded.FolderOpen, "Browse files", tint = AppColors.TextPrimary)
                }
            },
        )
        if (searching) {
            OutlinedTextField(
                value = ui.query, onValueChange = vm::setQuery, placeholder = { Text("Search") }, singleLine = true,
                colors = appTextFieldColors(), modifier = Modifier.focusRequester(searchFocus).fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PickerTab.entries.filter { !(tool.video && it == PickerTab.OUTPUTS) }.forEach { t ->
                SelectPill(if (t == PickerTab.ALL && tool.video) "All videos" else if (t == PickerTab.ALL) "All audio" else t.label, ui.tab == t) { vm.setTab(t) }
            }
        }
        Box(Modifier.weight(1f)) {
            when {
                !granted -> EmptyState(
                    "Permission needed",
                    "Allow access to your ${if (tool.video) "videos" else "audio files"} to browse them, or use the folder button to pick files directly.",
                    Modifier.align(Alignment.Center),
                ) { PrimaryButton("Allow access") { permLauncher.launch(permissions) } }
                ui.loading && ui.tab != PickerTab.OUTPUTS -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = AppColors.Crimson)
                ui.tab == PickerTab.FOLDERS && ui.folder == null -> LazyColumn {
                    items(ui.folders, key = { it.first }) { (folder, count) ->
                        Row(
                            Modifier.fillMaxWidth().clickable { vm.openFolder(folder) }.padding(horizontal = 20.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Rounded.Folder, null, tint = AppColors.Crimson)
                            Spacer(Modifier.width(16.dp))
                            Text(folder, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                            Text("$count", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                ui.items.isEmpty() -> EmptyState("Nothing here", "No matching files found on this device.", Modifier.align(Alignment.Center))
                else -> LazyColumn(Modifier.fillMaxSize(), state = listState) {
                    if (ui.folder != null) item {
                        Text("‹  ${ui.folder}", style = MaterialTheme.typography.titleSmall, color = AppColors.Crimson,
                            modifier = Modifier.clickable { vm.openFolder(null) }.padding(horizontal = 20.dp, vertical = 10.dp))
                    }
                    items(ui.items, key = { it.uri }) { f ->
                        val isSelected = ui.selected.any { it.uri == f.uri }
                        FileRow(
                            file = f, selected = isSelected, multi = multi,
                            playing = previewUri == f.uri && previewPlaying,
                            onPlay = { vm.preview(f) },
                            onClick = {
                                vm.toggle(f, multi)
                                if (!multi && vm.confirm(appendMode)) onPicked()
                            },
                        )
                    }
                }
            }
        }
        if (multi && ui.selected.isNotEmpty()) {
            PrimaryButton(
                "Continue (${ui.selected.size})",
                modifier = Modifier.padding(16.dp).navigationBarsPadding(),
            ) { if (vm.confirm(appendMode)) onPicked() }
        }
    }
}

@Composable
fun FileRow(file: AudioFile, selected: Boolean, multi: Boolean, playing: Boolean, onPlay: () -> Unit, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(if (selected) AppColors.CrimsonContainer.copy(alpha = 0.5f) else AppColors.Canvas)
            .clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(52.dp).clip(RoundedCornerShape(10.dp)).background(AppColors.Surface).clickable(onClick = onPlay),
            contentAlignment = Alignment.Center,
        ) { Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "Pause preview" else "Play preview", tint = AppColors.TextPrimary) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(file.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            Text("${Fmt.time(file.durationMs)} | ${Fmt.size(file.sizeBytes)} | ${file.formatLabel}", style = MaterialTheme.typography.bodySmall)
        }
        if (multi) {
            Icon(
                if (selected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                if (selected) "Selected" else "Not selected",
                tint = if (selected) AppColors.Crimson else AppColors.TextDisabled,
            )
        }
    }
}
