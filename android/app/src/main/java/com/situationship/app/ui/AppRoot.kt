package com.situationship.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.situationship.app.ui.auth.LoginScreen
import com.situationship.app.ui.auth.OtpScreen
import com.situationship.app.ui.call.CallOverlay
import com.situationship.app.ui.chat.ChatsScreen
import com.situationship.app.ui.chat.ConversationScreen
import com.situationship.app.ui.components.EmptyState
import com.situationship.app.ui.components.FullScreenLoader
import com.situationship.app.ui.components.GradientButton
import com.situationship.app.ui.discover.DiscoverScreen
import com.situationship.app.ui.discover.LikesScreen
import com.situationship.app.ui.discover.MatchPopup
import com.situationship.app.ui.onboarding.ConsentScreen
import com.situationship.app.ui.onboarding.NotEligibleScreen
import com.situationship.app.ui.onboarding.VerifyScreen
import com.situationship.app.ui.profile.BlockedScreen
import com.situationship.app.ui.profile.EditProfileScreen
import com.situationship.app.ui.profile.ProfileScreen
import com.situationship.app.ui.profile.SafetyScreen
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import androidx.compose.ui.platform.LocalContext
import com.situationship.app.call.CallManager
import com.situationship.app.data.repo.ChatRepository
import kotlinx.coroutines.flow.merge

@EntryPoint
@InstallIn(SingletonComponent::class)
interface RootEntryPoint {
    fun calls(): CallManager
    fun chat(): ChatRepository
    fun popups(): MatchPopupBus
    fun ui(): UiMessages
    fun deepLinks(): DeepLinks
}

@Composable
fun AppRoot(vm: SessionViewModel = hiltViewModel()) {
    val ctx = LocalContext.current
    val deps = remember { EntryPointAccessors.fromApplication(ctx.applicationContext, RootEntryPoint::class.java) }
    val boot by vm.boot.collectAsStateWithLifecycle()
    val user by vm.user.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        merge(deps.ui().messages, deps.calls().messages, deps.chat().errors).collect { snackbar.showSnackbar(it) }
    }

    Box(Modifier.fillMaxSize()) {
        when (boot) {
            BootState.Loading -> FullScreenLoader()
            BootState.Offline -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                EmptyState("📡", "You're offline", "Check your internet connection and try again.") {
                    GradientButton("Retry", vm::bootstrap)
                }
            }
            BootState.Ready -> {
                val nav = rememberNavController()
                val required = Routes.required(user)
                val startDest = remember { required.let { if (it == Routes.DISCOVER) Routes.DISCOVER else it } }
                RouteGuard(nav, required)
                OpenChatHandler(nav, deps.deepLinks(), user != null && required == Routes.DISCOVER)
                AppNavHost(nav, startDest)
            }
        }

        val popup by deps.popups().popup.collectAsStateWithLifecycle()
        popup?.let { MatchPopup(it, me = user, onDismiss = deps.popups()::dismiss) }

        val call by deps.calls().state.collectAsStateWithLifecycle()
        AnimatedVisibility(call.active) { CallOverlay() }

        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 72.dp))
    }
}

/** Keeps navigation consistent with the account status the server reports. */
@Composable
private fun RouteGuard(nav: NavHostController, required: String) {
    val entry by nav.currentBackStackEntryAsState()
    val current = entry?.destination?.route
    LaunchedEffect(required, current) {
        if (current == null) return@LaunchedEffect
        val ok = when (required) {
            Routes.LOGIN -> current in Routes.auth
            Routes.DISCOVER -> current in Routes.main
            else -> current == required
        }
        if (!ok) nav.navigate(required) { popUpTo(0) { inclusive = true }; launchSingleTop = true }
    }
}

@Composable
private fun OpenChatHandler(nav: NavHostController, links: DeepLinks, active: Boolean) {
    val matchId by links.openChat.collectAsStateWithLifecycle()
    LaunchedEffect(matchId, active) {
        val id = matchId ?: return@LaunchedEffect
        if (!active) return@LaunchedEffect
        links.consumeOpenChat()
        nav.navigate(Routes.chat(id)) { launchSingleTop = true }
    }
}

