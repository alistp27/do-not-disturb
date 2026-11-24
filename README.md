# تبلیغ‌گیر · Tabligh-gir

**Blocks ad calls and silences ad SMS for Iranian phone numbers, without replacing your phone or SMS app, and without internet access.**

- ✅ Ad calls from `0940`, `0941`, `0942` are rejected before your phone rings.
- ✅ Ad SMS notifications are removed silently; OTP codes, bank messages and contacts always get through.
- ✅ Promotional notifications from apps you pick are hidden.
- 🔒 **No internet permission.** CI fails the build if one is ever added. See [PRIVACY.md](PRIVACY.md).
- ❤️ Free, no ads. Support it on [Hamibash](https://hamibash.com/tablighgir).

Download the latest APK from [Releases](https://github.com/alistp27/do-not-disturb/releases/latest). Android 10+.

## Two modes

| | Gatekeeper (default) | Full mode (optional) |
|---|---|---|
| Your SMS app | Stays the same | Tabligh-gir becomes your SMS app |
| Ad SMS | Notification removed silently; message stays in your SMS app | Never reaches the inbox; kept in the **Ads** tab |
| Permissions | Call screening, notification access, contacts | + SMS |

Gatekeeper only acts on messages it is sure are ads (rules 3, 4, 7 and 8 below). Anything uncertain is left alone.

## How it works

**Calls** (Android `CallScreeningService`, no need to replace your dialer)
- Rejects calls from the ad prefixes `+98940`, `+98941`, `+98942` (and `0940/0941/0942`).
- Contacts are never blocked.
- Optional: block hidden numbers, or every number not in your contacts.
- Every blocked call is shown in the **Calls** tab.

**SMS**

Each incoming message is checked in this order; the first match wins:

| # | Rule | Result |
|---|------|--------|
| 1 | Sender is in contacts | Inbox |
| 2 | Sender is on your "Not spam" list | Inbox |
| 3 | Sender is on your "Block sender" list | Ads |
| 4 | Contains an opt-out marker like `لغو11` | Ads (spam) |
| 5 | Looks like an OTP (`کد`, `رمز`, `code`… + 4–8 digits) | Inbox |
| 6 | Looks like a bank message (`برداشت`, `واریز`, `مانده`, `ریال`…) | Inbox |
| 7 | Ad keywords (`تخفیف`, `جشنواره`, links…) | Ads (spam) |
| 8 | Learned spam score ≥ 90% | Ads (spam) |
| 9 | Learned spam score ≤ 10%, after you've taught it 5+ of each kind | Inbox |
| 10 | Anything else | Ads (quarantine) |

### On-device spam learning

A small Naive Bayes classifier ([`SpamModel.kt`](app/src/main/java/com/dnd/blocker/SpamModel.kt)) learns from your choices in the **Ads** tab: **Not spam** teaches it a good message, **Spam** and **Block sender** teach it spam. It starts with a few built-in Persian/English examples, normalizes Persian text (Arabic ي/ك, half-spaces, digits) and runs entirely on the phone. OTP and bank messages are checked before the classifier, so it can never hide them. You can see what it has learned, or reset it, in **Settings**.

MMS messages go through the same rules, using their subject and text. Pictures from filtered MMS are not kept, and MMS from blocked senders is never downloaded, which saves mobile data.

Nothing is deleted automatically. Filtered messages wait in the **Ads** tab, where you can mark them **Not spam** (moves to inbox and trusts the sender) or **Block sender**. Persian and Arabic digits are normalized before matching.

The filter rules live in [`SmsFilter.kt`](app/src/main/java/com/dnd/blocker/SmsFilter.kt) and are covered by unit tests in [`SmsFilterTest.kt`](app/src/test/java/com/dnd/blocker/SmsFilterTest.kt).

## Download / build

Every push is built by GitHub Actions. Open the **Actions** tab → latest **Build APK** run → download the `app-debug` artifact, unzip it and install `app-debug.apk`.

To build locally you need JDK 17, the Android SDK and Gradle 8.11:

```
gradle testDebugUnitTest assembleDebug
```

## First run

1. Allow contacts, SMS and notification permissions.
2. In **Settings**, enable **Default SMS app** and **Call screening**.

## Promotional notifications from apps

In the **Apps** tab, pick a mode for each app (shopping, food delivery, banking apps that push offers…):

- **Promos** — dismisses notifications that look like ads: the app marked them as a promotion, they contain an opt-out marker or ad keywords (`تخفیف`, `جشنواره`, `%`…), or the spam classifier scores them 90%+.
- **All** — dismisses every notification from that app.

In both modes, calls, alarms, navigation, ongoing/progress notifications, OTP codes and bank alerts are never touched. Dismissed notifications are listed under **Dismissed**; tap **Not an ad** to teach the classifier. This uses Android's notification access (a `NotificationListenerService`), which you grant once from the Apps tab.

## Messaging

- **Conversations**: the Messages tab lists threads (with contact names and unread state); tap one for a chat view that updates live.
- **MMS**: receives MMS with text and pictures, and sends pictures (shrunk to under ~280 KB so carriers accept them). Messages to several people are sent as group MMS.
- The app includes its own MMS PDU encoder/decoder ([`MmsPdu.kt`](app/src/main/java/com/dnd/blocker/MmsPdu.kt)), because Android has no public one; it is covered by [`MmsPduTest.kt`](app/src/test/java/com/dnd/blocker/MmsPduTest.kt).

## Known limitations

- Group MMS you *receive* appears in a thread with the sender only, not the whole group.
- Video, audio and contact-card attachments are received but not displayed.
- MMS needs mobile data and the carrier's MMS settings (APN) on the phone.
- Not suitable for Google Play as-is (SMS permission policy); Cafe Bazaar / Myket are fine.

## Releases

Pushing a tag like `v0.2.0` runs the **Release** workflow, which builds an APK signed with the project's release key and attaches it to a GitHub release. The version name comes from the tag.

It needs four repository secrets (Settings → Secrets and variables → Actions): `SIGNING_KEYSTORE_BASE64` (the `.jks` file, base64-encoded), `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS` and `SIGNING_KEY_PASSWORD`. Keep a backup of the keystore: if it's lost, users can't update to new versions without uninstalling.
