package com.situationship.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.situationship.app.data.model.PublicProfile
import com.situationship.app.ui.theme.Brand
import com.situationship.app.ui.theme.BrandGradient
import com.situationship.app.ui.theme.Online
import com.situationship.app.ui.theme.Verified

@Composable
fun GradientButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    Button(
        onClick = onClick,
        enabled = enabled && !loading,
        modifier = modifier.fillMaxWidth().height(56.dp),
        shape = RoundedCornerShape(16.dp),
        contentPadding = PaddingValues(),
        colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent, disabledContainerColor = Color.Transparent),
    ) {
        Box(
            Modifier.fillMaxSize().background(BrandGradient, RoundedCornerShape(16.dp))
                .then(if (!enabled || loading) Modifier.background(Color.White.copy(alpha = 0.45f), RoundedCornerShape(16.dp)) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            if (loading) CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
            else Text(text, color = Color.White, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurface) {
    FilledTonalButton(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(52.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.filledTonalButtonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = color),
    ) { Text(text, fontWeight = FontWeight.SemiBold) }
}

@Composable
fun Avatar(profile: PublicProfile?, size: Dp, modifier: Modifier = Modifier, ring: Boolean = false, showOnline: Boolean = true) {
    Box(modifier.size(size)) {
        AsyncImage(
            model = profile?.photos?.firstOrNull(),
            contentDescription = profile?.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .then(if (ring) Modifier.border(3.dp, Brand, CircleShape) else Modifier),
        )
        if (showOnline && profile?.online == true) {
            Box(
                Modifier.align(Alignment.BottomEnd).size(size / 5).clip(CircleShape)
                    .background(MaterialTheme.colorScheme.background).padding(2.dp).clip(CircleShape).background(Online)
            )
        }
    }
}

@Composable
fun VerifiedBadge(size: Dp = 20.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(Verified), contentAlignment = Alignment.Center) {
        Icon(Icons.Default.Check, contentDescription = "Verified", tint = Color.White, modifier = Modifier.size(size * 0.7f))
    }
}

@Composable
fun BackHeader(title: String, onBack: () -> Unit, actions: @Composable RowScope.() -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        actions()
    }
}

@Composable
fun StepBar(step: Int, total: Int = 3) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(total) { i ->
            Box(
                Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(4.dp))
                    .background(if (i < step) Brand else MaterialTheme.colorScheme.outline)
            )
        }
    }
}

@Composable
fun DevNote(text: String) {
    Text(
        text,
        color = Color(0xFF92400E),
        fontSize = 13.sp,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFFFEF3C7)).padding(12.dp),
    )
}

@Composable
fun EmptyState(emoji: String, title: String, body: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(84.dp).clip(RoundedCornerShape(26.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) { Text(emoji, fontSize = 40.sp) }
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(6.dp))
        Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        if (action != null) { Spacer(Modifier.height(16.dp)); action() }
    }
}

@Composable
fun FullScreenLoader() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Brand) }
}
