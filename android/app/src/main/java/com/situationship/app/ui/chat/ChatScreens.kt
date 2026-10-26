package com.situationship.app.ui.chat

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situationship.app.data.local.MessageEntity
import com.situationship.app.data.model.MatchSummary
import com.situationship.app.data.repo.ChatRepository
import com.situationship.app.ui.RootEntryPoint
import com.situationship.app.ui.components.*
import com.situationship.app.ui.theme.Brand
import com.situationship.app.ui.theme.BrandGradient
import com.situationship.app.ui.theme.Online
import dagger.hilt.android.EntryPointAccessors
import androidx.compose.ui.platform.LocalContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// ------------------------------------------------------------------------------- Chats list

@Composable
fun ChatsScreen(onOpen: (String) -> Unit, onDiscover: () -> Unit) {
    val ctx = LocalContext.current
    val chat: ChatRepository = remember { EntryPointAccessors.fromApplication(ctx.applicationContext, RootEntryPoint::class.java).chat() }
    val matches by chat.matches.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { chat.refreshMatches() }

    Column(Modifier.fillMaxSize()) {
        Text("Chats", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.statusBarsPadding().padding(20.dp, 14.dp))
        if (matches.isEmpty()) {
            EmptyState("💬", "No matches yet", "Chats unlock when you and someone both like each other.") {
                GradientButton("Start swiping", onClick = onDiscover, modifier = Modifier.width(200.dp))
            }
            return@Column
        }
        val fresh = matches.filter { it.lastMessage == null }
        val convs = matches.filter { it.lastMessage != null }
        LazyColumn(Modifier.fillMaxSize()) {
            if (fresh.isNotEmpty()) {
                item { SectionTitle("New matches") }
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        items(fresh, key = { it.matchId }) { m ->
                            Column(Modifier.width(72.dp).clickable { onOpen(m.matchId) }, horizontalAlignment = Alignment.CenterHorizontally) {
                                Avatar(m.user, 66.dp, ring = true)
                                Spacer(Modifier.height(6.dp))
                                Text(m.user.name.orEmpty(), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
            if (convs.isNotEmpty()) {
                item { SectionTitle("Messages") }
                items(convs, key = { it.matchId }) { m -> ConversationRow(m) { onOpen(m.matchId) } }
            }
        }
    }
}

@Composable
private fun SectionTitle(t: String) {
    Text(t.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp))
}

@Composable
private fun ConversationRow(m: MatchSummary, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Avatar(m.user, 56.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row {
                Text(m.user.name.orEmpty(), fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                m.lastMessage?.let { Text(shortTime(it.at), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            Text(
                (if (m.lastMessage?.fromMe == true) "You: " else "") + m.lastMessage?.text.orEmpty(),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (m.unread > 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = if (m.unread > 0) FontWeight.SemiBold else FontWeight.Normal,
            )
        }
        if (m.unread > 0) {
            Spacer(Modifier.width(8.dp))
            Box(Modifier.clip(RoundedCornerShape(10.dp)).background(Brand).padding(horizontal = 8.dp, vertical = 2.dp)) {
                Text("${m.unread}", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// ------------------------------------------------------------------------------- Conversation

@Composable
fun ConversationScreen(onBack: () -> Unit, vm: ChatViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val messages by vm.messages.collectAsStateWithLifecycle()
    val typing by vm.typing.collectAsStateWithLifecycle()
    var text by remember { mutableStateOf("") }
    var sheet by remember { mutableStateOf(false) }
    var reporting by remember { mutableStateOf(false) }
    val list = rememberLazyListState()
    var pendingKind by remember { mutableStateOf("video") }

    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.all { it }) vm.call(pendingKind)
    }
    fun startCall(kind: String) {
        pendingKind = kind
        val perms = if (kind == "video") arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA) else arrayOf(Manifest.permission.RECORD_AUDIO)
        permissions.launch(perms)
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.onScreenVisible() }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { vm.onScreenHidden() }
    LaunchedEffect(state.gone) { if (state.gone) onBack() }
    LaunchedEffect(messages.size, typing) {
        val last = messages.size - 1 + (if (typing) 1 else 0)
        if (last >= 0) list.animateScrollToItem(last)
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).imePadding()) {
        // Header
        Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 1.dp) {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                Avatar(state.other, 40.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(state.other?.name.orEmpty(), fontWeight = FontWeight.Bold, maxLines = 1)
                        if (state.other?.verified == true) { Spacer(Modifier.width(4.dp)); VerifiedBadge(16.dp) }
                    }
                    Text(
                        when { typing -> "typing…"; state.online -> "Online"; else -> "Verified match" },
                        fontSize = 12.sp, color = if (typing || state.online) Online else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { startCall("audio") }, enabled = state.other != null) { Icon(Icons.Filled.Call, "Voice call") }
                IconButton(onClick = { startCall("video") }, enabled = state.other != null) { Icon(Icons.Filled.Videocam, "Video call") }
                IconButton(onClick = { sheet = true }) { Icon(Icons.Filled.MoreVert, "More") }
            }
        }

        // Messages
        LazyColumn(
            state = list, modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (!state.loading && messages.isEmpty()) {
                item {
                    Column(Modifier.fillMaxWidth().padding(top = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Avatar(state.other, 90.dp, showOnline = false)
                        Spacer(Modifier.height(12.dp))
                        Text("You matched with ${state.other?.name.orEmpty()}!", fontWeight = FontWeight.Bold)
                        Text("Say hi 👋 — be kind and respectful.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            val lastMine = messages.lastOrNull { it.senderId == vm.myId }?.clientMessageId
            items(messages.size, key = { messages[it].clientMessageId }) { i ->
                val m = messages[i]
                val prev = messages.getOrNull(i - 1)
                if (prev == null || dayLabel(prev.createdAt) != dayLabel(m.createdAt)) {
                    Text(dayLabel(m.createdAt), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
                Bubble(m, mine = m.senderId == vm.myId, showStatus = m.clientMessageId == lastMine || m.state != "sent", onRetry = { vm.retry(m) })
            }
            if (typing) item(key = "typing") { TypingBubble() }
        }

        // Composer
        Surface(color = MaterialTheme.colorScheme.surface) {
            Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(10.dp), verticalAlignment = Alignment.Bottom) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(2000); vm.onTyping() },
                    placeholder = { Text("Type a message…") },
                    modifier = Modifier.weight(1f), shape = RoundedCornerShape(24.dp), maxLines = 5,
                )
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier.size(52.dp).clip(CircleShape).background(BrandGradient)
                        .clickable(enabled = text.isNotBlank()) { vm.send(text); text = "" },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.AutoMirrored.Filled.Send, "Send", tint = Color.White) }
            }
        }
    }

    if (sheet) SafetySheet(
        name = state.other?.name.orEmpty(),
        onDismiss = { sheet = false },
        onUnmatch = { sheet = false; vm.unmatch(onBack) },
        onBlock = { sheet = false; vm.block(onBack) },
        onReport = { sheet = false; reporting = true },
    )
    if (reporting) ReportDialog(
        name = state.other?.name.orEmpty(),
        onDismiss = { reporting = false },
        onSubmit = { reason, details, block -> reporting = false; vm.report(reason, details, block, onBack) },
    )
}

@Composable
private fun Bubble(m: MessageEntity, mine: Boolean, showStatus: Boolean, onRetry: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        val shape = if (mine) RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp) else RoundedCornerShape(20.dp, 20.dp, 20.dp, 6.dp)
        Column(
            Modifier.widthIn(max = 290.dp).clip(shape)
                .then(if (mine) Modifier.background(BrandGradient) else Modifier.background(MaterialTheme.colorScheme.surface).border(1.dp, MaterialTheme.colorScheme.outline, shape))
                .then(if (m.state == "failed") Modifier.clickable(onClick = onRetry) else Modifier)
                .padding(horizontal = 14.dp, vertical = 9.dp),
        ) {
            Text(m.text, color = if (mine) Color.White else MaterialTheme.colorScheme.onSurface, fontSize = 15.sp)
            if (mine && showStatus) {
                Text(
                    when {
                        m.state == "failed" -> "⚠ Tap to retry"
                        m.state == "pending" -> "🕓"
                        m.readAt != null -> "✓✓ Read"
                        m.deliveredAt != null -> "✓✓"
                        else -> "✓"
                    },
                    color = Color.White.copy(alpha = 0.85f), fontSize = 11.sp,
                    fontWeight = if (m.readAt != null) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.align(Alignment.End),
                )
            }
        }
    }
}

@Composable
private fun TypingBubble() {
    Row(
        Modifier.clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(20.dp)).padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) { repeat(3) { Box(Modifier.size(7.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onSurfaceVariant)) } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SafetySheet(name: String, onDismiss: () -> Unit, onUnmatch: () -> Unit, onBlock: () -> Unit, onReport: () -> Unit) {
    var confirm by remember { mutableStateOf<String?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text(name, style = MaterialTheme.typography.titleLarge)
            Text("Your safety matters. Reports are confidential.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))
            SheetOption("💔  Unmatch") { confirm = "unmatch" }
            SheetOption("🚩  Report", onClick = onReport)
            SheetOption("⛔  Block", color = MaterialTheme.colorScheme.error) { confirm = "block" }
        }
    }
    confirm?.let { what ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(if (what == "block") "Block $name?" else "Unmatch $name?") },
            text = { Text(if (what == "block") "They won't be able to see you or message you." else "Your conversation will be removed.") },
            confirmButton = { TextButton(onClick = { confirm = null; if (what == "block") onBlock() else onUnmatch() }) { Text("Confirm", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SheetOption(text: String, color: Color = MaterialTheme.colorScheme.onSurface, onClick: () -> Unit) {
    Text(text, color = color, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(14.dp))
}

@Composable
private fun ReportDialog(name: String, onDismiss: () -> Unit, onSubmit: (String, String, Boolean) -> Unit) {
    val reasons = listOf(
        "fake_profile" to "Fake profile / impersonation",
        "harassment" to "Harassment or threats",
        "inappropriate_content" to "Inappropriate photos or messages",
        "scam" to "Scam or asking for money",
        "underage" to "Appears to be under 18",
        "other" to "Something else",
    )
    var reason by remember { mutableStateOf<String?>(null) }
    var details by remember { mutableStateOf("") }
    var alsoBlock by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Report $name") },
        text = {
            Column {
                reasons.forEach { (k, label) ->
                    Row(Modifier.fillMaxWidth().selectable(selected = reason == k, onClick = { reason = k }).padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = reason == k, onClick = { reason = k })
                        Text(label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                OutlinedTextField(details, { details = it.take(1000) }, placeholder = { Text("Add details (optional)") }, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(alsoBlock, { alsoBlock = it })
                    Text("Also block $name", style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = { TextButton(enabled = reason != null, onClick = { onSubmit(reason!!, details, alsoBlock) }) { Text("Submit") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ------------------------------------------------------------------------------- time helpers

private fun sameDay(a: Long, b: Long): Boolean {
    val c1 = Calendar.getInstance().apply { timeInMillis = a }
    val c2 = Calendar.getInstance().apply { timeInMillis = b }
    return c1.get(Calendar.YEAR) == c2.get(Calendar.YEAR) && c1.get(Calendar.DAY_OF_YEAR) == c2.get(Calendar.DAY_OF_YEAR)
}

fun shortTime(ts: Long): String {
    val now = System.currentTimeMillis()
    return when {
        sameDay(ts, now) -> SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(ts))
        sameDay(ts, now - 86_400_000) -> "Yesterday"
        else -> SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(ts))
    }
}

fun dayLabel(ts: Long): String {
    val now = System.currentTimeMillis()
    return when {
        sameDay(ts, now) -> "Today"
        sameDay(ts, now - 86_400_000) -> "Yesterday"
        else -> SimpleDateFormat("EEE, d MMM", Locale.getDefault()).format(Date(ts))
    }
}
