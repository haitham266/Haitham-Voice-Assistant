package com.haitham.voiceassistant.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.haitham.voiceassistant.R
import com.haitham.voiceassistant.model.AssistantStatus
import com.haitham.voiceassistant.model.AssistantUiState
import com.haitham.voiceassistant.ui.theme.Accent
import com.haitham.voiceassistant.ui.theme.AccentDeep
import com.haitham.voiceassistant.ui.theme.Danger
import com.haitham.voiceassistant.ui.theme.Listening
import com.haitham.voiceassistant.ui.theme.Warning

@Composable
fun MainScreen(
    state: AssistantUiState,
    wakePhrase: String,
    missingPermissions: List<PermissionItem>,
    onToggle: () -> Unit,
    onGrantPermissions: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Header
        Row(Modifier.fillMaxWidth().widthIn(max = 640.dp), verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(R.drawable.ic_logo), contentDescription = null, modifier = Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)))
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Haitham Voice Assistant", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.WifiOff, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.size(4.dp))
                    Text("مساعد عربي يعمل دون إنترنت", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            IconButton(onClick = onOpenSettings) { Icon(Icons.Filled.Settings, contentDescription = "الإعدادات") }
        }

        Spacer(Modifier.height(28.dp))
        MicCircle(state, onToggle)
        Spacer(Modifier.height(20.dp))
        StatusChip(state.status)
        Spacer(Modifier.height(14.dp))

        // Microphone level
        Column(Modifier.fillMaxWidth().widthIn(max = 520.dp)) {
            Text("مستوى الصوت", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            val lvl by animateFloatAsState(if (state.serviceRunning) state.level else 0f, tween(120), label = "lvl")
            LinearProgressIndicator(
                progress = { lvl }, modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                color = if (state.status == AssistantStatus.LISTENING_COMMAND) Listening else Accent,
                trackColor = MaterialTheme.colorScheme.surfaceVariant, drawStopIndicator = {}
            )
        }

        Spacer(Modifier.height(18.dp))
        Button(
            onClick = onToggle,
            modifier = Modifier.fillMaxWidth().widthIn(max = 520.dp).height(54.dp),
            colors = ButtonDefaults.buttonColors(containerColor = if (state.serviceRunning) Danger else Accent),
            shape = RoundedCornerShape(16.dp)
        ) {
            Icon(if (state.serviceRunning) Icons.Filled.MicOff else Icons.Filled.Mic, null)
            Spacer(Modifier.size(8.dp))
            Text(if (state.serviceRunning) "إيقاف المساعد" else "تشغيل المساعد", style = MaterialTheme.typography.labelLarge)
        }

        if (missingPermissions.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            InfoCard("أذونات مطلوبة", missingPermissions.joinToString("، ") { it.title }, Warning, actionLabel = "منح الأذونات", onAction = onGrantPermissions)
        }
        state.error?.let { Spacer(Modifier.height(14.dp)); InfoCard("خطأ", it, Danger) }

        Spacer(Modifier.height(14.dp))
        InfoCard("آخر أمر صوتي", state.lastHeard.ifBlank { "—" }, Accent)
        Spacer(Modifier.height(10.dp))
        InfoCard("نتيجة التنفيذ", state.lastResult.ifBlank { "—" }, Listening)
        Spacer(Modifier.height(10.dp))
        InfoCard(
            "جرّب أن تقول",
            "«$wakePhrase» ثم:\n• اتصل بهيثم العريفي\n• دق على أحمد\n• اتصل بالرقم سبعة سبعة سبعة…\n• أعد الاتصال بآخر رقم\n• أوقف الاستماع",
            MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (state.modelInfo.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            Text("النموذج: ${state.modelInfo}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(16.dp))
        CreditsFooter()
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun MicCircle(state: AssistantUiState, onToggle: () -> Unit) {
    val active = state.serviceRunning
    val listening = state.status == AssistantStatus.LISTENING_COMMAND || state.status == AssistantStatus.AWAITING_CHOICE
    val color by animateColorAsState(
        when {
            !active -> Color(0xFF39425E)
            listening -> Listening
            state.status == AssistantStatus.PROCESSING -> Warning
            state.status == AssistantStatus.ERROR || state.status == AssistantStatus.MIC_BLOCKED -> Danger
            else -> Accent
        }, label = "c"
    )
    val pulse = rememberInfiniteTransition(label = "p")
    val breathe by pulse.animateFloat(1f, if (active) 1.08f else 1f, infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "b")
    val levelScale by animateFloatAsState(1f + (if (active) state.level else 0f) * 0.35f, tween(100), label = "l")

    Box(Modifier.size(260.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(250.dp).scale(levelScale).clip(CircleShape).background(color.copy(alpha = 0.10f)))
        Box(Modifier.size(205.dp).scale(breathe).clip(CircleShape).background(color.copy(alpha = 0.18f)))
        Box(
            Modifier.size(160.dp).clip(CircleShape)
                .background(Brush.linearGradient(if (active) listOf(color, AccentDeep) else listOf(Color(0xFF2A3150), Color(0xFF1A2038))))
                .border(2.dp, color.copy(alpha = 0.7f), CircleShape)
                .clickable(onClick = onToggle),
            contentAlignment = Alignment.Center
        ) {
            Icon(if (active) Icons.Filled.Mic else Icons.Filled.MicOff, contentDescription = "الميكروفون", tint = Color.White, modifier = Modifier.size(72.dp))
        }
    }
}

@Composable
private fun StatusChip(status: AssistantStatus) {
    val c = when (status) {
        AssistantStatus.STOPPED -> MaterialTheme.colorScheme.onSurfaceVariant
        AssistantStatus.LISTENING_COMMAND, AssistantStatus.AWAITING_CHOICE -> Listening
        AssistantStatus.PROCESSING, AssistantStatus.LOADING_MODEL, AssistantStatus.PAUSED_IN_CALL -> Warning
        AssistantStatus.ERROR, AssistantStatus.MIC_BLOCKED -> Danger
        AssistantStatus.WAITING_WAKE_WORD, AssistantStatus.WAITING_DIRECT_COMMAND -> Accent
    }
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(c.copy(alpha = 0.14f)).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(c))
        Spacer(Modifier.size(8.dp))
        Text(status.label, color = c, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun InfoCard(title: String, body: String, accent: Color, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    Card(
        Modifier.fillMaxWidth().widthIn(max = 640.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(width = 4.dp, height = 16.dp).clip(RoundedCornerShape(2.dp)).background(accent))
                Spacer(Modifier.size(8.dp))
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            Text(body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            if (actionLabel != null && onAction != null) {
                Button(onClick = onAction, shape = RoundedCornerShape(12.dp)) { Text(actionLabel) }
            }
        }
    }
}
