package com.dnd.blocker

import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    private var prefillTo by mutableStateOf<String?>(null)
    private var startTab by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Notifier.ensureChannels(this)
        handleIntent(intent)
        setContent {
            MaterialTheme {
                App(prefillTo, startTab, onPrefillConsumed = { prefillTo = null })
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

private fun runtimePermissions(): Array<String> = buildList {
    add(Manifest.permission.READ_CONTACTS)
    add(Manifest.permission.READ_SMS)
    add(Manifest.permission.SEND_SMS)
    add(Manifest.permission.RECEIVE_SMS)
    if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
}.toTypedArray()

private fun ago(t: Long): String = DateUtils.getRelativeTimeSpanString(t).toString()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(prefillTo: String?, startTab: Int, onPrefillConsumed: () -> Unit) {
    val ctx = LocalContext.current
    var tab by rememberSaveable { mutableIntStateOf(startTab) }
    var refresh by remember { mutableIntStateOf(0) }
    var composeTo by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(startTab) { tab = startTab }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refresh++ }
    val roleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { refresh++ }

    LaunchedEffect(Unit) { permLauncher.launch(runtimePermissions()) }
    LaunchedEffect(prefillTo) {
        if (prefillTo != null) { composeTo = prefillTo; onPrefillConsumed() }
    }

    val requestRole: (String) -> Unit = { role ->
        val i = Roles.requestIntent(ctx, role)
        if (i != null) roleLauncher.launch(i) else refresh++
    }

    val titles = listOf("Inbox", "Filtered", "Calls", "Settings")
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(titles[tab]) },
                actions = {
                    IconButton(onClick = { refresh++ }) { Icon(Icons.Default.Refresh, "Refresh") }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                val icons = listOf(Icons.Default.Email, Icons.Default.Warning, Icons.Default.Call, Icons.Default.Settings)
                titles.forEachIndexed { i, t ->
                    NavigationBarItem(
                        selected = tab == i, onClick = { tab = i; refresh++ },
                        icon = { Icon(icons[i], t) }, label = { Text(t) }
                    )
                }
            }
        },
        floatingActionButton = {
            if (tab == 0) FloatingActionButton(onClick = { composeTo = "" }) {
                Icon(Icons.Default.Add, "New message")
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                0 -> InboxScreen(refresh, requestRole) { composeTo = it }
                1 -> FilteredScreen(refresh) { refresh++ }
                2 -> CallsScreen(refresh) { refresh++ }
                else -> SettingsScreen(refresh, requestRole) { refresh++ }
            }
        }
    }

    composeTo?.let { to ->
        ComposeDialog(to, onDismiss = { composeTo = null }) { address, body ->
            runCatching { SmsStore.send(ctx, address, body) }
                .onSuccess { Toast.makeText(ctx, "Sent", Toast.LENGTH_SHORT).show() }
                .onFailure { Toast.makeText(ctx, "Failed: ${it.message}", Toast.LENGTH_LONG).show() }
            composeTo = null
            refresh++
        }
    }
}

@Composable
private fun RoleBanner(text: String, button: String, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(12.dp), colors = CardDefaults.cardColors(MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.padding(16.dp)) {
            Text(text)
            Spacer(Modifier.height(8.dp))
            Button(onClick = onClick) { Text(button) }
        }
    }
}

