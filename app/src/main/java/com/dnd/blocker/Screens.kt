package com.dnd.blocker

import android.app.role.RoleManager
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ---------------- Helpers ----------------

fun ago(t: Long): String = DateUtils.getRelativeTimeSpanString(t).toString()

fun splitAddresses(s: String): List<String> =
    s.split(',', ';', '،').map { it.trim() }.filter { it.isNotEmpty() }

/** Phone numbers have no letters, so in a right-to-left layout "+98…" would render as "…98+". Force LTR. */
fun ltr(s: String): String = if (s.none { it.isLetter() }) "⁦$s⁩" else s

/** Bumps whenever the system SMS/MMS database changes, so screens refresh live. */
@Composable
fun rememberProviderChanges(): Int {
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
class NameCache(private val ctx: Context) {
    private val cache = HashMap<String, String>()
    fun of(address: String): String = cache.getOrPut(address) { Contacts.name(ctx, address) ?: ltr(address) }
    fun of(addresses: List<String>, unknown: String): String =
        if (addresses.isEmpty()) unknown else addresses.joinToString(", ") { of(it) }
}

/** Filters store their reasons in English; translate them for display. */
@Composable
fun reasonText(reason: String): String {
    val pct = Regex("(\\d+)%").find(reason)?.groupValues?.get(1) ?: ""
    return when {
        reason == "blacklist" -> stringResource(R.string.reason_blacklist)
        reason == "ad opt-out marker" -> stringResource(R.string.reason_opt_out)
        reason == "ad keywords" -> stringResource(R.string.reason_ad_keywords)
        reason.startsWith("learned spam score") -> stringResource(R.string.reason_score, pct)
        reason.startsWith("unknown sender · spam score") -> stringResource(R.string.reason_unknown_score, pct)
        reason == "unknown sender" -> stringResource(R.string.reason_unknown)
        reason == "hidden number" -> stringResource(R.string.reason_hidden)
        reason.startsWith("ad prefix ") -> stringResource(R.string.reason_prefix, ltr(reason.removePrefix("ad prefix ")))
        reason == "unknown number" -> stringResource(R.string.reason_unknown_number)
        reason == "all notifications from this app" -> stringResource(R.string.reason_all_notifications)
        reason == "marked as a promotion by the app" -> stringResource(R.string.reason_promo_category)
        reason.startsWith("ad keyword + spam score") -> stringResource(R.string.reason_keyword_score, pct)
        else -> reason
    }
}

@Composable
fun Banner(text: String, button: String, onClick: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(12.dp),
        colors = CardDefaults.cardColors(MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(text, color = MaterialTheme.colorScheme.onSecondaryContainer)
            Spacer(Modifier.height(8.dp))
            Button(onClick = onClick) { Text(button) }
        }
    }
}

