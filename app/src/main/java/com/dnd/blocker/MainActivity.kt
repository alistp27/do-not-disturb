package com.dnd.blocker

import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class OpenThread(val id: Long, val addresses: List<String>)

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
    add(Manifest.permission.RECEIVE_MMS)
    if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
}.toTypedArray()

private fun ago(t: Long): String = DateUtils.getRelativeTimeSpanString(t).toString()

private fun splitAddresses(s: String): List<String> =
    s.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }

/** Bumps a counter whenever the system SMS/MMS database changes, so screens refresh live. */
@Composable
private fun rememberProviderChanges(): Int {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { tick++ }
        }
        runCatching { ctx.contentResolver.registerContentObserver(Uri.parse("content://mms-sms/"), true, observer) }
        onDispose { ctx.contentResolver.unregisterContentObserver(observer) }
    }
    return tick
}

/** Caches contact names so lists don't hit the contacts DB on every recomposition. */
private class NameCache(private val ctx: Context) {
    private val cache = HashMap<String, String>()
    fun of(address: String): String = cache.getOrPut(address) { Contacts.name(ctx, address) ?: address }
    fun of(addresses: List<String>): String =
        if (addresses.isEmpty()) "(unknown)" else addresses.joinToString(", ") { of(it) }
}

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

    LaunchedEffect(startTab) { tab = startTab; openThread = null }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refresh++ }
    val roleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { refresh++ }

    LaunchedEffect(Unit) { permLauncher.launch(runtimePermissions()) }
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
        val titles = listOf("Messages", "Filtered", "Calls", "Settings")
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(titles[tab]) },
                    actions = { IconButton(onClick = { refresh++ }) { Icon(Icons.Default.Refresh, "Refresh") } }
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
                if (tab == 0) FloatingActionButton(onClick = { showNew = true }) {
                    Icon(Icons.Default.Add, "New conversation")
                }
            }
        ) { pad ->
            Box(Modifier.padding(pad).fillMaxSize()) {
                when (tab) {
                    0 -> ThreadsScreen(version, names, requestRole) { openThread = it }
                    1 -> FilteredScreen(version) { refresh++ }
                    2 -> CallsScreen(version) { refresh++ }
                    else -> SettingsScreen(version, requestRole) { refresh++ }
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

// ---------------- Conversations ----------------

@Composable
private fun ThreadsScreen(version: Int, names: NameCache, requestRole: (String) -> Unit, onOpen: (OpenThread) -> Unit) {
    val ctx = LocalContext.current
    val isDefault = remember(version) { Roles.held(ctx, RoleManager.ROLE_SMS) }
    val threads by produceState(emptyList<ThreadInfo>(), version) {
        value = withContext(Dispatchers.IO) { Threads.list(ctx) }
    }
    Column {
        if (!isDefault) RoleBanner(
            "To filter spam SMS, this app must be your default SMS app.",
            "Make default SMS app"
        ) { requestRole(RoleManager.ROLE_SMS) }
        if (threads.isEmpty()) EmptyState("No conversations")
        LazyColumn {
            items(threads, key = { it.id }) { t ->
                val weight = if (t.unread) FontWeight.Bold else FontWeight.Normal
                ListItem(
                    modifier = Modifier.clickable { onOpen(OpenThread(t.id, t.addresses)) },
                    headlineContent = {
                        Text(names.of(t.addresses), fontWeight = weight, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    supportingContent = {
                        val snippet = t.snippet.ifBlank { if (t.hasAttachment) "📷 Picture" else "MMS" }
                        Text(snippet, fontWeight = weight, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    },
                    trailingContent = { Text(ago(t.date), style = MaterialTheme.typography.labelSmall) }
                )
                HorizontalDivider()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConversationScreen(thread: OpenThread, version: Int, names: NameCache, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var localRefresh by remember { mutableIntStateOf(0) }
    var threadId by remember(thread) { mutableLongStateOf(thread.id) }
    var text by rememberSaveable { mutableStateOf("") }
    var image by remember { mutableStateOf<Uri?>(null) }
    var sending by remember { mutableStateOf(false) }
    val isGroup = thread.addresses.size > 1

    BackHandler(onBack = onBack)

    val messages by produceState(emptyList<ChatMessage>(), threadId, version, localRefresh) {
        value = withContext(Dispatchers.IO) { Threads.messages(ctx, threadId) }
    }
    LaunchedEffect(threadId, messages.size) {
        withContext(Dispatchers.IO) { Threads.markRead(ctx, threadId) }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) image = uri
    }

    fun send() {
        val body = text.trim()
        val img = image
        if (body.isEmpty() && img == null) return
        sending = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    if (img != null || isGroup) MmsStore.send(ctx, thread.addresses, body, img)
                    else SmsStore.send(ctx, thread.addresses.first(), body)
                    if (threadId <= 0) threadId = Threads.threadIdFor(ctx, thread.addresses)
                }
            }
            sending = false
            result.onSuccess { text = ""; image = null }
                .onFailure { Toast.makeText(ctx, "Not sent: ${it.message}", Toast.LENGTH_LONG).show() }
            localRefresh++
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(names.of(thread.addresses), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                }
            )
        },
        bottomBar = {
            Column(Modifier.imePadding()) {
                if (image != null) Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("📷 Picture attached", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                    TextButton(onClick = { image = null }) { Text("Remove") }
                }
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = {
                        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }) { Icon(Icons.Default.Add, "Attach picture") }
                    OutlinedTextField(
                        value = text, onValueChange = { text = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text(if (image != null || isGroup) "MMS message" else "Text message") },
                        maxLines = 5
                    )
                    IconButton(enabled = !sending && (text.isNotBlank() || image != null), onClick = { send() }) {
                        if (sending) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        else Icon(Icons.AutoMirrored.Filled.Send, "Send")
                    }
                }
            }
        }
    ) { pad ->
        if (messages.isEmpty()) {
            Box(Modifier.padding(pad)) { EmptyState("No messages yet") }
        } else {
            LazyColumn(
                Modifier.padding(pad).fillMaxSize(),
                reverseLayout = true,
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(messages.asReversed(), key = { it.key }) { m ->
                    MessageBubble(m, if (isGroup && m.incoming) names.of(m.address) else null)
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(m: ChatMessage, senderName: String?) {
    val bg = if (m.incoming) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.primaryContainer
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = if (m.incoming) Alignment.Start else Alignment.End
    ) {
        Surface(color = bg, shape = RoundedCornerShape(16.dp), modifier = Modifier.widthIn(max = 300.dp)) {
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                senderName?.let { Text(it, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold) }
                m.images.forEach { MmsImage(it) }
                if (m.text.isNotBlank()) Text(m.text)
            }
        }
        Text(
            listOfNotNull(ago(m.date), m.status).joinToString(" · "),
            style = MaterialTheme.typography.labelSmall,
            color = if (m.status == "Failed") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun MmsImage(uri: Uri) {
    val ctx = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(null, uri) {
        value = withContext(Dispatchers.IO) {
            runCatching { MmsStore.decodeScaled(ctx, uri, 720)?.asImageBitmap() }.getOrNull()
        }
    }
    bitmap?.let {
        Image(
            it, contentDescription = "Picture",
            modifier = Modifier.widthIn(max = 260.dp).heightIn(max = 340.dp).clip(RoundedCornerShape(10.dp)),
            contentScale = ContentScale.Fit
        )
    }
}

@Composable
private fun NewConversationDialog(onDismiss: () -> Unit, onStart: (List<String>) -> Unit) {
    var to by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New conversation") },
        text = {
            OutlinedTextField(
                to, { to = it },
                label = { Text("Phone number(s), comma separated") },
                singleLine = true
            )
        },
        confirmButton = {
            TextButton(enabled = splitAddresses(to).isNotEmpty(), onClick = { onStart(splitAddresses(to)) }) { Text("Start") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

// ---------------- Filtered / Calls / Settings ----------------

@Composable
private fun FilteredScreen(version: Int, onChanged: () -> Unit) {
    val ctx = LocalContext.current
    val db = remember { Db.get(ctx) }
    val items = remember(version) { db.filtered() }
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
private fun CallsScreen(version: Int, onChanged: () -> Unit) {
    val ctx = LocalContext.current
    val db = remember { Db.get(ctx) }
    val calls = remember(version) { db.blockedCalls() }
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
private fun SettingsScreen(version: Int, requestRole: (String) -> Unit, onChanged: () -> Unit) {
    val ctx = LocalContext.current
    val smsRole = remember(version) { Roles.held(ctx, RoleManager.ROLE_SMS) }
    val callRole = remember(version) { Roles.held(ctx, RoleManager.ROLE_CALL_SCREENING) }
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
            "SMS and MMS from contacts, OTP codes and bank messages go to Messages. " +
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
