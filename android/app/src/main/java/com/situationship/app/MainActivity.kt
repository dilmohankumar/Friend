package com.situationship.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.situationship.app.call.CallManager
import com.situationship.app.push.Notifications
import com.situationship.app.ui.AppRoot
import com.situationship.app.ui.DeepLinks
import com.situationship.app.ui.theme.SituationshipTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var deepLinks: DeepLinks
    @Inject lateinit var calls: CallManager

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        setContent {
            SituationshipTheme {
                AppRoot()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        // Identity provider redirect: situationship://kyc/return?result=verified
        intent.data?.takeIf { it.scheme == "situationship" && it.host == "kyc" }?.let {
            deepLinks.kycReturned(it.getQueryParameter("result"))
        }
        intent.getStringExtra(Notifications.EXTRA_OPEN_MATCH)?.let { deepLinks.openChat(it) }
        intent.getStringExtra(Notifications.EXTRA_CALL_ID)?.let { callId ->
            Notifications.cancelIncomingCall(this)
            if (intent.getBooleanExtra(Notifications.EXTRA_ACCEPT_CALL, false)) calls.autoAcceptWhenArrives(callId)
        }
        intent.removeExtra(Notifications.EXTRA_OPEN_MATCH)
        intent.removeExtra(Notifications.EXTRA_CALL_ID)
        intent.data = null
    }
}