@Composable
fun EmptyState(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ---------------- Conversations ----------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ThreadsScreen(version: Int, names: NameCache, requestRole: (String) -> Unit, onOpen: (OpenThread) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val isDefault = remember(version) { Roles.held(ctx, RoleManager.ROLE_SMS) }
    var localRefresh by remember { mutableIntStateOf(0) }
    val threads by produceState(emptyList<ThreadInfo>(), version, localRefresh) {
        value = withContext(Dispatchers.IO) { Threads.list(ctx) }
    }
    var query by rememberSaveable { mutableStateOf("") }
    val bodyHits by produceState(emptySet<Long>(), query, version, localRefresh) {
        val term = query.trim()
        if (term.length < 2) { value = emptySet(); return@produceState }
        kotlinx.coroutines.delay(250)
        value = withContext(Dispatchers.IO) { Threads.searchBodies(ctx, term) }
    }
    var selected by remember { mutableStateOf(setOf<Long>()) }
    var confirmDelete by remember { mutableStateOf(false) }
    val unknown = stringResource(R.string.unknown_contact)
    val picture = stringResource(R.string.picture)
    val mms = stringResource(R.string.mms)
    val notDefault = stringResource(R.string.delete_needs_default)

    var archiveTick by remember { mutableIntStateOf(0) }
    var showArchived by rememberSaveable { mutableStateOf(false) }
    var lastArchived by remember { mutableStateOf<ThreadInfo?>(null) }
    val archive = remember(archiveTick) { Archive.all(ctx) }
    val archivedThreads = threads.filter { Archive.isArchived(archive, it) }
    if (showArchived) BackHandler { showArchived = false }
    LaunchedEffect(lastArchived) { if (lastArchived != null) { kotlinx.coroutines.delay(4000); lastArchived = null } }

    val q = SmsFilter.normalizeDigits(query.trim()).lowercase()
    val base = when {
        q.isNotEmpty() -> threads
        showArchived -> archivedThreads
        else -> threads.filter { !Archive.isArchived(archive, it) }
    }
    val shown = if (q.isEmpty()) base else base.filter { t ->
        t.id in bodyHits ||
            names.of(t.addresses, unknown).lowercase().contains(q) ||
            t.addresses.any { SmsFilter.normalizeDigits(it).contains(q) || SmsFilter.normalizeNumber(it).contains(q) } ||
            t.snippet.lowercase().contains(q)
    }
    LaunchedEffect(threads) { selected = selected.filter { id -> threads.any { it.id == id } }.toSet() }
    if (selected.isNotEmpty()) BackHandler { selected = emptySet() }

    Column {
        if (!isDefault) Banner(stringResource(R.string.sms_role_banner), stringResource(R.string.make_default)) {
            requestRole(RoleManager.ROLE_SMS)
        }
        if (selected.isNotEmpty()) {
            Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { selected = emptySet() }) { Icon(Icons.Default.Close, stringResource(R.string.cancel)) }
                    Text(stringResource(R.string.selected_count, selected.size), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    IconButton(onClick = {
                        selected = if (selected.size == shown.size) emptySet() else shown.map { it.id }.toSet()
                    }) { Icon(Icons.Default.CheckCircle, stringResource(R.string.select_all)) }
                    IconButton(onClick = {
                        if (Roles.held(ctx, RoleManager.ROLE_SMS)) confirmDelete = true
                        else Toast.makeText(ctx, notDefault, Toast.LENGTH_LONG).show()
                    }) { Icon(Icons.Default.Delete, stringResource(R.string.delete)) }
                }
            }
        } else if (threads.isNotEmpty() || query.isNotEmpty()) {
            OutlinedTextField(
                query, { query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text(stringResource(R.string.search_conversations)) },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = {
                    if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Clear, stringResource(R.string.clear)) }
                },
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
            )
        }
        if (q.isEmpty() && selected.isEmpty()) {
            if (showArchived) ListItem(
                modifier = Modifier.clickable { showArchived = false },
                leadingContent = { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) },
                headlineContent = { Text(stringResource(R.string.archived_title, archivedThreads.size), style = MaterialTheme.typography.titleMedium) },
                supportingContent = { Text(stringResource(R.string.archived_help)) },
            ) else if (archivedThreads.isNotEmpty()) ListItem(
                modifier = Modifier.clickable { showArchived = true },
                leadingContent = { Icon(Icons.Default.Lock, null) },
                headlineContent = { Text(stringResource(R.string.archived_title, archivedThreads.size)) },
            )
        }
        lastArchived?.let { t ->
            Surface(color = MaterialTheme.colorScheme.inverseSurface) {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.archived_one), Modifier.weight(1f), color = MaterialTheme.colorScheme.inverseOnSurface)
                    TextButton(onClick = { Archive.remove(ctx, t.id); archiveTick++; lastArchived = null }) {
                        Text(stringResource(R.string.undo), color = MaterialTheme.colorScheme.inversePrimary)
                    }
                }
            }
        }
        if (threads.isEmpty()) EmptyState(stringResource(R.string.no_conversations))
        else if (shown.isEmpty()) EmptyState(stringResource(if (q.isEmpty()) R.string.no_conversations else R.string.no_results))
        LazyColumn {
            items(shown, key = { it.id }) { t ->
                val weight = if (t.unread) FontWeight.Bold else FontWeight.Normal
                val isSel = t.id in selected
                val toggle = { selected = if (isSel) selected - t.id else selected + t.id }
                val isArch = Archive.isArchived(archive, t)
                val swipe = rememberSwipeToDismissBoxState(confirmValueChange = { v ->
                    if (v == SwipeToDismissBoxValue.Settled || selected.isNotEmpty()) false
                    else {
                        if (isArch) Archive.remove(ctx, t.id) else { Archive.add(ctx, t); lastArchived = t }
                        archiveTick++
                        true
                    }
                })
                SwipeToDismissBox(swipe, backgroundContent = {
                    val start = swipe.dismissDirection == SwipeToDismissBoxValue.StartToEnd
                    Box(
                        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.tertiaryContainer).padding(horizontal = 24.dp),
                        contentAlignment = if (start) Alignment.CenterStart else Alignment.CenterEnd,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(if (isArch) Icons.Default.Refresh else Icons.Default.Lock, null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
                            Text(stringResource(if (isArch) R.string.unarchive else R.string.archive), color = MaterialTheme.colorScheme.onTertiaryContainer)
                        }
                    }
                }) {
                ListItem(
                    modifier = Modifier.combinedClickable(
                        onClick = { if (selected.isNotEmpty()) toggle() else onOpen(OpenThread(t.id, t.addresses)) },
                        onLongClick = toggle,
                    ),
                    colors = if (isSel) ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                    else ListItemDefaults.colors(),
                    leadingContent = {
                        if (isSel) Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp)) {
                            Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.onPrimary) }
                        } else Avatar(names.of(t.addresses, unknown))
                    },
                    headlineContent = {
                        Text(names.of(t.addresses, unknown), fontWeight = weight, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    supportingContent = {
                        val snippet = t.snippet.ifBlank { if (t.hasAttachment) picture else mms }
                        Text(snippet, fontWeight = weight, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    },
                    trailingContent = { Text(ago(t.date), style = MaterialTheme.typography.labelSmall) }
                )
                }
            }
        }
    }

    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = {
            Text(
                if (selected.size == 1) stringResource(R.string.delete_conversation_title)
                else stringResource(R.string.delete_conversations_title, selected.size)
            )
        },
        text = { Text(stringResource(R.string.delete_conversations_body)) },
        confirmButton = {
            TextButton(onClick = {
                val ids = selected
                confirmDelete = false
                selected = emptySet()
                scope.launch {
                    withContext(Dispatchers.IO) { Threads.delete(ctx, ids) }
                    localRefresh++
                }
            }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun Avatar(name: String) {
    val letter = name.firstOrNull { it.isLetter() }?.uppercase()
    Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(40.dp)) {
        Box(contentAlignment = Alignment.Center) {
            if (letter != null) Text(letter, color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.titleMedium)
            else Icon(Icons.Default.Person, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationScreen(thread: OpenThread, version: Int, names: NameCache, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var localRefresh by remember { mutableIntStateOf(0) }
    var threadId by remember(thread) { mutableLongStateOf(thread.id) }
    var text by rememberSaveable { mutableStateOf("") }
    var image by remember { mutableStateOf<Uri?>(null) }
    var sending by remember { mutableStateOf(false) }
    val isGroup = thread.addresses.size > 1
    val notSent = stringResource(R.string.not_sent, "%s")
    val unknown = stringResource(R.string.unknown_contact)

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
                .onFailure { Toast.makeText(ctx, notSent.format(it.message ?: ""), Toast.LENGTH_LONG).show() }
            localRefresh++
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(names.of(thread.addresses, unknown), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
                }
            )
        },
        bottomBar = {
            Surface(tonalElevation = 2.dp) {
                Column(Modifier.windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))) {
                    if (image != null) Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Done, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.picture_attached), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                        TextButton(onClick = { image = null }) { Text(stringResource(R.string.remove)) }
                    }
                    Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = {
                            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        }) { Icon(Icons.Default.AddCircle, stringResource(R.string.attach_picture)) }
                        OutlinedTextField(
                            value = text, onValueChange = { text = it },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text(stringResource(if (image != null || isGroup) R.string.hint_mms else R.string.hint_sms)) },
                            shape = RoundedCornerShape(24.dp),
                            maxLines = 5
                        )
                        IconButton(enabled = !sending && (text.isNotBlank() || image != null), onClick = { send() }) {
                            if (sending) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            else Icon(Icons.AutoMirrored.Filled.Send, stringResource(R.string.send), tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    ) { pad ->
        if (messages.isEmpty()) {
            Box(Modifier.padding(pad)) { EmptyState(stringResource(R.string.no_messages)) }
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
    val bg = if (m.incoming) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.primaryContainer
    val fg = if (m.incoming) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onPrimaryContainer
    val status = when (m.status) {
        "Sending…" -> stringResource(R.string.status_sending)
        "Failed" -> stringResource(R.string.status_failed)
        else -> null
    }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (m.incoming) Alignment.Start else Alignment.End) {
        Surface(color = bg, contentColor = fg, shape = RoundedCornerShape(18.dp), modifier = Modifier.widthIn(max = 300.dp)) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                senderName?.let { Text(it, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold) }
                m.images.forEach { MmsImage(it) }
                if (m.text.isNotBlank()) Text(m.text)
            }
        }
        Text(
            listOfNotNull(ago(m.date), status).joinToString(" · "),
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
            it, contentDescription = stringResource(R.string.picture),
            modifier = Modifier.widthIn(max = 260.dp).heightIn(max = 340.dp).clip(RoundedCornerShape(12.dp)),
            contentScale = ContentScale.Fit
        )
    }
}

