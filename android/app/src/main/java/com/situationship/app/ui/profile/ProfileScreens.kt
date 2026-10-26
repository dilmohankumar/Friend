package com.situationship.app.ui.profile

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.situationship.app.BuildConfig
import com.situationship.app.ui.components.*
import com.situationship.app.ui.theme.Brand
import com.situationship.app.ui.theme.Online

// ------------------------------------------------------------------------------- Setup / Edit

@Composable
fun EditProfileScreen(setup: Boolean, onDone: () -> Unit, onBack: () -> Unit, vm: ProfileViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(vm::addPhoto) }
    LaunchedEffect(ui.saved) { if (ui.saved && !setup) onDone() }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (setup) {
            Column(Modifier.statusBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp)) {
                StepBar(3)
                Spacer(Modifier.height(20.dp))
                Text("Create your profile", style = MaterialTheme.typography.headlineLarge)
                Text("This is what others will see. You can change it later.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else BackHeader("Edit profile", onBack)

        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).imePadding()) {
            Label("Photos (at least 1)")
            PhotoGrid(
                photos = ui.photos, uploading = ui.uploading,
                onAdd = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                onDelete = vm::deletePhoto,
            )
            Label("First name")
            Field(ui.form.name, { v -> vm.edit { it.copy(name = v.take(40)) } }, "Your first name")
            Label("I am")
            Segmented(listOf("male" to "Man", "female" to "Woman"), ui.form.gender, vm::setGender)
            Label("Show me")
            Segmented(listOf("female" to "Women", "male" to "Men"), ui.form.interestedIn) { v -> vm.edit { it.copy(interestedIn = v) } }
            Label("Age range: ${ui.form.minAge} – ${ui.form.maxAge}")
            RangeSlider(
                value = ui.form.minAge.toFloat()..ui.form.maxAge.toFloat(),
                onValueChange = { r -> vm.edit { it.copy(minAge = r.start.toInt(), maxAge = r.endInclusive.toInt()) } },
                valueRange = 18f..80f,
                colors = SliderDefaults.colors(thumbColor = Brand, activeTrackColor = Brand),
            )
            Label("City")
            Field(ui.form.city, { v -> vm.edit { it.copy(city = v.take(40)) } }, "e.g. Mumbai")
            Label("About me")
            Field(ui.form.bio, { v -> vm.edit { it.copy(bio = v.take(300)) } }, "A little about you…", singleLine = false)
            ui.error?.let { Spacer(Modifier.height(10.dp)); Text(it, color = MaterialTheme.colorScheme.error) }
            Spacer(Modifier.height(20.dp))
        }
        Box(Modifier.navigationBarsPadding().padding(20.dp)) {
            GradientButton(if (setup) "Finish & start matching" else "Save changes", onClick = vm::save, loading = ui.saving)
        }
    }
}

@Composable
private fun Label(text: String) {
    Spacer(Modifier.height(18.dp))
    Text(text.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun Field(value: String, onChange: (String) -> Unit, placeholder: String, singleLine: Boolean = true) {
    OutlinedTextField(
        value = value, onValueChange = onChange, placeholder = { Text(placeholder) },
        singleLine = singleLine, minLines = if (singleLine) 1 else 3,
        shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
    )
}

@Composable
private fun Segmented(options: List<Pair<String, String>>, selected: String?, onSelect: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        options.forEach { (value, label) ->
            val on = value == selected
            Box(
                Modifier.weight(1f).height(50.dp).clip(RoundedCornerShape(14.dp))
                    .background(if (on) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface)
                    .border(1.5.dp, if (on) Brand else MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp))
                    .clickable { onSelect(value) },
                contentAlignment = Alignment.Center,
            ) { Text(label, fontWeight = FontWeight.SemiBold, color = if (on) Brand else MaterialTheme.colorScheme.onSurface) }
        }
    }
}

