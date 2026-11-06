package com.nat.autoanswer

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Voicemail
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private val Ink = Color(0xFF0E1512)
private val Surface1 = Color(0xFF16201C)
private val Surface2 = Color(0xFF1D2B25)
private val Accent = Color(0xFF3DDC97)
private val Muted = Color(0xFF8DA399)

private val Scheme = darkColorScheme(
    primary = Accent,
    onPrimary = Color(0xFF003824),
    background = Ink,
    onBackground = Color(0xFFE6F0EB),
    surface = Surface1,
    onSurface = Color(0xFFE6F0EB),
    surfaceVariant = Surface2,
    onSurfaceVariant = Muted,
    outline = Color(0xFF2E4038),
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = Scheme) {
                AppRoot()
            }
        }
    }
}

private val ESSENTIAL = arrayOf(
    Manifest.permission.READ_PHONE_STATE,
    Manifest.permission.ANSWER_PHONE_CALLS,
    Manifest.permission.RECORD_AUDIO,
)

private fun requestList(): Array<String> {
    val list = ESSENTIAL.toMutableList()
    list += Manifest.permission.READ_CALL_LOG
    if (Build.VERSION.SDK_INT >= 33) list += Manifest.permission.POST_NOTIFICATIONS
    return list.toTypedArray()
}

private fun essentialsGranted(c: Context) = ESSENTIAL.all {
    ContextCompat.checkSelfPermission(c, it) == PackageManager.PERMISSION_GRANTED
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppRoot() {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    val store = remember { MessageStore(context) }
    val serviceRunning by AnswerService.running.collectAsState()
    val version by AnswerService.messagesVersion.collectAsState()

    var tab by remember { mutableStateOf(0) }
    var enabled by remember { mutableStateOf(prefs.enabled && essentialsGranted(context)) }
    var permissionNote by remember { mutableStateOf(false) }
    var messages by remember { mutableStateOf(store.all()) }

    LaunchedEffect(version) { messages = store.all() }
    LaunchedEffect(Unit) {
        // Re-attach the service after the app was killed while enabled.
        if (enabled && !serviceRunning) AnswerService.start(context)
    }

    fun turnOn() {
        prefs.enabled = true
        enabled = true
        permissionNote = false
        AnswerService.start(context)
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (essentialsGranted(context)) turnOn() else permissionNote = true
    }

    fun toggle(on: Boolean) {
        if (!on) {
            prefs.enabled = false
            enabled = false
            AnswerService.stop(context)
        } else if (essentialsGranted(context)) {
            turnOn()
        } else {
            launcher.launch(requestList())
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = Surface1) {
                NavigationBarItem(
                    selected = tab == 0, onClick = { tab = 0 },
                    icon = { Icon(Icons.Filled.Inbox, null) },
                    label = {
                        val unread = messages.count { !it.listened }
                        Text(if (unread > 0) "Inbox ($unread)" else "Inbox")
                    },
                )
                NavigationBarItem(
                    selected = tab == 1, onClick = { tab = 1 },
                    icon = { Icon(Icons.Filled.Settings, null) },
                    label = { Text("Settings") },
                )
            }
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(horizontal = 20.dp)
        ) {
            Spacer(Modifier.height(20.dp))
            StatusCard(enabled = enabled, running = serviceRunning, delay = prefs.ringDelaySec, onToggle = ::toggle)
            if (permissionNote) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "Phone, call answering and microphone permissions are required. " +
                        "Grant them in system settings, then try again.",
                    color = Color(0xFFFFB4A8), fontSize = 13.sp,
                )
            }
            Spacer(Modifier.height(18.dp))
            if (tab == 0) {
                Inbox(messages, store) { messages = store.all() }
            } else {
                SettingsPane(prefs)
            }
        }
    }
}

@Composable
private fun StatusCard(enabled: Boolean, running: Boolean, delay: Int, onToggle: (Boolean) -> Unit) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = if (enabled) Color(0xFF14382B) else Surface1),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(if (enabled) Accent else Surface2),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Voicemail, null,
                    tint = if (enabled) Color(0xFF003824) else Muted,
                    modifier = Modifier.size(28.dp),
                )
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (enabled) "Answering machine on" else "Answering machine off",
                    fontWeight = FontWeight.SemiBold, fontSize = 18.sp,
                )
                Text(
                    if (enabled) {
                        if (running) "Picks up after $delay seconds of ringing" else "Starting…"
                    } else "Calls ring normally",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp,
                )
            }
            Switch(
                checked = enabled, onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(checkedTrackColor = Accent, checkedThumbColor = Ink),
            )
        }
    }
}

// ---- Inbox ---------------------------------------------------------------------------