@Composable
fun NewConversationDialog(onDismiss: () -> Unit, onStart: (List<String>) -> Unit) {
    var to by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.new_conversation)) },
        text = {
            OutlinedTextField(to, { to = it }, label = { Text(stringResource(R.string.phone_numbers)) }, singleLine = true)
        },
        confirmButton = {
            TextButton(enabled = splitAddresses(to).isNotEmpty(), onClick = { onStart(splitAddresses(to)) }) {
                Text(stringResource(R.string.start))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}

// ---------------- Filtered ----------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FilteredScreen(version: Int, onChanged: () -> Unit) {
    val ctx = LocalContext.current
    val db = remember { Db.get(ctx) }
    val items = remember(version) { db.filtered() }
    Column {
        if (items.isNotEmpty()) Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
        ) {
            Text(stringResource(R.string.filtered_count, items.size), style = MaterialTheme.typography.labelLarge)
            TextButton(onClick = { db.clearFiltered(); onChanged() }) { Text(stringResource(R.string.delete_all)) }
        }
        if (items.any { it.source == Db.SOURCE_NOTIFICATION }) Text(
            stringResource(R.string.gate_note),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
        )
        if (items.isEmpty()) EmptyState(stringResource(R.string.nothing_filtered))
        LazyColumn {
            items(items, key = { it.id }) { f ->
                ElevatedCard(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Column(Modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 4.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(ltr(f.address), fontWeight = FontWeight.SemiBold)
                            Text(ago(f.date), style = MaterialTheme.typography.labelSmall)
                        }
                        val verdict = stringResource(if (f.verdict == "SPAM") R.string.verdict_spam else R.string.verdict_quarantine)
                        Text(
                            "$verdict · ${reasonText(f.reason)}",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(f.body)
                        FlowRow {
                            TextButton(onClick = {
                                if (f.source == Db.SOURCE_SMS) SmsStore.insertInbox(ctx, f.address, f.body, f.date)
                                db.whitelist(SmsFilter.normalizeNumber(f.address))
                                Learner.learn(ctx, f.body, isSpam = false)
                                db.deleteFiltered(f.id); onChanged()
                            }) { Text(stringResource(R.string.not_spam)) }
                            TextButton(onClick = {
                                Learner.learn(ctx, f.body, isSpam = true)
                                db.deleteFiltered(f.id); onChanged()
                            }) { Text(stringResource(R.string.spam)) }
                            TextButton(onClick = {
                                db.blacklist(SmsFilter.normalizeNumber(f.address))
                                Learner.learn(ctx, f.body, isSpam = true)
                                db.deleteFiltered(f.id); onChanged()
                            }) { Text(stringResource(R.string.block_sender)) }
                            TextButton(onClick = { db.deleteFiltered(f.id); onChanged() }) { Text(stringResource(R.string.delete)) }
                        }
                    }
                }
            }
        }
    }
}

