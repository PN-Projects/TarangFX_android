package com.pnprojects.tarangfx.ui.outputs

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.CheckBox
import androidx.compose.material.icons.rounded.CheckBoxOutlineBlank
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.HelpOutline
import androidx.compose.material.icons.rounded.LibraryAddCheck
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pnprojects.tarangfx.data.db.OutputAudioEntity
import com.pnprojects.tarangfx.data.media.RingtoneTarget
import com.pnprojects.tarangfx.domain.model.OutputCategory
import com.pnprojects.tarangfx.ui.components.ConfirmDialog
import com.pnprojects.tarangfx.ui.components.EmptyState
import com.pnprojects.tarangfx.ui.components.Fmt
import com.pnprojects.tarangfx.ui.components.InfoDialog
import com.pnprojects.tarangfx.ui.components.SelectPill
import com.pnprojects.tarangfx.ui.components.appTextFieldColors
import com.pnprojects.tarangfx.ui.theme.AppColors
import com.pnprojects.tarangfx.ui.theme.TabularNums
import java.text.DateFormat
import java.util.Date

private val CHIPS: List<OutputCategory?> = listOf(
    null, OutputCategory.TRIM, OutputCategory.MERGE, OutputCategory.MIX, OutputCategory.DENOISE, OutputCategory.CONVERT,
    OutputCategory.FX, OutputCategory.RECORD, OutputCategory.SPLIT, OutputCategory.VIDEO_TO_AUDIO, OutputCategory.RINGTONE, OutputCategory.TAGS,
)

