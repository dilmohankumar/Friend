package com.situationship.app.ui.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.situationship.app.data.remote.userMessage
import com.situationship.app.data.repo.SessionRepository
import com.situationship.app.ui.components.DevNote
import com.situationship.app.ui.components.GradientButton
import com.situationship.app.ui.theme.Brand
import com.situationship.app.ui.theme.BrandGradient
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

// ------------------------------------------------------------------------------- ViewModels

data class LoginUi(val phone: String = "", val loading: Boolean = false, val error: String? = null)

@HiltViewModel
class LoginViewModel @Inject constructor(private val session: SessionRepository) : ViewModel() {
    private val _ui = MutableStateFlow(LoginUi())
    val ui = _ui.asStateFlow()

    fun onPhone(v: String) = _ui.update { it.copy(phone = v.filter(Char::isDigit).take(10), error = null) }

    fun send(onSent: (String) -> Unit) {
        val p = _ui.value.phone
        if (!Regex("^[6-9]\\d{9}$").matches(p)) { _ui.update { it.copy(error = "Enter a valid 10-digit Indian mobile number") }; return }
        viewModelScope.launch {
            _ui.update { it.copy(loading = true, error = null) }
            try {
                val r = session.requestOtp(p)
                OtpCache.devOtp = r.devOtp
                onSent(r.phone)
            } catch (e: Exception) {
                _ui.update { it.copy(error = e.userMessage()) }
            } finally {
                _ui.update { it.copy(loading = false) }
            }
        }
    }
}

/** Dev-only: the backend returns the OTP when no SMS gateway is configured. */
object OtpCache { var devOtp: String? = null }

data class OtpUi(val code: String = "", val loading: Boolean = false, val error: String? = null, val devOtp: String? = null, val info: String? = null)

@HiltViewModel
class OtpViewModel @Inject constructor(
    private val session: SessionRepository,
    saved: SavedStateHandle,
) : ViewModel() {
    val phone: String = saved.get<String>("phone").orEmpty()
    private val _ui = MutableStateFlow(OtpUi(devOtp = OtpCache.devOtp))
    val ui = _ui.asStateFlow()

    fun onCode(v: String) {
        val c = v.filter(Char::isDigit).take(6)
        _ui.update { it.copy(code = c, error = null) }
        if (c.length == 6) verify()
    }

    fun verify() {
        val c = _ui.value.code
        if (c.length != 6 || _ui.value.loading) return
        viewModelScope.launch {
            _ui.update { it.copy(loading = true) }
            try {
                session.verifyOtp(phone, c) // RouteGuard moves on once the user is set
                OtpCache.devOtp = null
            } catch (e: Exception) {
                _ui.update { it.copy(error = e.userMessage(), code = "") }
            } finally {
                _ui.update { it.copy(loading = false) }
            }
        }
    }

    fun resend() = viewModelScope.launch {
        try {
            val r = session.requestOtp(phone)
            OtpCache.devOtp = r.devOtp
            _ui.update { it.copy(devOtp = r.devOtp, info = "New code sent", code = "", error = null) }
        } catch (e: Exception) {
            _ui.update { it.copy(error = e.userMessage()) }
        }
    }
}

// ------------------------------------------------------------------------------- Screens

@Composable
fun LoginScreen(onCodeSent: (String) -> Unit, vm: LoginViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).systemBarsPadding().imePadding().padding(20.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(BrandGradient), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.LocalFireDepartment, null, tint = Color.White)
            }
            Spacer(Modifier.width(10.dp))
            Text("Situationship", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
        }
        Spacer(Modifier.height(36.dp))
        Text("Real people.\nVerified matches.", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(8.dp))
        Text("Enter your mobile number. We'll send a one-time code to verify it.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(28.dp))
        Text("MOBILE NUMBER", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = "+91", onValueChange = {}, enabled = false, modifier = Modifier.width(80.dp),
                shape = RoundedCornerShape(14.dp), textStyle = TextStyle(fontWeight = FontWeight.Bold, textAlign = TextAlign.Center),
            )
            OutlinedTextField(
                value = ui.phone, onValueChange = vm::onPhone, modifier = Modifier.weight(1f),
                placeholder = { Text("98765 43210") }, singleLine = true, shape = RoundedCornerShape(14.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                isError = ui.error != null,
            )
        }
        ui.error?.let { Spacer(Modifier.height(8.dp)); Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
        Spacer(Modifier.weight(1f))
        GradientButton("Send OTP", onClick = { vm.send(onCodeSent) }, loading = ui.loading)
        Spacer(Modifier.height(12.dp))
        Text(
            "By continuing you agree to our Terms and Privacy Policy. You must be 18+ to use Situationship.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
fun OtpScreen(onBack: () -> Unit, vm: OtpViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).systemBarsPadding().imePadding().padding(20.dp)
    ) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        Spacer(Modifier.height(12.dp))
        Text("Enter the code", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(8.dp))
        Text("Sent to ${vm.phone}", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
        // Six boxes backed by one hidden text field (supports paste + SMS autofill)
        BasicTextField(
            value = ui.code,
            onValueChange = vm::onCode,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier.focusRequester(focus),
            decorationBox = {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    repeat(6) { i ->
                        val ch = ui.code.getOrNull(i)?.toString() ?: ""
                        val active = i == ui.code.length
                        Box(
                            Modifier.weight(1f).aspectRatio(0.87f).clip(RoundedCornerShape(14.dp))
                                .background(MaterialTheme.colorScheme.surface)
                                .border(if (active) 2.dp else 1.5.dp, if (active) Brand else MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp)),
                            contentAlignment = Alignment.Center,
                        ) { Text(ch, fontSize = 24.sp, fontWeight = FontWeight.Bold) }
                    }
                }
            },
        )
        ui.error?.let { Spacer(Modifier.height(10.dp)); Text(it, color = MaterialTheme.colorScheme.error) }
        ui.info?.let { Spacer(Modifier.height(10.dp)); Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        ui.devOtp?.let { Spacer(Modifier.height(14.dp)); DevNote("Dev mode: no SMS gateway connected. Your code is $it") }
        Spacer(Modifier.weight(1f))
        GradientButton("Verify", onClick = vm::verify, enabled = ui.code.length == 6, loading = ui.loading)
        TextButton(onClick = { vm.resend() }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Resend code", color = Brand) }
    }
}