@Composable
private fun AppNavHost(nav: NavHostController, start: String) {
    NavHost(nav, startDestination = start) {
        composable(Routes.LOGIN) { LoginScreen(onCodeSent = { phone -> nav.navigate(Routes.otp(phone)) }) }
        composable(Routes.OTP, arguments = listOf(navArgument("phone") { type = NavType.StringType })) {
            OtpScreen(onBack = { nav.popBackStack() })
        }
        composable(Routes.CONSENT) { ConsentScreen() }
        composable(Routes.VERIFY) { VerifyScreen() }
        composable(Routes.NOT_ELIGIBLE) { NotEligibleScreen() }
        composable(Routes.SETUP) { EditProfileScreen(setup = true, onDone = {}, onBack = {}) }

        composable(Routes.DISCOVER) { TabScaffold(nav, Routes.DISCOVER) { DiscoverScreen(onEditPrefs = { nav.navigate(Routes.EDIT) }) } }
        composable(Routes.LIKES) { TabScaffold(nav, Routes.LIKES) { LikesScreen() } }
        composable(Routes.CHATS) { TabScaffold(nav, Routes.CHATS) { ChatsScreen(onOpen = { nav.navigate(Routes.chat(it)) }, onDiscover = { nav.navigateTab(Routes.DISCOVER) }) } }
        composable(Routes.PROFILE) {
            TabScaffold(nav, Routes.PROFILE) {
                ProfileScreen(
                    onEdit = { nav.navigate(Routes.EDIT) },
                    onBlocked = { nav.navigate(Routes.BLOCKED) },
                    onSafety = { nav.navigate(Routes.SAFETY) },
                )
            }
        }
        composable(Routes.CHAT, arguments = listOf(navArgument("matchId") { type = NavType.StringType })) {
            ConversationScreen(onBack = { nav.popBackStack() })
        }
        composable(Routes.EDIT) { EditProfileScreen(setup = false, onDone = { nav.popBackStack() }, onBack = { nav.popBackStack() }) }
        composable(Routes.BLOCKED) { BlockedScreen(onBack = { nav.popBackStack() }) }
        composable(Routes.SAFETY) { SafetyScreen(onBack = { nav.popBackStack() }) }
    }
}

private fun NavHostController.navigateTab(route: String) = navigate(route) {
    popUpTo(Routes.DISCOVER) { saveState = true }
    launchSingleTop = true
    restoreState = true
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab(Routes.DISCOVER, "Discover", Icons.Filled.LocalFireDepartment),
    Tab(Routes.LIKES, "Likes", Icons.Filled.Favorite),
    Tab(Routes.CHATS, "Chats", Icons.Outlined.ChatBubbleOutline),
    Tab(Routes.PROFILE, "Profile", Icons.Outlined.Person),
)

@Composable
private fun TabScaffold(nav: NavHostController, current: String, content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val chat = remember { EntryPointAccessors.fromApplication(ctx.applicationContext, RootEntryPoint::class.java).chat() }
    val matches by chat.matches.collectAsStateWithLifecycle()
    val unread = matches.sumOf { it.unread }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                tabs.forEach { t ->
                    NavigationBarItem(
                        selected = current == t.route,
                        onClick = { if (current != t.route) nav.navigateTab(t.route) },
                        icon = {
                            BadgedBox(badge = { if (t.route == Routes.CHATS && unread > 0) Badge { Text(if (unread > 9) "9+" else "$unread") } }) {
                                Icon(t.icon, contentDescription = t.label)
                            }
                        },
                        label = { Text(t.label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = MaterialTheme.colorScheme.surfaceVariant,
                        ),
                    )
                }
            }
        },
    ) { pad -> Box(Modifier.padding(pad).fillMaxSize()) { content() } }
}