@Composable
fun OutputsScreen(onOpenInEditor: () -> Unit, onEditTags: () -> Unit, onSettings: () -> Unit, vm: OutputsViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val ui by vm.ui.collectAsStateWithLifecycle()
    val playingId by vm.playingId.collectAsStateWithLifecycle()
    val playing by vm.isPlaying.collectAsStateWithLifecycle()
    val position by vm.position.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val consent by vm.consent.collectAsStateWithLifecycle()
    var searching by remember { mutableStateOf(false) }
    val searchFocus = remember { FocusRequester() }
    LaunchedEffect(searching) { if (searching) searchFocus.requestFocus() }
    var sortMenu by remember { mutableStateOf(false) }
    var help by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<OutputAudioEntity?>(null) }
    var infoTarget by remember { mutableStateOf<OutputAudioEntity?>(null) }
    var ringtoneTarget by remember { mutableStateOf<OutputAudioEntity?>(null) }
    var deleteTargets by remember { mutableStateOf<List<OutputAudioEntity>>(emptyList()) }
    val consentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        vm.onConsentResult(it.resultCode == Activity.RESULT_OK)
    }
    LaunchedEffect(consent) { consent?.let { consentLauncher.launch(IntentSenderRequest.Builder(it.intentSender).build()) } }

    fun share(items: List<OutputAudioEntity>) {
        if (items.isEmpty()) return
        val intent = if (items.size == 1) {
            Intent(Intent.ACTION_SEND).setType(vm.mimeFor(items[0])).putExtra(Intent.EXTRA_STREAM, Uri.parse(items[0].contentUri))
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).setType("audio/*")
                .putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(items.map { Uri.parse(it.contentUri) }))
        }
        // Grant read access only to the receiving app, only for these URIs.
        context.startActivity(Intent.createChooser(intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share audio"))
    }

    Column(Modifier.fillMaxSize().background(AppColors.Canvas)) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().height(64.dp).padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Outputs", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            IconButton(onClick = { help = true }) { Icon(Icons.Rounded.HelpOutline, "Help", tint = AppColors.TextPrimary) }
            IconButton(onClick = onSettings) { Icon(Icons.Rounded.Settings, "Settings", tint = AppColors.TextPrimary) }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CHIPS.forEach { c -> SelectPill(c?.label ?: "All", ui.filter.category == c) { vm.setCategory(c) } }
        }
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (ui.filter.selecting) {
                Text("${ui.filter.selected.size} selected", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = vm::selectAll) { Text("All", color = AppColors.Crimson) }
                IconButton(onClick = { share(ui.items.filter { it.id in ui.filter.selected }) }, enabled = ui.filter.selected.isNotEmpty()) {
                    Icon(Icons.Rounded.Share, "Share selected", tint = AppColors.TextPrimary)
                }
                IconButton(onClick = { deleteTargets = ui.items.filter { it.id in ui.filter.selected } }, enabled = ui.filter.selected.isNotEmpty()) {
                    Icon(Icons.Rounded.DeleteOutline, "Delete selected", tint = AppColors.TextPrimary)
                }
                IconButton(onClick = vm::toggleSelecting) { Icon(Icons.Rounded.Close, "Done", tint = AppColors.TextPrimary) }
            } else {
                Text("${ui.items.size} audio${if (ui.items.size == 1) "" else "s"}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = { searching = !searching }) { Icon(Icons.Rounded.Search, "Search", tint = AppColors.TextPrimary) }
                Box {
                    IconButton(onClick = { sortMenu = true }) { Icon(Icons.AutoMirrored.Rounded.Sort, "Sort", tint = AppColors.TextPrimary) }
                    DropdownMenu(sortMenu, { sortMenu = false }, containerColor = AppColors.SurfaceVariant) {
                        OutputSort.entries.forEach { s ->
                            DropdownMenuItem(text = { Text(s.label, color = if (s == ui.filter.sort) AppColors.Crimson else AppColors.TextPrimary) },
                                onClick = { vm.setSort(s); sortMenu = false })
                        }
                    }
                }
                IconButton(onClick = vm::toggleSelecting) { Icon(Icons.Rounded.LibraryAddCheck, "Select multiple", tint = AppColors.TextPrimary) }
            }
        }
        if (searching) {
            OutlinedTextField(ui.filter.query, vm::setQuery, placeholder = { Text("Search outputs") }, singleLine = true,
                colors = appTextFieldColors(), modifier = Modifier.focusRequester(searchFocus).fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp))
        }
        if (ui.items.isEmpty()) {
            EmptyState(
                if (ui.total == 0) "No outputs yet" else "No matches",
                if (ui.total == 0) "Everything you export appears here and in Music/TarangFX." else "Try another filter or search.",
                Modifier.fillMaxWidth(),
            )
        }
        val listState = rememberLazyListState()
        // Re-sorting/filtering must show the top of the list, not keep the old first item anchored.
        LaunchedEffect(ui.filter.category, ui.filter.sort, ui.filter.query) { listState.scrollToItem(0) }
        LazyColumn(Modifier.weight(1f), state = listState) {
            items(ui.items, key = { it.id }) { e ->
                val isCurrent = e.id == playingId
                Column {
                    OutputRow(
                        e, isCurrent && playing, ui.filter.selecting, e.id in ui.filter.selected,
                        onPlay = { vm.play(e) },
                        onClick = { if (ui.filter.selecting) vm.toggleSelected(e.id) else vm.play(e) },
                        onMenu = { action ->
                            when (action) {
                                "rename" -> renameTarget = e
                                "tags" -> { vm.prepareEditor(e); onEditTags() }
                                "ringtone" -> ringtoneTarget = e
                                "editor" -> { vm.prepareEditor(e); onOpenInEditor() }
                                "share" -> share(listOf(e))
                                "info" -> infoTarget = e
                                "delete" -> deleteTargets = listOf(e)
                            }
                        },
                    )
                    if (isCurrent) {
                        // Inline mini-player scrubber
                        Slider(
                            value = if (e.durationMs > 0) position.toFloat() / e.durationMs else 0f,
                            onValueChange = { vm.seek((it * e.durationMs).toLong()) },
                            modifier = Modifier.padding(horizontal = 20.dp),
                            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = AppColors.Crimson, inactiveTrackColor = AppColors.SurfaceVariant),
                        )
                    }
                }
            }
        }
    }

    renameTarget?.let { e ->
        var name by remember(e.id) { mutableStateOf(TextFieldValue(e.title, TextRange(0, e.title.length))) }
        val renameFocus = remember { FocusRequester() }
        LaunchedEffect(e.id) { renameFocus.requestFocus() }
        AlertDialog(
            onDismissRequest = { renameTarget = null }, containerColor = AppColors.Surface, title = { Text("Rename") },
            text = { OutlinedTextField(name, { name = if (it.text.length <= 120) it else name }, singleLine = true, colors = appTextFieldColors(), modifier = Modifier.focusRequester(renameFocus)) },
            confirmButton = { TextButton(enabled = name.text.isNotBlank(), onClick = { vm.rename(e, name.text.trim()); renameTarget = null }) { Text("Rename", color = AppColors.Crimson) } },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("Cancel", color = AppColors.TextSecondary) } },
        )
    }
    infoTarget?.let { e ->
        InfoDialog(
            "File info",
            "Name: ${e.title}\nFormat: ${e.format}\nDuration: ${Fmt.time(e.durationMs)}\nSize: ${Fmt.size(e.sizeBytes)}\n" +
                "Sample rate: ${if (e.sampleRate > 0) Fmt.rate(e.sampleRate) else "—"}\nBitrate: ${if (e.bitrate > 0) "${e.bitrate} kbps" else "—"}\n" +
                "Tool: ${runCatching { OutputCategory.valueOf(e.category).label }.getOrDefault(e.category)}\n" +
                "Location: ${e.filePath}\nCreated: ${DateFormat.getDateTimeInstance().format(Date(e.createdAt))}",
        ) { infoTarget = null }
    }
    ringtoneTarget?.let { e ->
        AlertDialog(
            onDismissRequest = { ringtoneTarget = null }, containerColor = AppColors.Surface, title = { Text("Set as") },
            text = {
                Column {
                    if (!vm.canWriteSettings()) {
                        Text("First allow \"Modify system settings\" for this app.", color = AppColors.TextSecondary)
                        TextButton(onClick = { context.startActivity(vm.writeSettingsIntent()) }) { Text("Open settings", color = AppColors.Crimson) }
                    }
                    RingtoneTarget.entries.forEach { t ->
                        Text(t.label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.fillMaxWidth()
                            .clickable { vm.setRingtone(e, t); ringtoneTarget = null }.padding(vertical = 12.dp))
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { ringtoneTarget = null }) { Text("Cancel", color = AppColors.TextSecondary) } },
        )
    }
    if (deleteTargets.isNotEmpty()) {
        ConfirmDialog(
            "Delete ${deleteTargets.size} file${if (deleteTargets.size == 1) "" else "s"}?",
            "The exported audio will be permanently removed from this device.", "Delete",
            onConfirm = { vm.delete(deleteTargets); deleteTargets = emptyList() },
            onDismiss = { deleteTargets = emptyList() },
        )
    }
    if (help) InfoDialog("Outputs", "All files you exported, recorded or converted. Tap a row to play it, use ⋮ for more actions, or the checklist icon to select several files.") { help = false }
    message?.let { InfoDialog("", it) { vm.clearMessage() } }
}

