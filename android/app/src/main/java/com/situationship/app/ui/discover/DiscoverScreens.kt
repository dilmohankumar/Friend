package com.situationship.app.ui.discover

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.situationship.app.data.model.MatchEvent
import com.situationship.app.data.model.PublicProfile
import com.situationship.app.data.model.SelfProfile
import com.situationship.app.ui.RootEntryPoint
import com.situationship.app.ui.components.*
import com.situationship.app.ui.theme.Brand
import com.situationship.app.ui.theme.Brand2
import com.situationship.app.ui.theme.BrandGradient
import com.situationship.app.ui.theme.Online
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

// ------------------------------------------------------------------------------- Discover

@Composable
fun DiscoverScreen(onEditPrefs: () -> Unit, vm: DiscoverViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(36.dp).clip(RoundedCornerShape(11.dp)).background(BrandGradient), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.LocalFireDepartment, null, tint = Color.White, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(10.dp))
            Text("Situationship", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
            IconButton(onClick = onEditPrefs) { Icon(Icons.Outlined.Tune, "Preferences") }
        }
        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp)) {
            when {
                state.loading && state.deck.isEmpty() -> FullScreenLoader()
                state.deck.isEmpty() -> EmptyState(
                    "🌙", "You're all caught up",
                    "No new verified profiles right now. Check back later or widen your age range.",
                    Modifier.align(Alignment.Center),
                ) { SecondaryButton("Refresh", onClick = vm::load, modifier = Modifier.width(160.dp)) }
                else -> {
                    val top = state.deck.first()
                    state.deck.getOrNull(1)?.let { next ->
                        key(next.id) { ProfileCard(next, Modifier.fillMaxSize().scale(0.95f).padding(top = 10.dp)) }
                    }
                    key(top.id) { SwipeableCard(top, onSwiped = { like -> vm.swipe(top, like) }) }
                }
            }
        }
        if (state.deck.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(28.dp, Alignment.CenterHorizontally)) {
                RoundAction(Icons.Filled.Close, "Pass", Color(0xFFF43F5E), filled = false) { vm.swipe(state.deck.first(), false) }
                RoundAction(Icons.Filled.Favorite, "Like", Color.White, filled = true) { vm.swipe(state.deck.first(), true) }
            }
        }
    }
}

@Composable
private fun RoundAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, tint: Color, filled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(66.dp).shadow(10.dp, CircleShape).clip(CircleShape)
            .background(if (filled) BrandGradient else Brush.linearGradient(listOf(MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.surface)))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, tint = tint, modifier = Modifier.size(30.dp)) }
}

/** Drag left = Pass, drag right = Like. Released past the threshold the card flies off. */
@Composable
private fun SwipeableCard(profile: PublicProfile, onSwiped: (Boolean) -> Unit) {
    val scope = rememberCoroutineScope()
    val widthPx = with(androidx.compose.ui.platform.LocalDensity.current) { LocalConfiguration.current.screenWidthDp.dp.toPx() }
    val offsetX = remember { Animatable(0f) }
    val offsetY = remember { Animatable(0f) }
    val threshold = widthPx * 0.28f

    Box(
        Modifier.fillMaxSize()
            .offset { IntOffset(offsetX.value.roundToInt(), offsetY.value.roundToInt()) }
            .graphicsLayer { rotationZ = offsetX.value / 22f }
            .pointerInput(profile.id) {
                detectDragGestures(
                    onDrag = { change, drag ->
                        change.consume()
                        scope.launch { offsetX.snapTo(offsetX.value + drag.x); offsetY.snapTo(offsetY.value + drag.y * 0.4f) }
                    },
                    onDragEnd = {
                        scope.launch {
                            if (abs(offsetX.value) > threshold) {
                                val like = offsetX.value > 0
                                launch { offsetY.animateTo(offsetY.value + 60f, tween(250)) }
                                offsetX.animateTo(if (like) widthPx * 1.6f else -widthPx * 1.6f, tween(250))
                                onSwiped(like)
                            } else {
                                launch { offsetY.animateTo(0f, tween(220)) }
                                offsetX.animateTo(0f, tween(220))
                            }
                        }
                    },
                    onDragCancel = { scope.launch { offsetX.animateTo(0f); offsetY.animateTo(0f) } },
                )
            }
    ) {
        ProfileCard(profile, Modifier.fillMaxSize())
        val likeAlpha = (offsetX.value / threshold).coerceIn(0f, 1f)
        val nopeAlpha = (-offsetX.value / threshold).coerceIn(0f, 1f)
        Stamp("LIKE", Color(0xFF22C55E), -14f, likeAlpha, Modifier.align(Alignment.TopStart).padding(26.dp))
        Stamp("NOPE", Color(0xFFF43F5E), 14f, nopeAlpha, Modifier.align(Alignment.TopEnd).padding(26.dp))
    }
}

@Composable
private fun Stamp(text: String, color: Color, angle: Float, alpha: Float, modifier: Modifier) {
    if (alpha <= 0f) return
    Text(
        text, color = color, fontSize = 32.sp, fontWeight = FontWeight.Black,
        modifier = modifier.graphicsLayer { this.alpha = alpha }.rotate(angle)
            .background(Color.Transparent).padding(4.dp)
            .then(Modifier.background(color.copy(alpha = 0.12f), RoundedCornerShape(10.dp)))
            .padding(horizontal = 12.dp, vertical = 2.dp),
    )
}

