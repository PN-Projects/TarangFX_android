package com.pnprojects.tarangfx.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.pnprojects.tarangfx.audio.JobState
import com.pnprojects.tarangfx.audio.JobStatus
import com.pnprojects.tarangfx.ui.theme.AppColors

/** Shows progress for a batch of export jobs; becomes a result summary when all finish. */
@Composable
fun ExportProgressDialog(
    statuses: List<JobStatus>,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
    onOpenOutputs: () -> Unit,
) {
    if (statuses.isEmpty()) return
    val active = statuses.any { it.state == JobState.QUEUED || it.state == JobState.RUNNING }
    val done = statuses.count { it.state == JobState.DONE }
    val failed = statuses.filter { it.state == JobState.FAILED }
    AlertDialog(
        onDismissRequest = { if (!active) onDismiss() },
        properties = DialogProperties(dismissOnClickOutside = false),
        containerColor = AppColors.Surface,
        title = {
            Text(
                when {
                    active -> "Processing…"
                    failed.isEmpty() && done > 0 -> "Saved"
                    done == 0 && failed.isEmpty() -> "Cancelled"
                    else -> "Finished with errors"
                },
                style = MaterialTheme.typography.titleLarge,
            )
        },
        text = {
            LazyColumn(Modifier.heightIn(max = 320.dp)) {
                items(statuses, key = { it.job.id }) { s ->
                    Column(Modifier.fillMaxWidth()) {
                        Row {
                            Text(s.job.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1,
                                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Text(
                                when (s.state) {
                                    JobState.QUEUED -> "Queued"
                                    JobState.RUNNING -> "${(s.progress * 100).toInt()}%"
                                    JobState.DONE -> "Done"
                                    JobState.FAILED -> "Failed"
                                    JobState.CANCELLED -> "Cancelled"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (s.state == JobState.FAILED) MaterialTheme.colorScheme.error else AppColors.TextSecondary,
                            )
                        }
                        if (s.state == JobState.RUNNING || s.state == JobState.QUEUED) {
                            Spacer(Modifier.height(6.dp))
                            LinearProgressIndicator(
                                progress = { s.progress }, modifier = Modifier.fillMaxWidth(),
                                color = AppColors.Crimson, trackColor = AppColors.SurfaceVariant,
                            )
                        }
                        s.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                        Spacer(Modifier.height(10.dp))
                    }
                }
            }
        },
        confirmButton = {
            if (active) TextButton(onClick = onCancel) { Text("Cancel", color = AppColors.Crimson) }
            else if (done > 0) TextButton(onClick = onOpenOutputs) { Text("View in Outputs", color = AppColors.Crimson) }
        },
        dismissButton = {
            if (!active) TextButton(onClick = onDismiss) { Text("Close", color = AppColors.TextSecondary) }
        },
    )
}
