package com.dnd.blocker

import android.Manifest
import android.app.Activity
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

data class OpenThread(val id: Long, val addresses: List<String>)

/** Settings the UI reacts to immediately. */
class UiPrefs(ctx: Context) {
    var theme by mutableStateOf(Prefs.theme(ctx))
    var onboarded by mutableStateOf(Prefs.onboarded(ctx))
}

val LocalUiPrefs = staticCompositionLocalOf<UiPrefs> { error("UiPrefs not provided") }

class MainActivity : ComponentActivity() {
    private var prefillTo by mutableStateOf<String?>(null)
    private var startTab by mutableIntStateOf(0)

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(Lang.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Notifier.ensureChannels(this)
        handleIntent(intent)
        val prefs = UiPrefs(this)
        setContent {
            CompositionLocalProvider(LocalUiPrefs provides prefs) {
                AppTheme(prefs.theme) {
                    if (prefs.onboarded) {
                        App(prefillTo, startTab, onPrefillConsumed = { prefillTo = null })
                    } else {
                        Onboarding(onDone = {
                            Prefs.setOnboarded(this, true)
                            prefs.onboarded = true
                        })
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        if (i == null) return
        startTab = i.getIntExtra(EXTRA_TAB, startTab)
        val d = i.data ?: return
        if (d.scheme in setOf("sms", "smsto", "mms", "mmsto")) {
            prefillTo = d.schemeSpecificPart.substringBefore('?')
        }
    }

    companion object { const val EXTRA_TAB = "tab" }
}

// ---------------- Theme ----------------

// Brand: navy (trust, the cage) and amber (the caught ad).
private val LightColors = lightColorScheme(
    primary = Color(0xFF0C447C), onPrimary = Color.White,
    primaryContainer = Color(0xFFD6E4F7), onPrimaryContainer = Color(0xFF042C53),
    secondary = Color(0xFF535F70), onSecondary = Color.White,
    secondaryContainer = Color(0xFFD7E3F7), onSecondaryContainer = Color(0xFF101C2B),
    tertiary = Color(0xFF8A5100), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDDB5), onTertiaryContainer = Color(0xFF2B1700),
    background = Color(0xFFF8F9FC), onBackground = Color(0xFF191C20),
    surface = Color(0xFFF8F9FC), onSurface = Color(0xFF191C20),
    surfaceVariant = Color(0xFFDFE2EB), onSurfaceVariant = Color(0xFF43474E),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF2F3F7),
    surfaceContainer = Color(0xFFECEEF2), surfaceContainerHigh = Color(0xFFE6E8EC),
    surfaceContainerHighest = Color(0xFFE1E2E6),
    outline = Color(0xFF73777F), outlineVariant = Color(0xFFC3C6CF),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA6C8FF), onPrimary = Color(0xFF003061),
    primaryContainer = Color(0xFF0C447C), onPrimaryContainer = Color(0xFFD6E4FF),
    secondary = Color(0xFFBBC7DB), onSecondary = Color(0xFF253140),
    secondaryContainer = Color(0xFF3B4858), onSecondaryContainer = Color(0xFFD7E3F7),
    tertiary = Color(0xFFFFB95C), onTertiary = Color(0xFF482900),
    tertiaryContainer = Color(0xFF6A3C00), onTertiaryContainer = Color(0xFFFFDDB5),
    background = Color(0xFF0F141B), onBackground = Color(0xFFE1E2E8),
    surface = Color(0xFF0F141B), onSurface = Color(0xFFE1E2E8),
    surfaceVariant = Color(0xFF43474E), onSurfaceVariant = Color(0xFFC3C6CF),
    surfaceContainerLowest = Color(0xFF0A0E14), surfaceContainerLow = Color(0xFF171C23),
    surfaceContainer = Color(0xFF1B2027), surfaceContainerHigh = Color(0xFF252A32),
    surfaceContainerHighest = Color(0xFF30353D),
    outline = Color(0xFF8D9199), outlineVariant = Color(0xFF43474E),
)

@Composable
fun AppTheme(mode: String, content: @Composable () -> Unit) {
    val dark = when (mode) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }
    val activity = LocalContext.current as? ComponentActivity
    LaunchedEffect(dark) {
        val transparent = android.graphics.Color.TRANSPARENT
        val style = if (dark) SystemBarStyle.dark(transparent) else SystemBarStyle.light(transparent, transparent)
        activity?.enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
    }
    MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, content = content)
}

// ---------------- Shared helpers ----------------