/** Card with photo carousel (tap left/right side), name, age, city and bio. */
@Composable
fun ProfileCard(p: PublicProfile, modifier: Modifier = Modifier) {
    var idx by remember(p.id) { mutableIntStateOf(0) }
    Box(modifier.shadow(14.dp, RoundedCornerShape(24.dp)).clip(RoundedCornerShape(24.dp)).background(Color(0xFFDDDDDD))) {
        AsyncImage(
            model = p.photos.getOrNull(idx), contentDescription = p.name, contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().pointerInput(p.id, p.photos.size) {
                detectTapGestures { pos ->
                    if (p.photos.size > 1) {
                        idx = if (pos.x > size.width / 2) (idx + 1) % p.photos.size else (idx - 1 + p.photos.size) % p.photos.size
                    }
                }
            },
        )
        if (p.photos.size > 1) {
            Row(Modifier.fillMaxWidth().padding(10.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                p.photos.indices.forEach { i ->
                    Box(Modifier.weight(1f).height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = if (i == idx) 1f else 0.4f)))
                }
            }
        }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.78f))))
        Column(Modifier.align(Alignment.BottomStart).padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${p.name}, ${p.age ?: ""}", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
                if (p.verified) { Spacer(Modifier.width(8.dp)); VerifiedBadge(22.dp) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (p.city.isNotEmpty()) Text("📍 ${p.city}", color = Color.White.copy(alpha = 0.9f))
                if (p.online) {
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.size(8.dp).clip(CircleShape).background(Online))
                    Text(" Active now", color = Color.White.copy(alpha = 0.9f))
                }
            }
            if (p.bio.isNotEmpty()) { Spacer(Modifier.height(8.dp)); Text(p.bio, color = Color.White, maxLines = 3) }
        }
    }
}

// ------------------------------------------------------------------------------- Likes

@Composable
fun LikesScreen(vm: DiscoverViewModel = hiltViewModel()) {
    val likes by vm.likes.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.loadLikes() }
    Column(Modifier.fillMaxSize()) {
        Text("Likes you", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.statusBarsPadding().padding(20.dp, 14.dp))
        val list = likes.profiles
        when {
            list == null -> FullScreenLoader()
            list.isEmpty() -> EmptyState("💌", "No likes yet", "When someone likes you, they'll show up here. Keep your profile fresh with good photos!")
            else -> {
                Text(
                    "${list.size} ${if (list.size == 1) "person likes" else "people like"} you. Like back to match.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp),
                )
                LazyVerticalGrid(
                    GridCells.Fixed(2), contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(list, key = { it.id }) { p ->
                        Box(Modifier.aspectRatio(0.75f).clip(RoundedCornerShape(18.dp)).background(Color.LightGray)) {
                            AsyncImage(p.photos.firstOrNull(), p.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.7f))))
                            Column(Modifier.align(Alignment.BottomStart).padding(8.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("${p.name}, ${p.age ?: ""}", color = Color.White, fontWeight = FontWeight.Bold)
                                    if (p.verified) { Spacer(Modifier.width(4.dp)); VerifiedBadge(16.dp) }
                                }
                                Spacer(Modifier.height(6.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    SmallPill("✕", Color.White.copy(alpha = 0.92f), Color(0xFF222222), Modifier.weight(1f)) { vm.answerLike(p, false) }
                                    SmallPill("♥ Like", Brand, Color.White, Modifier.weight(1f)) { vm.answerLike(p, true) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SmallPill(text: String, bg: Color, fg: Color, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.height(34.dp).clip(RoundedCornerShape(10.dp)).background(bg).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = fg, fontWeight = FontWeight.Bold, fontSize = 13.sp) }
}

// ------------------------------------------------------------------------------- Match popup

@Composable
fun MatchPopup(e: MatchEvent, me: SelfProfile?, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val links = remember { EntryPointAccessors.fromApplication(ctx.applicationContext, RootEntryPoint::class.java).deepLinks() }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Brand.copy(alpha = 0.97f), Brand2.copy(alpha = 0.97f)))).padding(30.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
        ) {
            Text("It's a match!", color = Color.White, fontSize = 42.sp, fontWeight = FontWeight.ExtraBold, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
            Spacer(Modifier.height(8.dp))
            Text("You and ${e.user.name} liked each other.", color = Color.White, textAlign = TextAlign.Center)
            Spacer(Modifier.height(30.dp))
            Row {
                Avatar(me?.toPublic(), 120.dp, showOnline = false)
                Avatar(e.user, 120.dp, showOnline = false, modifier = Modifier.offset(x = (-20).dp))
            }
            Spacer(Modifier.height(36.dp))
            Button(
                onClick = { onDismiss(); links.openChat(e.matchId) },
                modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Brand),
            ) { Text("Send a message", fontWeight = FontWeight.Bold) }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = onDismiss, modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(16.dp),
                border = androidx.compose.foundation.BorderStroke(2.dp, Color.White.copy(alpha = 0.7f)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
            ) { Text("Keep swiping", fontWeight = FontWeight.Bold) }
        }
    }
}
