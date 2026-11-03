package com.fernandogodinho.teclado.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fernandogodinho.teclado.R
import com.fernandogodinho.teclado.core.ProjectInfo
import com.fernandogodinho.teclado.ui.components.Gap
import com.fernandogodinho.teclado.ui.components.SectionTitle

/** About the author and the Pix key for voluntary contributions. */
@Composable
fun SupportScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    SettingsPage("Apoie o projeto", onBack) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
            AppLogo(52)
            Spacer(Modifier.width(14.dp))
            Column {
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge)
                Text("Gratuito, sem anúncios e de código aberto", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        SectionTitle("Quem fez")
        Text(
            "Este teclado é um projeto de Fernando Godinho, apresentado no canal dele no YouTube. A ideia é simples: " +
                "qualquer pessoa deve poder escrever falando, em português impecável, sem pagar nada e sem entregar a própria voz ou o próprio texto a empresa nenhuma. " +
                "Por isso tudo roda no seu celular e o código é aberto para qualquer um conferir.",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(horizontal = 6.dp),
        )

        SectionTitle("Contribua com um Pix")
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(22.dp))
                .padding(18.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.FavoriteBorder, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Text("Qualquer valor ajuda o projeto a continuar", style = MaterialTheme.typography.titleSmall)
            }
            Gap(10)
            Text(
                "Se o teclado facilita o seu dia, você pode agradecer com um Pix de qualquer valor. É voluntário: todos os recursos continuam gratuitos para todo mundo.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Gap(14)
            Text("Chave Pix (e-mail)", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SelectionContainer {
                Text(
                    ProjectInfo.PIX_KEY,
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Gap(14)
            Button(
                onClick = { copyPixKey(context) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                shape = RoundedCornerShape(14.dp),
            ) {
                Icon(Icons.Outlined.ContentCopy, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Copiar chave Pix")
            }
            Gap(10)
            Text(
                "No app do seu banco, escolha Pix → Pagar com chave, cole a chave e confira o nome Fernando Godinho antes de confirmar.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Gap(28)
    }
}

private fun copyPixKey(context: Context) {
    val cm = context.getSystemService(ClipboardManager::class.java) ?: return
    cm.setPrimaryClip(ClipData.newPlainText("Chave Pix", ProjectInfo.PIX_KEY))
    Toast.makeText(context, "Chave Pix copiada", Toast.LENGTH_SHORT).show()
}
