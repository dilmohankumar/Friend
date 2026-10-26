package com.situationship.app.ui.onboarding

import android.content.Context
import android.net.Uri
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.situationship.app.data.model.ConsentRequest
import com.situationship.app.data.remote.ApiService
import com.situationship.app.data.remote.errorCode
import com.situationship.app.data.remote.userMessage
import com.situationship.app.data.repo.SessionRepository
import com.situationship.app.ui.components.DevNote
import com.situationship.app.ui.components.GradientButton
import com.situationship.app.ui.components.SecondaryButton
import com.situationship.app.ui.components.StepBar
import com.situationship.app.ui.theme.Brand
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class OnboardingUi(val loading: Boolean = false, val error: String? = null, val devMode: Boolean = false)

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val api: ApiService,
    private val session: SessionRepository,
) : ViewModel() {
    private val _ui = MutableStateFlow(OnboardingUi())
    val ui = _ui.asStateFlow()
    val user = session.user

    fun consent() = launchTask { api.consent(ConsentRequest(true)); session.refreshMe() }

    /** Creates a verification session; the provider's page opens in a Chrome Custom Tab and deep-links back. */
    fun startVerification(ctx: Context) = launchTask {
        try {
            val r = api.startKyc()
            _ui.value = _ui.value.copy(devMode = r.redirectUrl.contains("/mock-kyc/"))
            openCustomTab(ctx, r.redirectUrl)
        } catch (e: Exception) {
            if (e.errorCode() == "already_verified") session.refreshMe() else throw e
        }
    }

    fun refresh() = launchTask { session.refreshMe() }
    fun logout() = launchTask { session.logout() }
    fun deleteAccount() = launchTask { session.deleteAccount() }

    private fun launchTask(block: suspend () -> Unit) = viewModelScope.launch {
        _ui.value = _ui.value.copy(loading = true, error = null)
        try { block() } catch (e: Exception) { _ui.value = _ui.value.copy(error = e.userMessage()) }
        _ui.value = _ui.value.copy(loading = false)
    }
}

fun openCustomTab(ctx: Context, url: String) {
    CustomTabsIntent.Builder()
        .setShowTitle(true)
        .setDefaultColorSchemeParams(CustomTabColorSchemeParams.Builder().setToolbarColor(0xFFFF4D6D.toInt()).build())
        .build()
        .launchUrl(ctx, Uri.parse(url))
}

@Composable
private fun InfoRow(emoji: String, text: String) {
    Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(vertical = 7.dp)) {
        Box(
            Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) { Text(emoji, fontSize = 17.sp) }
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun CheckRow(checked: Boolean, onChange: (Boolean) -> Unit, text: String) {
    Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(vertical = 4.dp)) {
        Checkbox(checked = checked, onCheckedChange = onChange, colors = CheckboxDefaults.colors(checkedColor = Brand))
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 12.dp))
    }
}

@Composable
fun ConsentScreen(vm: OnboardingViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    var adult by remember { mutableStateOf(false) }
    var kyc by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).systemBarsPadding().padding(20.dp)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            StepBar(1)
            Spacer(Modifier.height(24.dp))
            Text("Before we verify you", style = MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.height(8.dp))
            Text("Situationship only allows verified adults. Here's how we handle your identity:", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            InfoRow("🔐", "Verification is done by an authorised identity provider. We never store your ID number or document images.")
            InfoRow("🎂", "We only receive whether you're verified and whether you're 18+. Your birth year is used to show your age.")
            InfoRow("🎯", "This data is used only for account verification and safety — never for ads.")
            InfoRow("🗑️", "You can delete your account and data anytime from your profile.")
            Spacer(Modifier.height(8.dp))
            CheckRow(adult, { adult = it }, "I am 18 years or older and agree to the Terms of Use and Privacy Policy.")
            CheckRow(kyc, { kyc = it }, "I consent to identity verification for the purpose of account eligibility and safety.")
            ui.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        GradientButton("Continue", onClick = { vm.consent() }, enabled = adult && kyc, loading = ui.loading)
        Spacer(Modifier.height(10.dp))
        SecondaryButton("Log out", onClick = { vm.logout() })
    }
}

@Composable
fun VerifyScreen(vm: OnboardingViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val user by vm.user.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val failed = user?.kycStatus == "failed"
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).systemBarsPadding().padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        StepBar(2)
        Spacer(Modifier.weight(1f))
        Box(
            Modifier.size(88.dp).clip(RoundedCornerShape(26.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) { Text("🪪", fontSize = 42.sp) }
        Spacer(Modifier.height(16.dp))
        Text("Verify your identity", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            if (failed) "Your last verification attempt did not succeed. Please try again."
            else "Takes about a minute. You'll be taken to our verification partner and brought back here automatically.",
            textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (ui.devMode) { Spacer(Modifier.height(14.dp)); DevNote("Dev mode: a simulated provider is used. No real ID is checked.") }
        ui.error?.let { Spacer(Modifier.height(10.dp)); Text(it, color = MaterialTheme.colorScheme.error) }
        Spacer(Modifier.weight(1f))
        GradientButton("Start verification", onClick = { vm.startVerification(ctx) }, loading = ui.loading)
        Spacer(Modifier.height(10.dp))
        SecondaryButton("I've finished — check status", onClick = { vm.refresh() })
        Spacer(Modifier.height(10.dp))
        TextButton(onClick = { vm.logout() }) { Text("Log out", fontWeight = FontWeight.SemiBold) }
    }
}

@Composable
fun NotEligibleScreen(vm: OnboardingViewModel = hiltViewModel()) {
    var confirm by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).systemBarsPadding().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Text("🔞", fontSize = 56.sp)
        Spacer(Modifier.height(12.dp))
        Text("Not eligible", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "Situationship is only for adults aged 18 and above. Your account can't access discovery or chat.",
            textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(28.dp))
        SecondaryButton("Delete my account", onClick = { confirm = true }, color = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(10.dp))
        SecondaryButton("Log out", onClick = { vm.logout() })
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("Delete account?") },
        text = { Text("This permanently deletes your account. This cannot be undone.") },
        confirmButton = { TextButton(onClick = { confirm = false; vm.deleteAccount() }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
    )
}
