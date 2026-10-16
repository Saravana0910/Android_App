# Maeva Android App

Native Android shell for the Maeva / NEST Salesforce Experience Cloud site.

This app replaces the retiring vendor-built (Bryj) application. All business
screens are the **existing Experience Cloud site rendered in a WebView** — no
business logic is reimplemented natively. A small native layer provides the
things a browser cannot: push notifications, notification deep links,
navigation policy, file upload/download and session cleanup.

| | |
|---|---|
| Testing launch URL | `https://orgfarm-84cc9e3664-dev-ed.develop.my.site.com/login` |
| Package | `com.renault.maeva` |
| Language | Kotlin (View system + ViewBinding, no Compose) |
| minSdk / targetSdk | 26 / 35 |
| Toolchain | JDK 17, Gradle 8.11.1, AGP 8.7.3, Kotlin 2.0.21 |

---

## 1. Repository layout

```
MAEVA/
├─ .github/workflows/android.yml     GitHub Actions build pipeline
└─ android/                          Gradle root
   ├─ gradlew / gradlew.bat          Gradle wrapper (committed)
   ├─ gradle/libs.versions.toml      Version catalog
   ├─ gradle.properties              Site URL configuration
   └─ app/
      ├─ build.gradle.kts
      ├─ google-services.json.template
      └─ src/main/
         ├─ AndroidManifest.xml
         ├─ res/
         └─ java/com/renault/maeva/
            ├─ MaevaApplication.kt           App init, notification channel, Firebase
            ├─ MainActivity.kt               The single screen; hosts the WebView
            ├─ web/UrlPolicy.kt              Allow-list + Maeva URL construction
            ├─ web/MaevaWebViewClient.kt     Navigation policy, errors, trust flag
            ├─ web/MaevaWebChromeClient.kt   Progress, window.open, uploads, camera
            ├─ bridge/MaevaBridge.kt         JS <-> native bridge (push identifiers only)
            ├─ push/DeviceRegistry.kt        Installation id + FCM token storage
            ├─ push/MaevaMessagingService.kt FCM receiver
            ├─ push/NotificationPublisher.kt Notification building + deep-link intent
            ├─ push/NotificationTarget.kt    Push payload -> record URL
            └─ session/SessionManager.kt     Cookie / storage cleanup on logout
```

---

## 2. Building

### 2.1 With GitHub Actions (no local tooling required)

The workflow `.github/workflows/android.yml` builds the app on every push that
touches `android/**`, and can be run manually.

1. Go to **Actions → Android CI → Run workflow**.
2. Optionally override the site origin and path for that run.
3. When the run finishes, download the **`maeva-debug-apk`** artifact.

Extract the downloaded ZIP and install **`app-debug.apk`** on your Android
phone (Android 8 or later, including Android 16). This APK is automatically
signed for testing; no release signing secrets are needed.

Do not install an old `app-release-unsigned.apk`: Android rejects APKs without
a signing certificate, often reporting "package appears to be invalid".
An AAB is a Play publishing artifact, not a directly installable APK.

To produce release artifacts (APK + AAB), run the workflow manually with
**Also build a release APK and AAB** enabled after configuring all four signing
secrets listed below. CI verifies APK signatures and alignment before upload.

### 2.2 Locally

Requires JDK 17 and the Android SDK (platform 35, build-tools 35.0.0).

```bash
cd android
./gradlew assembleDebug          # macOS / Linux
.\gradlew.bat assembleDebug      # Windows
```

The APK is written to `android/app/build/outputs/apk/debug/app-debug.apk`.

> Behind a TLS-intercepting corporate proxy, Gradle needs the proxy passed
> explicitly, because it does not read `HTTPS_PROXY`:
> ```
> set GRADLE_OPTS=-Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=9000
> ```
> and the proxy CA must be present in the JDK truststore.

---

## 3. Configuration

Site configuration is compiled into `BuildConfig` from Gradle properties, set in
`android/gradle.properties` or passed with `-P` on the command line.

| Property | Default | Meaning |
|---|---|---|
| `maevaBaseUrl` | `https://orgfarm-84cc9e3664-dev-ed.develop.my.site.com` | Site origin |
| `maevaSitePath` | `/` | Site prefix for record routes; confirm routes in the testing site |
| `maevaStartPath` | `/login` | Exact launch and logout-return path, separate from record routes |
| `maevaAllowedDomains` | `force.com,salesforce.com,my.site.com,…` | Domain suffixes allowed inside the WebView |
| `maevaVersionCode` | `1` | Version code (CI uses the run number) |
| `maevaVersionName` | `1.0.0` | Version name |

Example — build against production:

