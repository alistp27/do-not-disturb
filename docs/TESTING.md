# Testing Tabligh-gir (تبلیغ‌گیر) on an emulator

Thanks for helping! This takes about 30–45 minutes. You'll install the app on an Android emulator, send it fake ad SMS and calls, and send back screenshots plus a short report.

## Retest for 1.0.1 (short, about 10 minutes)

Already tested 1.0.0? You only need this section.

1. Download `tablighgir-v1.0.1.apk` from https://github.com/alistp27/do-not-disturb/releases/latest and drag it onto the emulator. It updates 1.0.0 in place.
2. Run these checks:

| # | Do this | Expected |
|---|---|---|
| R1 | Settings → language **فارسی**, open **Calls** and **Ads** tabs | Times read like «۶ دقیقه پیش» / «همین الان», fully Persian, number on the correct side |
| R2 | Switch back to English | Times read like "6 min ago" / "just now" |
| R3 | Ads tab with exactly one ad caught | Says "1 ad caught" (not "1 ads") |
| R4 | Android Settings → Apps → Default apps → **Caller ID & spam app** → set to None, then open Tabligh-gir | Red warning on the main screen: call screening is off |
| R5 | Android Settings → Notifications → **Device & app notifications** → turn off Tabligh-gir, open the app | Red warning: notification access is off |
| R6 | Tap the warning | Opens the app's Settings tab; turn both back on → warning disappears |
| R7 | Clear app data (Settings → Apps → Tabligh-gir → Storage → Clear), redo the setup 2–3 times | Setup never skips call screening or notification access. If it does, the red warning must show |

Report:

```
Emulator: Pixel 8, API __
App version: 1.0.1
R1 __ R2 __ R3 __ R4 __ R5 __ R6 __ R7 __
Screenshots: R1 (Calls + Ads in فارسی), R4/R5 warning
Problems / notes:
```

---

## 1. Set up the emulator (once)

1. Install **Android Studio**: https://developer.android.com/studio
2. Open it → **More Actions → Virtual Device Manager → Create device**.
3. Pick **Pixel 8** → system image **API 34 or 35, "Google APIs"** (not "Google Play", not "ATD") → Finish.
4. Press ▶ to start it. First boot takes a few minutes.

## 2. Install the app

1. Download `tablighgir-v1.0.1.apk` from
   https://github.com/alistp27/do-not-disturb/releases/latest
2. **Drag the APK file onto the emulator window.** It installs by itself.
3. Open **تبلیغ‌گیر** from the app drawer.

## 3. Add one contact

Open the emulator's **Contacts** app and add a contact named **Test Friend** with number **09121112233**.

## 4. How to send fake SMS and calls

In the emulator's side toolbar click **⋯ (Extended controls) → Phone**.

- **SMS:** put a number in *From*, type the text in the message box, click **Send message**.
- **Call:** put a number in *From* and click **Call device**. Click **End call** afterwards if it rings.

---

## 5. Test cases

Do them in order. For each one, write ✅ or ❌, and take a screenshot whenever something looks wrong or interesting.

### A. Setup

| # | Do this | Expected |
|---|---|---|
| A1 | Open the app the first time | Welcome screen. Try switching language to فارسی and back to English |
| A2 | Tap through setup, allow **call screening** and **notification access** | Each step shows a green "Enabled" ✓ |
| A3 | Finish setup | Tabs: **Ads, Calls, Apps, Settings** (no Messages tab yet) |

### B. Calls

| # | Do this | Expected |
|---|---|---|
| B1 | Call from **09401234567** | Phone does **not** ring. Shows up in the **Calls** tab |
| B2 | Call from **09421234567** | Same as B1 |
| B3 | Call from **09121112233** (Test Friend) | Rings normally |
| B4 | Call from **09351234567** | Rings normally |

### C. SMS in gatekeeper mode (default)

For each SMS, watch the **notification**. "Removed" means it appears briefly or not at all. The message will still be in the Messages app; that's expected.

| # | From | Text | Expected |
|---|---|---|---|
| C1 | 50004123 | `جشنواره تخفیف ۷۰٪ فقط امروز! خرید از سایت ما لغو11` | Notification **removed**, listed in **Ads** tab |
| C2 | 30001234 | `تخفیف ویژه یلدا با کد هدیه، همین حالا خرید کنید` | Notification **removed** |
| C3 | 9850001 | `کد تایید شما: 482913` | Notification **stays** |
| C4 | 9830002 | `برداشت: 1,250,000 ریال مانده: 8,400,000 ریال` | Notification **stays** |
| C5 | 09121112233 | `تخفیف داری بیام بخرم؟` | Notification **stays** (contact) |
| C6 | 09351234567 | `سلام فردا ساعت ۵ میای؟` | Notification **stays** |
| C7 | In **Ads** tab, tap **Not an ad** on C2 | It disappears from the list |
| C8 | Tap **Block sender** on C1, then send C1's sender any text | Notification **removed** |

### D. Full mode (optional mode)

| # | Do this | Expected |
|---|---|---|
| D1 | Settings → **Full mode → Turn on**, accept "default SMS app" | A **Messages** tab appears |
| D2 | Send C1 again | Not in Messages tab; appears in **Ads** tab |
| D3 | Send C3 again | Appears in **Messages** tab with a notification |
| D4 | Open a conversation and reply | Message sends (shows as sent) |
| D5 | Long-press a conversation, swipe another one sideways | Select/delete and archive work |
| D6 | Settings → **Full mode → Turn off**, pick Messages as SMS app | Messages tab disappears |

### E. Everything else

| # | Do this | Expected |
|---|---|---|
| E1 | **Apps** tab: set any app to **Promos** | Saves the choice |
| E2 | Settings: switch theme to **Dark** | Whole app turns dark, readable |
| E3 | Settings: switch language to **فارسی** | Layout flips right-to-left, numbers still read correctly |
| E4 | Settings: tap **Support us** | Browser opens the Hamibash page |
| E5 | Look at the home screen icon | Navy icon with a cage and an amber megaphone |

---

## 6. Screenshots to send

1. Each setup screen (A1–A3)
2. Calls tab after B1–B4
3. Ads tab after C1–C8
4. The notification shade right after C3 (OTP should be there)
5. Settings screen (light and dark)
6. Full mode Messages tab (D1–D3)
7. Anything that looks broken, cut off, or wrong in Persian

To take one: emulator toolbar → **camera icon**. Files land on your desktop.

## 7. Report

Copy this, fill it in, send with the screenshots:

```
Emulator: Pixel 8, API __
App version: 1.0.1
A1 __ A2 __ A3 __
B1 __ B2 __ B3 __ B4 __
C1 __ C2 __ C3 __ C4 __ C5 __ C6 __ C7 __ C8 __
D1 __ D2 __ D3 __ D4 __ D5 __ D6 __
E1 __ E2 __ E3 __ E4 __ E5 __
Problems / notes:
```

If something fails, write exactly what you did, what you expected and what happened. Thank you! 🙏