@Composable
private fun EmptyState(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun InboxScreen(refresh: Int, requestRole: (String) -> Unit, onReply: (String) -> Unit) {
    val ctx = LocalContext.current
    val isDefault = remember(refresh) { Roles.held(ctx, RoleManager.ROLE_SMS) }
    val messages = remember(refresh) { SmsStore.recent(ctx) }
    Column {
        if (!isDefault) RoleBanner(
            "To filter spam SMS, this app must be your default SMS app.",
            "Make default SMS app"
        ) { requestRole(RoleManager.ROLE_SMS) }
        if (messages.isEmpty()) EmptyState("No messages")
        LazyColumn {
            items(messages, key = { it.id }) { m ->
                ListItem(
                    modifier = Modifier.clickable { onReply(m.address) },
                    headlineContent = { Text(m.address, fontWeight = FontWeight.SemiBold) },
                    supportingContent = { Text(m.body, maxLines = 3, overflow = TextOverflow.Ellipsis) },
                    trailingContent = { Text((if (m.type == 2) "Sent · " else "") + ago(m.date), style = MaterialTheme.typography.labelSmall) }
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun FilteredScreen(refresh: Int, onChanged: () -> Unit) {
    val ctx = LocalContext.current
    val db = remember { Db.get(ctx) }
    val items = remember(refresh) { db.filtered() }
    Column {
        if (items.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("${items.size} filtered", style = MaterialTheme.typography.labelLarge)
            TextButton(onClick = { db.clearFiltered(); onChanged() }) { Text("Delete all") }
        }
        if (items.isEmpty()) EmptyState("Nothing filtered yet")
        LazyColumn {
            items(items, key = { it.id }) { f ->
                Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(f.address, fontWeight = FontWeight.SemiBold)
                            Text(ago(f.date), style = MaterialTheme.typography.labelSmall)
                        }
                        Text("${f.verdict.lowercase()} · ${f.reason}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                        Spacer(Modifier.height(4.dp))
                        Text(f.body)
                        Row {
                            TextButton(onClick = {
                                SmsStore.insertInbox(ctx, f.address, f.body, f.date)
                                db.whitelist(SmsFilter.normalizeNumber(f.address))
                                db.deleteFiltered(f.id); onChanged()
                            }) { Text("Not spam") }
                            TextButton(onClick = {
                                db.blacklist(SmsFilter.normalizeNumber(f.address))
                                db.deleteFiltered(f.id); onChanged()
                            }) { Text("Block sender") }
                            TextButton(onClick = { db.deleteFiltered(f.id); onChanged() }) { Text("Delete") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CallsScreen(refresh: Int, onChanged: () -> Unit) {
    val ctx = LocalContext.current
    val db = remember { Db.get(ctx) }
    val calls = remember(refresh) { db.blockedCalls() }
    Column {
        if (calls.isNotEmpty()) TextButton(onClick = { db.clearBlockedCalls(); onChanged() }, modifier = Modifier.padding(horizontal = 4.dp)) { Text("Clear log") }
        if (calls.isEmpty()) EmptyState("No calls blocked yet")
        LazyColumn {
            items(calls, key = { it.id }) { c ->
                ListItem(
                    headlineContent = { Text(c.number) },
                    supportingContent = { Text(c.reason) },
                    trailingContent = { Text(ago(c.date), style = MaterialTheme.typography.labelSmall) }
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun SettingsScreen(refresh: Int, requestRole: (String) -> Unit, onChanged: () -> Unit) {
    val ctx = LocalContext.current
    val smsRole = remember(refresh) { Roles.held(ctx, RoleManager.ROLE_SMS) }
    val callRole = remember(refresh) { Roles.held(ctx, RoleManager.ROLE_CALL_SCREENING) }
    var blockHidden by remember { mutableStateOf(Prefs.blockHidden(ctx)) }
    var blockUnknown by remember { mutableStateOf(Prefs.blockAllUnknown(ctx)) }

    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Permissions", style = MaterialTheme.typography.titleMedium)
        RoleRow("Default SMS app", smsRole) { requestRole(RoleManager.ROLE_SMS) }
        RoleRow("Call screening (blocks 0940/0941/0942)", callRole) { requestRole(RoleManager.ROLE_CALL_SCREENING) }

        HorizontalDivider()
        Text("Calls", style = MaterialTheme.typography.titleMedium)
        SwitchRow("Block hidden numbers", blockHidden) { blockHidden = it; Prefs.setBlockHidden(ctx, it); onChanged() }
        SwitchRow("Block all numbers not in contacts", blockUnknown) { blockUnknown = it; Prefs.setBlockAllUnknown(ctx, it); onChanged() }

        HorizontalDivider()
        Text(
            "SMS from contacts, OTP codes and bank messages go to the inbox. " +
                "Ads (with \"لغو11\" etc.) and unknown senders go to Filtered, where nothing is lost.",
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun RoleRow(label: String, held: Boolean, onRequest: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        if (held) Text("Enabled ✓", color = MaterialTheme.colorScheme.primary)
        else Button(onClick = onRequest) { Text("Enable") }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun ComposeDialog(initialTo: String, onDismiss: () -> Unit, onSend: (String, String) -> Unit) {
    var to by remember { mutableStateOf(initialTo) }
    var body by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New message") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(to, { to = it }, label = { Text("To") }, singleLine = true)
                OutlinedTextField(body, { body = it }, label = { Text("Message") }, minLines = 3)
            }
        },
        confirmButton = {
            TextButton(enabled = to.isNotBlank() && body.isNotBlank(), onClick = { onSend(to.trim(), body) }) { Text("Send") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
