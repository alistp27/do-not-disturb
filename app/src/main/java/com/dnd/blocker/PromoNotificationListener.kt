package com.dnd.blocker

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat

/** Dismisses promotional notifications from the apps the user selected. */
class PromoNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        // Clean up anything already sitting in the shade.
        runCatching { activeNotifications?.forEach { onNotificationPosted(it) } }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        if (SmsGate.applies(this, sbn.packageName)) {
            SmsGate.onPosted(this, sbn) { key -> cancelNotification(key) }
            return
        }
        val mode = AppRules.mode(this, sbn.packageName) ?: return
        val n = sbn.notification ?: return
        val extras = n.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString() ?: ""
        val ongoing = n.flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE) != 0
        val isSummary = n.flags and Notification.FLAG_GROUP_SUMMARY != 0

        val reason = NotificationFilter.decide(mode, title, text, ongoing, n.category, Learner.model(this)) ?: return
        cancelNotification(sbn.key)
        if (!isSummary && (title.isNotBlank() || text.isNotBlank())) {
            Db.get(this).addDismissed(sbn.packageName, AppRules.label(this, sbn.packageName), title, text, reason)
        }
    }
}

data class AppEntry(val pkg: String, val label: String)

object AppRules {
    @Volatile private var cache: Map<String, AppMode>? = null

    fun mode(ctx: Context, pkg: String): AppMode? =
        (cache ?: Db.get(ctx).appRules().also { cache = it })[pkg]

    fun all(ctx: Context): Map<String, AppMode> = cache ?: Db.get(ctx).appRules().also { cache = it }

    fun set(ctx: Context, pkg: String, mode: AppMode?) {
        Db.get(ctx).setAppRule(pkg, mode)
        cache = null
    }

    fun label(ctx: Context, pkg: String): String = runCatching {
        val pm = ctx.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    fun launchableApps(ctx: Context): List<AppEntry> {
        val pm = ctx.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            .map { AppEntry(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
            .filter { it.pkg != ctx.packageName }
            .distinctBy { it.pkg }
            .sortedBy { it.label.lowercase() }
    }

    fun accessGranted(ctx: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)

    /** Opens the system screen where the user grants notification access. */
    fun openAccessSettings(ctx: Context) {
        val general = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        val intent = if (Build.VERSION.SDK_INT >= 30) {
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(
                Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                ComponentName(ctx, PromoNotificationListener::class.java).flattenToString()
            )
        } else general
        runCatching { ctx.startActivity(intent) }.onFailure { runCatching { ctx.startActivity(general) } }
    }
}