// ---------------- Calls ----------------

@Composable
fun CallsScreen(version: Int, onChanged: () -> Unit) {
    val ctx = LocalContext.current
    val db = remember { Db.get(ctx) }
    val calls = remember(version) { db.blockedCalls() }
    Column {
        if (calls.isNotEmpty()) TextButton(
            onClick = { db.clearBlockedCalls(); onChanged() }, modifier = Modifier.padding(horizontal = 4.dp)
        ) { Text(stringResource(R.string.clear_log)) }
        if (calls.isEmpty()) EmptyState(stringResource(R.string.no_calls))
        LazyColumn {
            items(calls, key = { it.id }) { c ->
                ListItem(
                    leadingContent = { Icon(Icons.Default.Call, null, tint = MaterialTheme.colorScheme.error) },
                    headlineContent = { Text(if (c.number == "hidden") stringResource(R.string.reason_hidden) else ltr(c.number)) },
                    supportingContent = { Text(reasonText(c.reason)) },
                    trailingContent = { Text(ago(c.date), style = MaterialTheme.typography.labelSmall) }
                )
            }
        }
    }
}

// ---------------- Apps (promotional notifications) ----------------

@Composable
fun AppsScreen(version: Int) {
    val ctx = LocalContext.current
    val resume = rememberResumeTick()
    val granted = remember(version, resume) { AppRules.accessGranted(ctx) }
    var showLog by rememberSaveable { mutableStateOf(false) }
    var logVersion by remember { mutableIntStateOf(0) }
    val dismissed = remember(version, resume, logVersion) { Db.get(ctx).dismissed() }

    Column {
        if (!granted) Banner(stringResource(R.string.apps_access_banner), stringResource(R.string.allow_access)) {
            AppRules.openAccessSettings(ctx)
        }
        Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !showLog, onClick = { showLog = false }, label = { Text(stringResource(R.string.tab_apps)) })
            FilterChip(
                selected = showLog, onClick = { showLog = true },
                label = { Text(stringResource(R.string.dismissed_count, dismissed.size)) }
            )
        }
        if (showLog) DismissedList(dismissed) { logVersion++ } else AppList()
    }
}

