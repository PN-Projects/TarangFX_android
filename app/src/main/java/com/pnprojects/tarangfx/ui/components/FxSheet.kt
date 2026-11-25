package com.pnprojects.tarangfx.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pnprojects.tarangfx.domain.model.DspSettings
import com.pnprojects.tarangfx.domain.model.EqPreset
import com.pnprojects.tarangfx.ui.theme.AppColors
import com.pnprojects.tarangfx.ui.theme.AppShapes

/** FX & EQ studio as a bottom sheet, used from Trim / Merge / Mix for a single clip. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FxSheet(
    title: String,
    dsp: DspSettings,
    userPresets: List<EqPreset>,
    onChange: (DspSettings) -> Unit,
    onSavePreset: (String) -> Unit,
    onDeletePreset: (EqPreset) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss, containerColor = AppColors.Surface, shape = AppShapes.Sheet,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding()) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            FxPanel(dsp, onChange, userPresets, onSavePreset, onDeletePreset)
            Spacer(Modifier.height(24.dp))
        }
    }
}
