package com.pnprojects.tarangfx.ui.metadata

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.pnprojects.tarangfx.audio.ExportJob
import com.pnprojects.tarangfx.audio.ExportManager
import com.pnprojects.tarangfx.audio.PlaybackController
import com.pnprojects.tarangfx.audio.SelectionHolder
import com.pnprojects.tarangfx.data.media.SourceRegistry
import com.pnprojects.tarangfx.domain.model.AudioFile
import com.pnprojects.tarangfx.domain.model.AudioMetadata
import com.pnprojects.tarangfx.nativebridge.NativeAudio
import com.pnprojects.tarangfx.ui.EditorViewModel
import com.pnprojects.tarangfx.ui.components.EmptyState
import com.pnprojects.tarangfx.ui.components.ExportProgressDialog
import com.pnprojects.tarangfx.ui.components.MetadataFields
import com.pnprojects.tarangfx.ui.components.PrimaryButton
import com.pnprojects.tarangfx.ui.components.ScreenTopBar
import com.pnprojects.tarangfx.ui.components.SelectPill
import com.pnprojects.tarangfx.ui.theme.AppColors
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import kotlin.math.min

data class TagUi(
    val file: AudioFile? = null,
    val loading: Boolean = true,
    val metadata: AudioMetadata = AudioMetadata(),
    val cover: ByteArray? = null,
    val coverMode: Int = 0,  // 0 keep, 1 replace, 2 remove
    val supported: Boolean = true,
)

private val TAGGABLE = setOf("mp3", "m4a", "mp4", "flac", "ogg", "opus", "wav")

@HiltViewModel
class TagEditorViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    selection: SelectionHolder,
    private val sources: SourceRegistry,
    playback: PlaybackController,
    exports: ExportManager,
) : EditorViewModel(playback, exports) {
    private val _ui = MutableStateFlow(TagUi())
    val ui: StateFlow<TagUi> = _ui.asStateFlow()
    private val json = Json { ignoreUnknownKeys = true }

    init {
        val f = selection.peek().firstOrNull()
        if (f == null) _ui.value = TagUi(loading = false)
        else {
            _ui.value = TagUi(file = f, supported = f.extension in TAGGABLE)
            viewModelScope.launch {
                val (md, cover) = withContext(Dispatchers.IO) {
                    val src = sources.resolve(f.uri)
                    val tags = src?.let { NativeAudio.readTags(it, f.displayName) }
                        ?.let { runCatching { json.decodeFromString(AudioMetadata.serializer(), it) }.getOrNull() }
                    val c = src?.let { NativeAudio.readCover(it, f.displayName) }
                    (tags ?: AudioMetadata(title = f.title, artist = f.artist.orEmpty())) to c
                }
                _ui.update { it.copy(loading = false, metadata = md.copy(title = md.title.ifBlank { f.title }), cover = cover) }
            }
        }
    }

    fun setMetadata(md: AudioMetadata) = _ui.update { it.copy(metadata = md) }
    fun removeCover() = _ui.update { it.copy(cover = null, coverMode = 2) }

    /** Center-crops to a square and downsizes to ≤ 800 px JPEG (keeps tags small). */
    fun setCover(uri: Uri) {
        viewModelScope.launch {
            val bytes = withContext(Dispatchers.IO) {
                runCatching {
                    val bmp: Bitmap = if (Build.VERSION.SDK_INT >= 28) {
                        ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { d, _, _ ->
                            d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                        }
                    } else context.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it) }
                    val side = min(bmp.width, bmp.height)
                    val square = Bitmap.createBitmap(bmp, (bmp.width - side) / 2, (bmp.height - side) / 2, side, side)
                    val target = min(side, 800)
                    val scaled = Bitmap.createScaledBitmap(square, target, target, true)
                    ByteArrayOutputStream().use { out -> scaled.compress(Bitmap.CompressFormat.JPEG, 90, out); out.toByteArray() }
                }.getOrNull()
            }
            if (bytes != null) _ui.update { it.copy(cover = bytes, coverMode = 1) }
        }
    }

    fun save() {
        val s = _ui.value
        val f = s.file ?: return
        val ext = if (f.extension == "mp4") "m4a" else f.extension
        startExport(listOf(ExportJob.Retag(
            title = s.metadata.title.ifBlank { f.title }, sourceUri = f.uri, extension = ext,
            metadata = s.metadata, cover = if (s.coverMode == 1) s.cover else null, coverMime = "image/jpeg", coverMode = s.coverMode,
        )))
    }
}

@Composable
fun TagEditorScreen(onBack: () -> Unit, onOpenOutputs: () -> Unit, vm: TagEditorViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val statuses by vm.exportStatuses.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(vm::setCover) }
    Column(Modifier.fillMaxSize().background(AppColors.Canvas)) {
        ScreenTopBar("Tag Editor", onBack,
            helpText = "Edit title, artist, album, year, track, genre, comment and cover art. Your original is never modified: a tagged copy is saved to Outputs.")
        when {
            ui.file == null -> EmptyState("No audio selected", "", Modifier.fillMaxSize())
            ui.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = AppColors.Crimson) }
            !ui.supported -> EmptyState("Format not supported", "Tags can be edited in MP3, M4A, FLAC, OGG, OPUS and WAV files. Convert this file first.", Modifier.fillMaxSize())
            else -> {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val bitmap = remember(ui.cover) { ui.cover?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() } }
                        Box(
                            Modifier.size(120.dp).clip(RoundedCornerShape(16.dp)).background(AppColors.Surface)
                                .clickable { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (bitmap != null) Image(bitmap, "Cover art", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            else Icon(Icons.Rounded.AddPhotoAlternate, "Choose cover art", tint = AppColors.TextSecondary, modifier = Modifier.size(40.dp))
                        }
                        Spacer(Modifier.width(16.dp))
                        Column {
                            Text("Cover art", style = MaterialTheme.typography.titleSmall)
                            Spacer(Modifier.height(8.dp))
                            SelectPill("Choose image", false) { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                            Spacer(Modifier.height(8.dp))
                            SelectPill("Remove", false, enabled = ui.cover != null) { vm.removeCover() }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    MetadataFields(ui.metadata, vm::setMetadata)
                    Spacer(Modifier.height(16.dp))
                }
                PrimaryButton("Save tagged copy", modifier = Modifier.padding(16.dp).navigationBarsPadding()) { vm.save() }
            }
        }
    }
    ExportProgressDialog(statuses, vm::cancelExport, vm::dismissExport) { vm.dismissExport(); onOpenOutputs() }
}
