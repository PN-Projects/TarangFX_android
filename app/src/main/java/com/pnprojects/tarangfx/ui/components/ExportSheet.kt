package com.pnprojects.tarangfx.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.pnprojects.tarangfx.domain.model.AudioMetadata
import com.pnprojects.tarangfx.domain.model.Codec
import com.pnprojects.tarangfx.domain.model.ExportConfig
import com.pnprojects.tarangfx.ui.theme.AppColors
import com.pnprojects.tarangfx.ui.theme.AppShapes

@Composable
fun appTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = AppColors.Crimson,
    unfocusedBorderColor = AppColors.Divider,
    cursorColor = AppColors.Crimson,
    focusedLabelColor = AppColors.Crimson,
    unfocusedLabelColor = AppColors.TextSecondary,
    focusedContainerColor = AppColors.Surface,
    unfocusedContainerColor = AppColors.Surface,
)

/**
 * Export configuration bottom sheet (PRD Screen 10), shared by every tool's Save/Confirm.
 * [showName] is false for batch jobs where file names come from the sources.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportSheet(
    initial: ExportConfig,
    title: String = "Export",
    confirmLabel: String = "Save",
    showName: Boolean = true,
    onDismiss: () -> Unit,
    onConfirm: (ExportConfig) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var cfg by remember { mutableStateOf(initial) }
    ModalBottomSheet(
        onDismissRequest = onDismiss, sheetState = sheetState,
        containerColor = AppColors.Surface, shape = AppShapes.Sheet,
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding(),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(16.dp))
            if (showName) {
                OutlinedTextField(
                    value = cfg.outputName, onValueChange = { cfg = cfg.copy(outputName = it.take(120)) },
                    label = { Text("File name") }, singleLine = true, colors = appTextFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(16.dp))
            }
            ExportFormatSection(cfg) { cfg = it }
            Spacer(Modifier.height(8.dp))
            MetadataSection(cfg.metadata) { cfg = cfg.copy(metadata = it) }
            Spacer(Modifier.height(20.dp))
            PrimaryButton(confirmLabel, enabled = !showName || cfg.outputName.isNotBlank()) { onConfirm(cfg) }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
fun ExportFormatSection(cfg: ExportConfig, onChange: (ExportConfig) -> Unit) {
    SectionTitle("Format")
    ChipRow(Codec.entries, { it.label }, cfg.codec) { c ->
        onChange(cfg.copy(codec = c, bitDepth = if (c.supportsBitDepth) cfg.bitDepth else 16))
    }
    SectionTitle("Sample rate")
    ChipRow(ExportConfig.SAMPLE_RATES, { Fmt.rate(it) }, cfg.sampleRate) { onChange(cfg.copy(sampleRate = it)) }
    if (cfg.codec.lossy) {
        SectionTitle("Bitrate")
        ChipRow(ExportConfig.BITRATES, { "$it kbps" }, cfg.bitrateKbps) { onChange(cfg.copy(bitrateKbps = it)) }
        if (cfg.codec == Codec.MP3 || cfg.codec == Codec.OGG || cfg.codec == Codec.OPUS) {
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Variable bitrate (VBR)", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Switch(
                    checked = cfg.vbr, onCheckedChange = { onChange(cfg.copy(vbr = it)) },
                    colors = SwitchDefaults.colors(checkedTrackColor = AppColors.Crimson),
                )
            }
        }
    } else if (cfg.codec.supportsBitDepth) {
        SectionTitle("Bit depth")
        val depths = if (cfg.codec == Codec.WAV) ExportConfig.BIT_DEPTHS else listOf(16, 24)
        ChipRow(depths, { if (it == 32) "32-bit float" else "$it-bit" }, cfg.bitDepth) { onChange(cfg.copy(bitDepth = it)) }
    }
    SectionTitle("Channels")
    ChipRow(listOf(0, 2, 1), { when (it) { 0 -> "Original"; 2 -> "Stereo"; else -> "Mono" } }, cfg.channels) {
        onChange(cfg.copy(channels = it))
    }
}

@Composable
fun MetadataSection(md: AudioMetadata, onChange: (AudioMetadata) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Edit metadata", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, tint = AppColors.TextSecondary)
    }
    AnimatedVisibility(expanded) {
        MetadataFields(md, onChange)
    }
}

@Composable
fun MetadataFields(md: AudioMetadata, onChange: (AudioMetadata) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Field("Title", md.title) { onChange(md.copy(title = it)) }
        Field("Artist", md.artist) { onChange(md.copy(artist = it)) }
        Field("Album", md.album) { onChange(md.copy(album = it)) }
        Field("Album artist", md.albumArtist) { onChange(md.copy(albumArtist = it)) }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Field("Year", if (md.year > 0) md.year.toString() else "", Modifier.weight(1f), number = true) {
                onChange(md.copy(year = it.filter(Char::isDigit).take(4).toIntOrNull() ?: 0))
            }
            Field("Track #", if (md.trackNumber > 0) md.trackNumber.toString() else "", Modifier.weight(1f), number = true) {
                onChange(md.copy(trackNumber = it.filter(Char::isDigit).take(4).toIntOrNull() ?: 0))
            }
        }
        Field("Genre", md.genre) { onChange(md.copy(genre = it)) }
        Field("Comment", md.comment) { onChange(md.copy(comment = it)) }
    }
}

@Composable
private fun Field(label: String, value: String, modifier: Modifier = Modifier.fillMaxWidth(), number: Boolean = false, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = { onChange(it.take(200)) }, label = { Text(label) }, singleLine = true,
        colors = appTextFieldColors(), modifier = modifier,
        keyboardOptions = if (number) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
    )
}

@Composable
fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = AppColors.TextSecondary, modifier = Modifier.padding(top = 12.dp, bottom = 8.dp))
}

@Composable
fun <T> ChipRow(items: List<T>, label: (T) -> String, selected: T, onSelect: (T) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { item -> SelectPill(label(item), item == selected) { onSelect(item) } }
    }
}

/** Background helper for grouped sections. */
fun Modifier.card(): Modifier = this.background(AppColors.Surface, AppShapes.ToolCard).padding(16.dp)