```bash
./gradlew assembleRelease -PmaevaBaseUrl=https://<prod-host> -PmaevaSitePath=/NestSFA/s/ -PmaevaStartPath=/NestSFA/s/
```

In CI these can also be supplied as repository **variables**
(`MAEVA_BASE_URL`, `MAEVA_SITE_PATH`, `MAEVA_START_PATH`, `MAEVA_ALLOWED_DOMAINS`).
Workflow inputs and repository variables override the committed testing defaults.
Clear stale URL overrides when switching to the testing site. The testing
`/login` page is not a record-route prefix: confirm a working record URL after
sign-in before testing record notification taps.

> **Important:** `maevaAllowedDomains` must include the corporate **identity
> provider** domain. Any host not on this list is opened in an external browser
> instead of the WebView, which will break SSO sign-in if the IdP is missing.

---

## 4. Firebase setup (push notifications)

Push is **optional at build time**. When `android/app/google-services.json` is
absent, the Google Services plugin is skipped, the app still builds and runs,
and only push is inactive.

To enable push:

1. Create a Firebase project and add an Android app with package name
   `com.renault.maeva`.
2. Download `google-services.json`.
3. Local builds: save it to `android/app/google-services.json` (gitignored —
   **never commit it**).
4. CI builds: add its full contents as the repository secret
   **`GOOGLE_SERVICES_JSON`**.

`google-services.json.template` documents the expected shape.

---

## 5. How Salesforce addresses a device

Three identifiers are involved:

| Identifier | Owner | Purpose |
|---|---|---|
| Salesforce User ID | Salesforce | Who the notification is for |
| Installation ID | This app (random UUID, stable per install) | Which install |
| FCM registration token | Firebase (rotates) | Where to deliver |

The app never sends a user id — the server derives it from the authenticated
session (`UserInfo.getUserId()`). A client-supplied user id must never be
trusted, otherwise any user could register a device against another user.

### 5.1 Registration flow

1. User signs in to the site inside the WebView as normal.
2. The page detects the native shell and reads the device identifiers from the
   JS bridge.
3. The page calls an `@AuraEnabled` Apex method that upserts a device record for
   the current user.
4. The page acknowledges, so the app stops re-registering the same token.

Salesforce-side snippet (reference — this metadata is **not** part of this repo):

```js
// Runs on an authenticated Maeva page.
if (window.MaevaAndroid) {
    const device = JSON.parse(window.MaevaAndroid.getDeviceInfo());

    if (!device.pushToken) {
        // Token not ready yet; ask for it and wait for the event.
        window.addEventListener('maeva:pushTokenReady', (e) => register(e.detail), { once: true });
        window.MaevaAndroid.requestPushToken();
    } else if (device.needsRegistration) {
        register(device);
    }
}

function register(device) {
    // Apex: upsert Mobile_Device__c (User__c = UserInfo.getUserId(),
    //       Installation_Id__c, Push_Token__c, Platform__c, App_Version__c)
    registerDevice({ payload: JSON.stringify(device) })
        .then(() => window.MaevaAndroid.onDeviceRegistered(device.pushToken));
}
```

Useful events dispatched by the app on the page:

| Event | When |
|---|---|
| `maeva:ready` | A trusted Maeva page finished loading |
| `maeva:pushTokenReady` | Response to `requestPushToken()` (`detail.pushToken`) |
| `maeva:registrationNeeded` | The stored token is newer than the one Salesforce acknowledged |

### 5.2 Bridge API (`window.MaevaAndroid`)

| Method | Returns | Notes |
|---|---|---|
| `getDeviceInfo()` | JSON string | `installationId`, `pushToken`, `appVersion`, `osVersion`, `deviceModel`, `pushAvailable`, `needsRegistration` |
| `requestPushToken()` | — | Async; result via `maeva:pushTokenReady` |
| `onDeviceRegistered(token)` | — | Acknowledge a successful registration |
| `onSessionEnded()` | — | Call before logging out |

The bridge is attached to every document the WebView loads, so **every method
re-checks that the current page is on an approved domain** before responding. It
deliberately exposes only push-routing identifiers — never Salesforce session
tokens, cookies or user data.

---

## 6. Sending a notification that opens a record

Send a **data-only** FCM HTTP v1 message so the app always handles it and can
attach the deep link, including when backgrounded.

```json
{
  "message": {
    "token": "<device FCM token>",
    "data": {
      "title": "New INTERNET lead",
      "body": "Jean Dupont — Clio V",
      "objectApiName": "Lead",
      "recordId": "00Q7a00000XyZabEAF",
      "notificationId": "a0X7a00000LmNopEAF"
    },
    "android": { "priority": "HIGH" }
  }
}
```

Tapping the notification opens:

```
https://renaultarca--hquat.sandbox.my.site.com/NestSFA/s/lead/00Q7a00000XyZabEAF
```

Supported keys:

| Key | Required | Meaning |
|---|---|---|
| `title` / `body` | at least one | Notification text |
| `objectApiName` + `recordId` | — | Opens the record detail page |
| `targetPath` | — | Site-relative fallback path, e.g. `/NestSFA/s/home` |
| `notificationId` | — | De-duplication; same id replaces the previous notification |

Object routing: `Lead`, `Opportunity`, `Account`, `Contact`, `Case`, `Task` and
`Event` map to their site routes; anything else falls back to `detail`. Record
ids must be 15 or 18 alphanumeric characters, and `targetPath` must be
site-relative or on an approved domain — a push payload cannot redirect the
WebView to an arbitrary site.

Behaviour on tap:

| Situation | Result |
|---|---|
| App not running | Launches and loads the record URL directly |
| App running | Reuses the existing task (`singleTask` + `onNewIntent`) |
| Session expired | Site redirects to login, then back to the record |
| Invalid / missing payload | Message shown, home page loaded |

---

## 7. Replacing the vendor user-agent check

The old app was detected by Salesforce components through the user-agent token
`BryjRenaultWebView/1.0`. This app appends its own marker instead:

```
<default WebView UA> MaevaAndroid/<versionName>
```

The Aura components that branch on the mobile shell — notably
`NEST_ContentLayout_Home_Mobile_Compat_LC` — must be updated to test for
`MaevaAndroid` instead of the retired vendor token.

A cleaner alternative is already available: the app sets `window.maevaNativeShell = true`
and fires `maeva:ready` on every trusted page, so components can feature-detect
rather than sniff the user agent.

> Note: the existing desktop/mobile branch in that component requires
> `formFactor == 'DESKTOP'` or `'PHONE'`. A tablet that is not the vendor
> WebView matches **neither** branch — a pre-existing gap worth fixing while the
> user-agent check is being changed.

---

## 8. Native behaviour reference

| Concern | Behaviour |
|---|---|
| Navigation | Approved https hosts render in-app; everything else opens in a Custom Tab. `tel:`, `mailto:`, `sms:`, `geo:` are handed to the relevant app |
| `window.open` | Multiple windows enabled; the target URL is routed through the same policy (`NEST_Base_LCHelper.js` relies on this) |
| Back button | Navigates WebView history, then exits |
| Pull to refresh | Reloads the current page |
| File upload | `<input type="file">` opens the system picker; camera permission requested on demand |
| Download | Handled by `DownloadManager`, forwarding the session cookie so files behind the Salesforce session download correctly |
| Logout | After `/secur/logout.jsp` loads, cookies and web storage are cleared and the push binding is dropped — important on shared devices |
| Offline / error | Full-screen error state with retry |
| Security | Cleartext traffic disabled, mixed content blocked, SSL errors always cancelled, file/content access disabled, backup and device transfer excluded |

---

## 9. Release signing

Release builds require all four values below. Missing signing credentials cause
the build to fail instead of publishing an unsigned, non-installable APK. For
testing without a release key, use the signed `app-debug.apk` from the
`maeva-debug-apk` artifact. A signed release APK is named `app-release.apk`.

| CI secret | Environment variable |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | decoded to a file → `MAEVA_KEYSTORE_PATH` |
| `ANDROID_KEYSTORE_PASSWORD` | `MAEVA_KEYSTORE_PASSWORD` |
| `ANDROID_KEY_ALIAS` | `MAEVA_KEY_ALIAS` |
| `ANDROID_KEY_PASSWORD` | `MAEVA_KEY_PASSWORD` |

If the retiring app was published under an existing Play listing, the **original
upload key is required** to ship an update — confirm ownership of that key
before planning the cutover.

---

## 10. Open items to confirm before production

These could not be verified from the Salesforce metadata alone:

1. **Production site URL** — the app currently targets the `hquat` sandbox.
2. **Record route shape** — `/NestSFA/s/lead/<id>` is taken from
   `NEST_Base_LCHelper.js`; confirm against the live site.
3. **Identity provider domain** — must be added to `maevaAllowedDomains` or SSO
   sign-in will be pushed out to the external browser.
4. **Salesforce-side work** — the device record object, the `@AuraEnabled`
   registration method and the FCM dispatcher replacing
   `NEST_Batch_MobileNotification_CLS` are **not** in this repo and still need
   to be built.
5. **Play Store listing and signing key** ownership for the existing app.
6. **Mobile CSS** — `NEST_ModalPopIn_LC.css` forces `min-width: 75rem` on
   viewports above 601px, which overflows on phones and tablets.