@Composable
private fun PhotoGrid(photos: List<String>, uploading: Boolean, onAdd: () -> Unit, onDelete: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        (0 until 6).chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { i ->
                    val url = photos.getOrNull(i)
                    Box(
                        Modifier.weight(1f).aspectRatio(0.75f).clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .then(if (url == null) Modifier.border(2.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp)).clickable(enabled = !uploading) { onAdd() } else Modifier),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (url != null) {
                            AsyncImage(url, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            Box(
                                Modifier.align(Alignment.TopEnd).padding(6.dp).size(26.dp).clip(CircleShape)
                                    .background(Color.Black.copy(alpha = 0.6f)).clickable { onDelete(i) },
                                contentAlignment = Alignment.Center,
                            ) { Icon(Icons.Default.Close, "Remove", tint = Color.White, modifier = Modifier.size(16.dp)) }
                        } else if (uploading && i == photos.size) {
                            CircularProgressIndicator(color = Brand, strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
                        } else {
                            Icon(Icons.Default.Add, "Add photo", tint = Brand)
                        }
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------------------- Profile tab

@Composable
fun ProfileScreen(onEdit: () -> Unit, onBlocked: () -> Unit, onSafety: () -> Unit, vm: ProfileViewModel = hiltViewModel()) {
    val user by vm.user.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }
    var deleteText by remember { mutableStateOf("") }
    val u = user ?: return
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text("Profile", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.statusBarsPadding().padding(20.dp, 14.dp))
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Avatar(u.toPublic(), 110.dp, ring = true, showOnline = false)
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${u.name}, ${u.age ?: ""}", style = MaterialTheme.typography.headlineMedium)
                if (u.verified) { Spacer(Modifier.width(6.dp)); VerifiedBadge() }
            }
            Text("${u.city} · ${u.phone}", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(20.dp))
        MenuCard {
            MenuItem("✏️  Edit profile & photos", onClick = onEdit)
            MenuItem("🎯  Discovery preferences", onClick = onEdit)
        }
        MenuCard {
            MenuItem("🛡️  Safety tips", onClick = onSafety)
            MenuItem("⛔  Blocked users", onClick = onBlocked)
            MenuItem("🪪  Identity", trailing = { Text("Verified ✓", color = Online, fontWeight = FontWeight.SemiBold) })
            if (Build.VERSION.SDK_INT >= 33) {
                MenuItem("🔔  Notifications", onClick = { notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) })
            }
        }
        MenuCard {
            MenuItem("🚪  Log out", onClick = { vm.logout() })
            MenuItem("🗑️  Delete account", color = MaterialTheme.colorScheme.error, onClick = { confirmDelete = true })
        }
        Text(
            "Situationship v${BuildConfig.VERSION_NAME}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp,
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(16.dp),
        )
    }

    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete account?") },
        text = {
            Column {
                Text("This permanently deletes your profile, photos, matches and messages. This cannot be undone.")
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(deleteText, { deleteText = it }, label = { Text("Type DELETE to confirm") }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(enabled = deleteText.trim().equals("DELETE", ignoreCase = true), onClick = { confirmDelete = false; vm.deleteAccount() }) {
                Text("Delete", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
    )
}

@Composable
private fun MenuCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth(),
    ) { Column(content = content) }
}

@Composable
private fun MenuItem(
    text: String,
    color: Color = MaterialTheme.colorScheme.onSurface,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, fontWeight = FontWeight.SemiBold, color = color, modifier = Modifier.weight(1f))
        if (trailing != null) trailing()
        else if (onClick != null) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ------------------------------------------------------------------------------- Blocked / Safety

@Composable
fun BlockedScreen(onBack: () -> Unit, vm: ProfileViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.loadBlocked() }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        BackHeader("Blocked users", onBack)
        val list = ui.blocked
        when {
            list == null -> FullScreenLoader()
            list.isEmpty() -> EmptyState("🙂", "Nobody blocked", "People you block will appear here.")
            else -> list.forEach { u ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(u, 48.dp, showOnline = false)
                    Spacer(Modifier.width(14.dp))
                    Text(u.name.orEmpty(), fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    FilledTonalButton(onClick = { vm.unblock(u.id) }) { Text("Unblock") }
                }
            }
        }
    }
}

@Composable
fun SafetyScreen(onBack: () -> Unit) {
    val tips = listOf(
        "💸" to "Never send money or share bank/UPI details, OTPs or passwords with a match.",
        "🏠" to "Keep personal info private — home address, workplace and daily routine.",
        "☕" to "Meet in public places for the first few dates and tell a friend where you're going.",
        "📹" to "Use in-app video calls to check your match is who they say they are before meeting.",
        "🚩" to "If something feels off, unmatch, block and report. Reports are confidential.",
        "🆘" to "In an emergency in India call 112. Women's helpline: 181.",
    )
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        BackHeader("Safety tips", onBack)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            tips.forEach { (e, t) ->
                Row {
                    Box(
                        Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center,
                    ) { Text(e) }
                    Spacer(Modifier.width(12.dp))
                    Text(t, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}