@Composable
private fun AppList() {
    val ctx = LocalContext.current
    var rules by remember { mutableStateOf(AppRules.all(ctx)) }
    var query by rememberSaveable { mutableStateOf("") }
    val apps by produceState<List<AppEntry>?>(null) {
        value = withContext(Dispatchers.IO) {
            val selected = AppRules.all(ctx)
            // Apps already selected go first; the order is fixed so rows don't jump when toggled.
            AppRules.launchableApps(ctx).sortedBy { if (it.pkg in selected) 0 else 1 }
        }
    }
    Column {
        Text(
            stringResource(R.string.apps_help),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
        )
        OutlinedTextField(
            query, { query = it },
            placeholder = { Text(stringResource(R.string.search_apps)) }, singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, null) },
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
        )
        val list = apps
        if (list == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            LazyColumn {
                items(list.filter { query.isBlank() || it.label.contains(query, ignoreCase = true) }, key = { it.pkg }) { app ->
                    AppRow(app, rules[app.pkg]) { mode ->
                        AppRules.set(ctx, app.pkg, mode)
                        rules = AppRules.all(ctx)
                    }
                }
            }
        }
    }
}

@Composable
private fun AppRow(app: AppEntry, mode: AppMode?, onMode: (AppMode?) -> Unit) {
    ListItem(
        leadingContent = { AppIcon(app.pkg) },
        headlineContent = { Text(app.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = mode == null, onClick = { onMode(null) }, label = { Text(stringResource(R.string.mode_off)) })
                FilterChip(selected = mode == AppMode.PROMO, onClick = { onMode(AppMode.PROMO) }, label = { Text(stringResource(R.string.mode_promos)) })
                FilterChip(selected = mode == AppMode.ALL, onClick = { onMode(AppMode.ALL) }, label = { Text(stringResource(R.string.mode_all)) })
            }
        }
    )
}