@Composable
private fun OutputRow(
    e: OutputAudioEntity, playing: Boolean, selecting: Boolean, selected: Boolean,
    onPlay: () -> Unit, onClick: () -> Unit, onMenu: (String) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(52.dp).clip(RoundedCornerShape(10.dp)).background(AppColors.Surface).clickable(onClick = onPlay), contentAlignment = Alignment.Center) {
            Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "Pause" else "Play", tint = AppColors.TextPrimary)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(e.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                "${Fmt.time(e.durationMs)} | ${Fmt.size(e.sizeBytes)} | ${e.format}" + if (e.sampleRate > 0) " | ${Fmt.rateShort(e.sampleRate)}" else "",
                style = MaterialTheme.typography.bodySmall.merge(TabularNums), maxLines = 1,
            )
        }
        if (selecting) {
            Icon(if (selected) Icons.Rounded.CheckBox else Icons.Rounded.CheckBoxOutlineBlank, null, tint = if (selected) AppColors.Crimson else AppColors.TextDisabled)
        } else Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "More actions", tint = AppColors.TextSecondary) }
            DropdownMenu(menu, { menu = false }, containerColor = AppColors.SurfaceVariant) {
                listOf("rename" to "Rename", "tags" to "Edit metadata", "ringtone" to "Set as ringtone", "editor" to "Open in editor",
                    "share" to "Share", "info" to "File info", "delete" to "Delete").forEach { (key, label) ->
                    DropdownMenuItem(text = { Text(label, color = if (key == "delete") MaterialTheme.colorScheme.error else AppColors.TextPrimary) },
                        onClick = { menu = false; onMenu(key) })
                }
            }
        }
    }
}