/** Gatekeeper mode needs only contacts (to never touch their messages) and notifications. SMS permissions are for full mode. */
fun runtimePermissions(fullMode: Boolean): Array<String> = buildList {
    add(Manifest.permission.READ_CONTACTS)
    if (fullMode) {
        add(Manifest.permission.READ_SMS)
        add(Manifest.permission.SEND_SMS)
        add(Manifest.permission.RECEIVE_SMS)
        add(Manifest.permission.RECEIVE_MMS)
    }
    if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
}.toTypedArray()

/** Increments each time the screen comes back to the foreground (e.g. from system settings). */
@Composable
fun rememberResumeTick(): Int {
    val owner = LocalLifecycleOwner.current
    var tick by remember { mutableIntStateOf(0) }
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    return tick
}

@Composable
fun ChoiceChips(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (value, label) ->
            FilterChip(selected = selected == value, onClick = { onSelect(value) }, label = { Text(label) })
        }
    }
}

@Composable
fun LanguagePicker() {
    val ctx = LocalContext.current
    ChoiceChips(
        listOf(
            "system" to stringResource(R.string.lang_system),
            "fa" to stringResource(R.string.lang_fa),
            "en" to stringResource(R.string.lang_en),
        ),
        Prefs.language(ctx),
    ) { code ->
        if (code != Prefs.language(ctx)) {
            Prefs.setLanguage(ctx, code)
            (ctx as? Activity)?.recreate()
        }
    }
}

@Composable
fun ThemePicker() {
    val ctx = LocalContext.current
    val prefs = LocalUiPrefs.current
    ChoiceChips(
        listOf(
            "system" to stringResource(R.string.theme_system),
            "light" to stringResource(R.string.theme_light),
            "dark" to stringResource(R.string.theme_dark),
        ),
        prefs.theme,
    ) { mode ->
        Prefs.setTheme(ctx, mode)
        prefs.theme = mode
    }
}

// ---------------- Onboarding ----------------

@Composable
private fun Onboarding(onDone: () -> Unit) {
    val ctx = LocalContext.current
    var step by rememberSaveable { mutableIntStateOf(0) }
    var refresh by remember { mutableIntStateOf(0) }
    val resume = rememberResumeTick()
    val lastStep = 3

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { step = 1 }
    val roleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { refresh++ }

    val callHeld = remember(resume, refresh) { Roles.held(ctx, RoleManager.ROLE_CALL_SCREENING) }
    val notifHeld = remember(resume, refresh) { AppRules.accessGranted(ctx) }

    fun requestRole(role: String) {
        Roles.requestIntent(ctx, role)?.let { roleLauncher.launch(it) } ?: refresh++
    }

    BackHandler(enabled = step > 0) { step-- }

    Scaffold(
        bottomBar = {
            Row(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.widthIn(min = 88.dp)) {
                    if (step in 1 until lastStep) TextButton(onClick = { step++ }) { Text(stringResource(R.string.onb_skip)) }
                }
                Spacer(Modifier.weight(1f))
                StepDots(step, lastStep + 1)
                Spacer(Modifier.weight(1f))
                Button(onClick = {
                    when (step) {
                        0 -> permLauncher.launch(runtimePermissions(fullMode = false))
                        lastStep -> onDone()
                        else -> step++
                    }
                }) {
                    Text(stringResource(if (step == lastStep) R.string.onb_start else R.string.onb_next))
                }
            }
        }
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Spacer(Modifier.height(48.dp))
            when (step) {
                0 -> {
                    OnboardingHeader(R.drawable.ic_shield, R.string.onb_welcome_title, R.string.onb_welcome_body)
                    Text(stringResource(R.string.language), style = MaterialTheme.typography.titleSmall)
                    LanguagePicker()
                }
                1 -> {
                    OnboardingHeader(R.drawable.ic_call_block, R.string.onb_call_title, R.string.onb_call_body)
                    RoleAction(callHeld, R.string.enable) { requestRole(RoleManager.ROLE_CALL_SCREENING) }
                }
                2 -> {
                    OnboardingHeader(R.drawable.ic_sms_filter, R.string.onb_sms_title, R.string.onb_sms_body)
                    RoleAction(notifHeld, R.string.allow_access) { AppRules.openAccessSettings(ctx) }
                }
                else -> OnboardingHeader(R.drawable.ic_check_circle, R.string.onb_done_title, R.string.onb_done_body)
            }
        }
    }
}

