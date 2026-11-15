package com.snapgpt.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.snapgpt.backup.BackupCrypto
import com.snapgpt.backup.BackupException
import com.snapgpt.backup.ConfigPayload
import com.snapgpt.backup.ImportMode
import com.snapgpt.backup.ImportPreview
import com.snapgpt.container
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

const val BACKUP_FILE_NAME = "SnapGPT_API_Backup.snapgpt"

private sealed interface Step {
    data object None : Step
    data object ExportPassword : Step
    data object ImportSource : Step
    data object ImportPassword : Step
    data class Choose(val payload: ConfigPayload, val preview: ImportPreview, val fromInternal: Boolean) : Step
    data class ConfirmReplace(val payload: ConfigPayload, val preview: ImportPreview) : Step
    data object Working : Step
}

/**
 * Settings content for exporting and importing the API configuration. The password is held in memory only for the
 * duration of the operation and is wiped afterwards. Nothing is sent anywhere: the file goes where the user picks it.
 */
@Composable
fun BackupSection(onMessage: (String) -> Unit) {
    val ctx = LocalContext.current
    val backup = remember { ctx.container.backup }
    val scope = rememberCoroutineScope()

    var step by remember { mutableStateOf<Step>(Step.None) }
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf<String?>(null) }
    var pendingUri by remember { mutableStateOf<android.net.Uri?>(null) }

    fun reset() { password = ""; confirmation = ""; problem = null; pendingUri = null; step = Step.None }

    fun finish(msg: String) { reset(); onMessage(msg) }

    // ---- file pickers (Storage Access Framework: no storage permission needed)
    val createDoc = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri == null) { reset(); return@rememberLauncherForActivityResult }
        val pw = password.toCharArray()
        step = Step.Working
        scope.launch {
            try {
                val n = backup.exportTo(uri, pw)
                finish("Backup saved with $n account${if (n == 1) "" else "s"}. Keep the password safe: it cannot be recovered.")
            } catch (e: Exception) {
                finish("Backup failed: ${e.message ?: "unknown error"}")
            } finally {
                pw.fill('\u0000')
            }
        }
    }

    val openDoc = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) { reset(); return@rememberLauncherForActivityResult }
        scope.launch {
            try {
                backup.checkFile(uri)
                pendingUri = uri
                password = ""
                step = Step.ImportPassword
            } catch (e: BackupException) {
                finish(e.message ?: "That file cannot be imported.")
            } catch (e: Exception) {
                finish("That file could not be read.")
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "Your API accounts stay on this device, encrypted. Export one password-protected file to restore them after reinstalling the app or on another phone. Nothing is uploaded anywhere.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = { password = ""; confirmation = ""; problem = null; step = Step.ExportPassword }, modifier = Modifier.fillMaxWidth()) {
            Text("Export API configuration")
        }
        OutlinedButton(
            onClick = {
                scope.launch {
                    problem = null
                    if (backup.internalBackup()?.accounts?.isNotEmpty() == true) step = Step.ImportSource else openDoc.launch(arrayOf("*/*"))
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Import API configuration") }
    }

    when (val s = step) {
        Step.None, Step.Working -> if (s == Step.Working) {
            AlertDialog(onDismissRequest = {}, confirmButton = {}, title = { Text("Working…") }, text = { Text("Encrypting or decrypting. This takes a moment on purpose.") })
        }

        Step.ExportPassword -> AlertDialog(
            onDismissRequest = { reset() },
            title = { Text("Protect the backup") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Choose a password of at least ${BackupCrypto.MIN_PASSWORD_LENGTH} characters. You will need it to import the file. It is never saved and cannot be recovered.", style = MaterialTheme.typography.bodySmall)
                    PasswordField("Password", password) { password = it; problem = null }
                    PasswordField("Repeat password", confirmation) { confirmation = it; problem = null }
                    problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val p = BackupCrypto.passwordProblem(password.toCharArray(), confirmation.toCharArray())
                    if (p != null) problem = p else createDoc.launch(BACKUP_FILE_NAME)
                }) { Text("Choose location") }
            },
            dismissButton = { TextButton(onClick = { reset() }) { Text("Cancel") } },
        )

        Step.ImportSource -> AlertDialog(
            onDismissRequest = { reset() },
            title = { Text("Import API configuration") },
            text = {
                val t = backup.internalBackupTime().takeIf { it > 0 }?.let { DateFormat.getDateTimeInstance().format(Date(it)) }
                Text("This device has an automatic internal backup${t?.let { " from $it" } ?: ""}. Use it, or choose a backup file you exported earlier.")
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        val payload = backup.internalBackup()
                        if (payload == null) finish("The internal backup could not be opened.")
                        else step = Step.Choose(payload, backup.preview(payload), fromInternal = true)
                    }
                }) { Text("Use internal backup") }
            },
            dismissButton = {
                Column {
                    TextButton(onClick = { openDoc.launch(arrayOf("*/*")) }) { Text("Choose file…") }
                    TextButton(onClick = { reset() }) { Text("Cancel") }
                }
            },
        )

        Step.ImportPassword -> AlertDialog(
            onDismissRequest = { reset() },
            title = { Text("Backup password") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PasswordField("Password", password) { password = it; problem = null }
                    problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = {
                TextButton(enabled = password.isNotEmpty(), onClick = {
                    val uri = pendingUri ?: return@TextButton
                    val pw = password.toCharArray()
                    step = Step.Working
                    scope.launch {
                        try {
                            val payload = backup.readFile(uri, pw)
                            step = Step.Choose(payload, backup.preview(payload), fromInternal = false)
                        } catch (e: BackupException) {
                            problem = e.message
                            step = Step.ImportPassword
                        } catch (e: Exception) {
                            problem = "The backup could not be read."
                            step = Step.ImportPassword
                        } finally {
                            pw.fill('\u0000')
                        }
                    }
                }) { Text("Decrypt") }
            },
            dismissButton = { TextButton(onClick = { reset() }) { Text("Cancel") } },
        )

        is Step.Choose -> {
            val p = s.preview
            AlertDialog(
                onDismissRequest = { reset() },
                title = { Text("Restore ${p.total} account${if (p.total == 1) "" else "s"}?") },
                text = {
                    Text(
                        "${p.brandNew} new, ${p.alreadyPresent} already on this device.\n\n" +
                            "Merge keeps everything you have now and adds the missing accounts.\n" +
                            "Replace makes this device match the backup" + if (p.wouldBeRemovedByReplace > 0) " and removes ${p.wouldBeRemovedByReplace} account${if (p.wouldBeRemovedByReplace == 1) "" else "s"} that ${if (p.wouldBeRemovedByReplace == 1) "is" else "are"} not in it." else ".",
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        step = Step.Working
                        scope.launch {
                            try {
                                val r = backup.apply(s.payload, ImportMode.MERGE, applySettings = !s.fromInternal)
                                finish("Merged: ${r.added} added, ${r.kept} already present.")
                            } catch (e: Exception) { finish("Import failed: ${e.message ?: "unknown error"}") }
                        }
                    }) { Text("Merge") }
                },
                dismissButton = {
                    Column {
                        TextButton(onClick = {
                            if (p.wouldBeRemovedByReplace > 0) step = Step.ConfirmReplace(s.payload, p)
                            else {
                                step = Step.Working
                                scope.launch {
                                    try {
                                        backup.apply(s.payload, ImportMode.REPLACE, applySettings = !s.fromInternal)
                                        finish("Restored ${p.total} account${if (p.total == 1) "" else "s"}.")
                                    } catch (e: Exception) { finish("Import failed: ${e.message ?: "unknown error"}") }
                                }
                            }
                        }) { Text("Replace") }
                        TextButton(onClick = { reset() }) { Text("Cancel") }
                    }
                },
            )
        }

        is Step.ConfirmReplace -> AlertDialog(
            onDismissRequest = { reset() },
            title = { Text("Replace and remove ${s.preview.wouldBeRemovedByReplace} account(s)?") },
            text = { Text("Accounts on this device that are not in the backup will be deleted together with their stored keys. A copy of the current setup is kept as the previous internal backup.") },
            confirmButton = {
                TextButton(onClick = {
                    step = Step.Working
                    scope.launch {
                        try {
                            backup.apply(s.payload, ImportMode.REPLACE, applySettings = true)
                            finish("Replaced. This device now matches the backup.")
                        } catch (e: Exception) { finish("Import failed: ${e.message ?: "unknown error"}") }
                    }
                }) { Text("Replace") }
            },
            dismissButton = { TextButton(onClick = { reset() }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun PasswordField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
    )
}
