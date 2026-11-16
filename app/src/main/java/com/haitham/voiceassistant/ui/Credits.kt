package com.haitham.voiceassistant.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.haitham.voiceassistant.BuildConfig

object Credits {
    const val AUTHOR = "هيثم العريفي"
    const val PHONE = "775130005"
}

/** "برمجة وتصميم: هيثم العريفي" + phone (tap opens the dialer, nothing is called automatically). */
@Composable
fun CreditsFooter(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    Column(modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            "برمجة وتصميم: ${Credits.AUTHOR}",
            style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            "للتواصل: ${Credits.PHONE}",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable {
                try { ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Credits.PHONE}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
            }.padding(4.dp)
        )
        Text(
            "الإصدار ${BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