@Composable
private fun OnboardingHeader(@DrawableRes icon: Int, @StringRes title: Int, @StringRes body: Int) {
    Box(
        Modifier.size(128.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painterResource(icon), contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(64.dp)
        )
    }
    Text(stringResource(title), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
    Text(
        stringResource(body), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun RoleAction(held: Boolean, @StringRes label: Int, onClick: () -> Unit) {
    if (held) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(stringResource(R.string.enabled), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium)
        }
    } else {
        Button(onClick = onClick) { Text(stringResource(label)) }
    }
}

@Composable
private fun StepDots(current: Int, count: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(count) { i ->
            Box(
                Modifier.size(if (i == current) 10.dp else 7.dp).background(
                    if (i == current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                    CircleShape
                )
            )
        }
    }
}

// ---------------- Main app shell ----------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(prefillTo: String?, startTab: Int, onPrefillConsumed: () -> Unit) {
    val ctx = LocalContext.current
    var tab by rememberSaveable { mutableIntStateOf(startTab) }
    var refresh by remember { mutableIntStateOf(0) }
    var openThread by remember { mutableStateOf<OpenThread?>(null) }
    var showNew by remember { mutableStateOf(false) }
    val names = remember { NameCache(ctx) }
    val version = refresh + rememberProviderChanges()
    val resume = rememberResumeTick()
    val fullMode = remember(refresh, resume) { Roles.held(ctx, RoleManager.ROLE_SMS) }
    // Tab keys stay fixed (notifications open tabs by key); Messages only exists in full mode.
    val tabs = if (fullMode) listOf(0, 1, 2, 3, 4) else listOf(1, 2, 3, 4)
    if (tab !in tabs) tab = 1

    LaunchedEffect(startTab) { tab = startTab; openThread = null }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refresh++ }
    val roleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { refresh++ }

    LaunchedEffect(fullMode) {
        val missing = runtimePermissions(fullMode).filter { !ctx.hasPermission(it) }
        if (missing.isNotEmpty()) permLauncher.launch(missing.toTypedArray())
    }
    LaunchedEffect(prefillTo) {
        val to = prefillTo ?: return@LaunchedEffect
        val addrs = splitAddresses(to)
        if (addrs.isEmpty()) showNew = true
        else openThread = OpenThread(Threads.threadIdFor(ctx, addrs), addrs)
        onPrefillConsumed()
    }

    val requestRole: (String) -> Unit = { role ->
        val i = Roles.requestIntent(ctx, role)
        if (i != null) roleLauncher.launch(i) else refresh++
    }

    val current = openThread
    if (current != null) {
        ConversationScreen(current, version, names, onBack = { openThread = null; refresh++ })
    } else {
        val titles = listOf(
            stringResource(R.string.tab_messages), stringResource(R.string.tab_filtered),
            stringResource(R.string.tab_calls), stringResource(R.string.tab_apps),
            stringResource(R.string.tab_settings),
        )
        val title = titles[tab]
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(title) },
                    actions = {
                        IconButton(onClick = { refresh++ }) {
                            Icon(Icons.Default.Refresh, stringResource(R.string.refresh))
                        }
                    }
                )
            },
            bottomBar = {
                NavigationBar {
                    tabs.forEach { i ->
                        val t = titles[i]
                        NavigationBarItem(
                            selected = tab == i, onClick = { tab = i; refresh++ },
                            icon = { TabIcon(i, t) }, label = { Text(t, maxLines = 1) }
                        )
                    }
                }
            },
            floatingActionButton = {
                if (tab == 0) FloatingActionButton(onClick = { showNew = true }) {
                    Icon(Icons.Default.Add, stringResource(R.string.new_conversation))
                }
            }
        ) { pad ->
            Box(Modifier.padding(pad).fillMaxSize()) {
                when (tab) {
                    0 -> ThreadsScreen(version, names, requestRole) { openThread = it }
                    1 -> FilteredScreen(version) { refresh++ }
                    2 -> CallsScreen(version) { refresh++ }
                    3 -> AppsScreen(version)
                    else -> SettingsScreen(version, requestRole)
                }
            }
        }
    }

    if (showNew) NewConversationDialog(onDismiss = { showNew = false }) { addrs ->
        showNew = false
        openThread = OpenThread(Threads.threadIdFor(ctx, addrs), addrs)
    }
}

@Composable
private fun TabIcon(index: Int, label: String) {
    when (index) {
        0 -> Icon(Icons.Default.Email, label)
        1 -> Icon(painterResource(R.drawable.ic_shield), label, Modifier.size(24.dp))
        2 -> Icon(painterResource(R.drawable.ic_call_block), label, Modifier.size(24.dp))
        3 -> Icon(painterResource(R.drawable.ic_bell_off), label, Modifier.size(24.dp))
        else -> Icon(Icons.Default.Settings, label)
    }
}