@Composable
private fun Inbox(messages: List<VoiceMessage>, store: MessageStore, onChanged: () -> Unit) {
    val context = LocalContext.current
    var playingId by remember { mutableStateOf<String?>(null) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }

    fun stopPlayback() {
        try { player?.stop() } catch (_: Exception) {}
        player?.release()
        player = null
        playingId = null
    }

    DisposableEffect(Unit) { onDispose { stopPlayback() } }

    if (messages.isEmpty()) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(bottom = 80.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Filled.Voicemail, null, tint = Muted, modifier = Modifier.size(44.dp))
            Spacer(Modifier.height(12.dp))
            Text("No messages yet", fontWeight = FontWeight.Medium, fontSize = 16.sp)
            Text(
                "Voice messages from callers will appear here.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp,
            )
        }
        return
    }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(messages, key = { it.id }) { m ->
            val isPlaying = playingId == m.id
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = Surface1),
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = {
                            if (isPlaying) {
                                stopPlayback()
                            } else {
                                stopPlayback()
                                try {
                                    val mp = MediaPlayer()
                                    mp.setDataSource(store.audioFile(m.fileName).absolutePath)
                                    mp.prepare()
                                    mp.setOnCompletionListener { stopPlayback() }
                                    mp.start()
                                    player = mp
                                    playingId = m.id
                                    store.markListened(m.id)
                                    onChanged()
                                } catch (_: Exception) {
                                    stopPlayback()
                                }
                            }
                        },
                        modifier = Modifier
                            .size(46.dp)
                            .clip(CircleShape)
                            .background(Accent),
                    ) {
                        Icon(
                            if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            null, tint = Color(0xFF003824),
                        )
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                m.number, fontWeight = if (m.listened) FontWeight.Normal else FontWeight.Bold,
                                fontSize = 16.sp,
                            )
                            if (!m.listened) {
                                Spacer(Modifier.width(8.dp))
                                Box(
                                    Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(Accent)
                                )
                            }
                        }
                        Text(
                            SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(m.timestamp)) +
                                "  ·  " + formatDuration(m.durationMs),
                            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp,
                        )
                    }
                    IconButton(onClick = {
                        if (playingId == m.id) stopPlayback()
                        store.delete(m.id)
                        onChanged()
                    }) {
                        Icon(Icons.Filled.Delete, "Delete", tint = Muted)
                    }
                }
            }
        }
    }
}

private fun formatDuration(ms: Long): String {
    val s = (ms / 1000).toInt()
    return "%d:%02d".format(s / 60, s % 60)
}

// ---- Settings ------------------------------------------------------------------------

@Composable
private fun SettingsPane(prefs: Prefs) {
    val context = LocalContext.current
    var delay by remember { mutableStateOf(prefs.ringDelaySec.toFloat()) }
    var maxLen by remember { mutableStateOf(prefs.maxMessageSec.toFloat()) }
    var greeting by remember { mutableStateOf(prefs.greeting) }
    var silence by remember { mutableStateOf(prefs.hangUpOnSilence) }

    var ttsReady by remember { mutableStateOf(false) }
    val tts = remember {
        TextToSpeech(context) { status -> if (status == TextToSpeech.SUCCESS) ttsReady = true }
    }
    DisposableEffect(Unit) { onDispose { tts.shutdown() } }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item {
            SettingBlock("Answer after", "${delay.roundToInt()} seconds of ringing") {
                Slider(
                    value = delay, onValueChange = { delay = it },
                    onValueChangeFinished = { prefs.ringDelaySec = delay.roundToInt() },
                    valueRange = 5f..40f, steps = 6,
                )
            }
        }
        item {
            SettingBlock("Maximum message length", "${maxLen.roundToInt()} seconds") {
                Slider(
                    value = maxLen, onValueChange = { maxLen = it },
                    onValueChangeFinished = { prefs.maxMessageSec = maxLen.roundToInt() },
                    valueRange = 15f..180f,
                )
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Hang up when caller stops talking", fontWeight = FontWeight.Medium)
                    Text(
                        "Ends the call after 5 seconds of silence",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp,
                    )
                }
                Switch(
                    checked = silence,
                    onCheckedChange = { silence = it; prefs.hangUpOnSilence = it },
                    colors = SwitchDefaults.colors(checkedTrackColor = Accent, checkedThumbColor = Ink),
                )
            }
        }
        item {
            Column {
                Text("Greeting", fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = greeting,
                    onValueChange = { greeting = it; prefs.greeting = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = {
                            if (ttsReady) tts.speak(greeting, TextToSpeech.QUEUE_FLUSH, null, "preview")
                        },
                    ) { Text("Preview greeting") }
                    OutlinedButton(onClick = {
                        greeting = Prefs.DEFAULT_GREETING
                        prefs.greeting = greeting
                    }) { Text("Reset") }
                }
            }
        }
        item {
            Text(
                "Tip: for reliable answering, exclude this app from battery optimization in system settings, " +
                    "and reopen it once after restarting your phone.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SettingBlock(title: String, value: String, content: @Composable () -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(value, color = Accent, fontSize = 14.sp)
        }
        content()
    }
}
