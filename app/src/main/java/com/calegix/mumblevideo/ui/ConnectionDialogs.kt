package com.calegix.mumblevideo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.GppMaybe
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.calegix.mumblevideo.core.RejectReason
import java.text.DateFormat
import java.util.Date

@Composable
fun ConnectionDialogHost(
    dialog: ConnectionDialog,
    onAnswerCertificate: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    when (dialog) {
        is ConnectionDialog.TrustCertificate -> TrustCertificateDialog(dialog, onAnswerCertificate)

        is ConnectionDialog.Rejected -> AlertDialog(
            onDismissRequest = onDismiss,
            icon = { Icon(Icons.Outlined.ErrorOutline, contentDescription = null) },
            title = { Text(rejectTitle(dialog.state.reason)) },
            text = { Text(dialog.state.message.ifBlank { "The server refused the connection." }) },
            confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
        )

        is ConnectionDialog.Failed -> AlertDialog(
            onDismissRequest = onDismiss,
            icon = { Icon(Icons.Outlined.ErrorOutline, contentDescription = null) },
            title = { Text(dialog.title) },
            text = { Text(dialog.message) },
            confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
        )
    }
}

@Composable
private fun TrustCertificateDialog(dialog: ConnectionDialog.TrustCertificate, onAnswer: (Boolean) -> Unit) {
    val cert = dialog.state.certificate
    val dates = DateFormat.getDateInstance(DateFormat.MEDIUM)
    AlertDialog(
        // Back or tapping outside must not silently accept; treat it as "no".
        onDismissRequest = { onAnswer(false) },
        icon = { Icon(Icons.Outlined.GppMaybe, contentDescription = null) },
        title = { Text("Trust this server?") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${dialog.state.server} presented a certificate that could not be verified: ${cert.problem}")
                Text("Subject: ${cert.subject}", style = MaterialTheme.typography.bodySmall)
                Text("Issuer: ${cert.issuer}", style = MaterialTheme.typography.bodySmall)
                Text(
                    "Valid: ${dates.format(
                        Date(cert.notBeforeEpochMillis),
                    )} – ${dates.format(Date(cert.notAfterEpochMillis))}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text("SHA-256", style = MaterialTheme.typography.labelMedium)
                Text(
                    cert.sha256.chunked(2).joinToString(":"),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
                Text(
                    "Most Mumble servers use self-signed certificates. Only continue if you expected this one; " +
                        "it will be remembered.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onAnswer(true) }) { Text("Trust and connect") } },
        dismissButton = { TextButton(onClick = { onAnswer(false) }) { Text("Cancel") } },
    )
}

private fun rejectTitle(reason: RejectReason): String = when (reason) {
    RejectReason.WrongUserPassword, RejectReason.WrongServerPassword -> "Wrong password"
    RejectReason.UsernameInUse -> "Username in use"
    RejectReason.InvalidUsername -> "Invalid username"
    RejectReason.ServerFull -> "Server full"
    RejectReason.NoCertificate -> "Certificate required"
    RejectReason.WrongVersion -> "Incompatible version"
    RejectReason.AuthenticatorFail, RejectReason.None -> "Connection rejected"
}
