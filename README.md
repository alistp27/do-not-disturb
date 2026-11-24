# Do Not Disturb — Iran spam SMS & call blocker

An Android app (Android 10+) that blocks the advertising calls and SMS that flood Iranian phone numbers, without losing OTP codes or bank messages.

## How it works

**Calls** (Android `CallScreeningService`, no need to replace your dialer)
- Rejects calls from the ad prefixes `+98940`, `+98941`, `+98942` (and `0940/0941/0942`).
- Contacts are never blocked.
- Optional: block hidden numbers, or every number not in your contacts.
- Every blocked call is shown in the **Calls** tab.

**SMS** (the app becomes your default SMS app — Android only lets the default SMS app stop messages)

Each incoming message is checked in this order; the first match wins:

| # | Rule | Result |
|---|------|--------|
| 1 | Sender is in contacts | Inbox |
| 2 | Sender is on your "Not spam" list | Inbox |
| 3 | Sender is on your "Block sender" list | Filtered |
| 4 | Contains an opt-out marker like `لغو11` | Filtered (spam) |
| 5 | Looks like an OTP (`کد`, `رمز`, `code`… + 4–8 digits) | Inbox |
| 6 | Looks like a bank message (`برداشت`, `واریز`, `مانده`, `ریال`…) | Inbox |
| 7 | Ad keywords (`تخفیف`, `جشنواره`, links…) | Filtered (spam) |
| 8 | Anything else | Filtered (quarantine) |

MMS messages go through the same rules, using their subject and text. Pictures from filtered MMS are not kept, and MMS from blocked senders is never downloaded, which saves mobile data.

Nothing is deleted automatically. Filtered messages wait in the **Filtered** tab, where you can mark them **Not spam** (moves to inbox and trusts the sender) or **Block sender**. Persian and Arabic digits are normalized before matching.

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

## Messaging

- **Conversations**: the Messages tab lists threads (with contact names and unread state); tap one for a chat view that updates live.
- **MMS**: receives MMS with text and pictures, and sends pictures (shrunk to under ~280 KB so carriers accept them). Messages to several people are sent as group MMS.
- The app includes its own MMS PDU encoder/decoder ([`MmsPdu.kt`](app/src/main/java/com/dnd/blocker/MmsPdu.kt)), because Android has no public one; it is covered by [`MmsPduTest.kt`](app/src/test/java/com/dnd/blocker/MmsPduTest.kt).

## Known limitations

- Group MMS you *receive* appears in a thread with the sender only, not the whole group.
- Video, audio and contact-card attachments are received but not displayed.
- MMS needs mobile data and the carrier's MMS settings (APN) on the phone.
- Not suitable for Google Play as-is (SMS permission policy); Cafe Bazaar / Myket are fine.

## Roadmap

- On-device spam classifier trained from your "Not spam" / "Block sender" choices
- Dismissing promotional notifications from selected apps