@Composable
private fun AppIcon(pkg: String) {
    val ctx = LocalContext.current
    val icon by produceState<ImageBitmap?>(null, pkg) {
        value = withContext(Dispatchers.IO) {
            runCatching { ctx.packageManager.getApplicationIcon(pkg).toBitmap(96, 96).asImageBitmap() }.getOrNull()
        }
    }
    Box(Modifier.size(40.dp)) { icon?.let { Image(it, contentDescription = null, Modifier.fillMaxSize()) } }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DismissedList(items: List<DismissedNotification>, onChanged: () -> Unit) {
    val ctx = LocalContext.current
    val db = remember { Db.get(ctx) }
    if (items.isEmpty()) { EmptyState(stringResource(R.string.no_dismissed)); return }
    LazyColumn {
        item {
            TextButton(onClick = { db.clearDismissed(); onChanged() }, modifier = Modifier.padding(horizontal = 4.dp)) {
                Text(stringResource(R.string.clear_log))
            }
        }
        items(items, key = { it.id }) { d ->
            ElevatedCard(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                Column(Modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 4.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(d.app, fontWeight = FontWeight.SemiBold)
                        Text(ago(d.date), style = MaterialTheme.typography.labelSmall)
                    }
                    Text(reasonText(d.reason), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    if (d.title.isNotBlank()) Text(d.title, fontWeight = FontWeight.Medium)
                    if (d.text.isNotBlank()) Text(d.text)
                    FlowRow {
                        TextButton(onClick = {
                            Learner.learn(ctx, "${d.title}\n${d.text}", isSpam = false)
                            db.deleteDismissed(d.id); onChanged()
                        }) { Text(stringResource(R.string.not_an_ad)) }
                        TextButton(onClick = {
                            ctx.packageManager.getLaunchIntentForPackage(d.pkg)?.let { runCatching { ctx.startActivity(it) } }
                        }) { Text(stringResource(R.string.open_app)) }
                        TextButton(onClick = { db.deleteDismissed(d.id); onChanged() }) { Text(stringResource(R.string.delete)) }
                    }
                }
            }
        }
    }
}

// ---------------- Settings ----------------

@Composable
fun SettingsScreen(version: Int, requestRole: (String) -> Unit) {
    val ctx = LocalContext.current
    val uiPrefs = LocalUiPrefs.current
    val resume = rememberResumeTick()
    val smsRole = remember(version, resume) { Roles.held(ctx, RoleManager.ROLE_SMS) }
    val callRole = remember(version, resume) { Roles.held(ctx, RoleManager.ROLE_CALL_SCREENING) }
    val notifAccess = remember(version, resume) { AppRules.accessGranted(ctx) }
    var blockHidden by remember { mutableStateOf(Prefs.blockHidden(ctx)) }
    var blockUnknown by remember { mutableStateOf(Prefs.blockAllUnknown(ctx)) }
    var learnVersion by remember { mutableIntStateOf(0) }
    val model = remember(version, learnVersion) { Learner.model(ctx) }
    var confirmReset by remember { mutableStateOf(false) }

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SectionTitle(R.string.permissions)
        RoleRow(stringResource(R.string.call_screening), callRole) { requestRole(RoleManager.ROLE_CALL_SCREENING) }
        RoleRow(stringResource(R.string.notif_access), notifAccess) { AppRules.openAccessSettings(ctx) }
        Text(stringResource(R.string.privacy_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        HorizontalDivider()
        SectionTitle(R.string.full_mode)
        Text(
            stringResource(if (smsRole) R.string.full_mode_on else R.string.full_mode_body),
            style = MaterialTheme.typography.bodySmall
        )
        if (smsRole) OutlinedButton(onClick = { Links.openDefaultApps(ctx) }) { Text(stringResource(R.string.turn_off)) }
        else OutlinedButton(onClick = { requestRole(RoleManager.ROLE_SMS) }) { Text(stringResource(R.string.turn_on)) }

        HorizontalDivider()
        SectionTitle(R.string.calls_section)
        SwitchRow(stringResource(R.string.block_hidden), blockHidden) { blockHidden = it; Prefs.setBlockHidden(ctx, it) }
        SwitchRow(stringResource(R.string.block_unknown), blockUnknown) { blockUnknown = it; Prefs.setBlockAllUnknown(ctx, it) }

        HorizontalDivider()
        SectionTitle(R.string.appearance)
        Text(stringResource(R.string.language), style = MaterialTheme.typography.labelLarge)
        LanguagePicker()
        Text(stringResource(R.string.theme), style = MaterialTheme.typography.labelLarge)
        ThemePicker()

        HorizontalDivider()
        SectionTitle(R.string.learning)
        Text(
            stringResource(R.string.learned, model.userSpam, model.userHam) + " " +
                if (model.canAutoInbox) stringResource(R.string.learning_ready)
                else stringResource(R.string.learning_not_ready, SpamModel.MIN_USER_EXAMPLES),
            style = MaterialTheme.typography.bodySmall
        )
        Text(stringResource(R.string.learning_local), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        OutlinedButton(onClick = { confirmReset = true }) { Text(stringResource(R.string.reset_learning)) }
        if (confirmReset) AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text(stringResource(R.string.reset_title)) },
            text = { Text(stringResource(R.string.reset_body)) },
            confirmButton = {
                TextButton(onClick = { Learner.reset(ctx); confirmReset = false; learnVersion++ }) { Text(stringResource(R.string.reset)) }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text(stringResource(R.string.cancel)) } }
        )

        HorizontalDivider()
        SectionTitle(R.string.support)
        Text(stringResource(R.string.support_body), style = MaterialTheme.typography.bodySmall)
        Button(
            onClick = { Links.open(ctx, Links.DONATE) },
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        ) {
            Icon(Icons.Default.Favorite, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.donate))
        }

        HorizontalDivider()
        Text(stringResource(R.string.settings_footer), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = {
            Prefs.setOnboarded(ctx, false)
            uiPrefs.onboarded = false
        }) { Text(stringResource(R.string.run_setup)) }
    }
}

@Composable
private fun SectionTitle(res: Int) {
    Text(stringResource(res), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun RoleRow(label: String, held: Boolean, onRequest: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        if (held) Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.enabled), color = MaterialTheme.colorScheme.primary)
        } else Button(onClick = onRequest) { Text(stringResource(R.string.enable)) }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
