# edu-sharing Android

Native Android shell for the edu-sharing mobile app. The primary use case is **"send to
edu-sharing"**: the user shares a file, link or text from another app, the shell hands it to
the edu-sharing web app, and the web app uploads it into a folder/collection after login.

## Architecture: thin native shell + remote WebView

This app is intentionally **thin**. It does not bundle the Angular SPA and it contains no
REST/OAuth/HTTP layer. Instead:

- `MainActivity` loads the **live server SPA** (served by `NgServlet`) into a `WebView` over
  HTTPS. Because the WebView runs at the server origin, login, REST and OAuth all go through
  the SPA's normal `HttpClient` — **same-origin, so no CORS and no native HTTP shim**.
- The only native responsibility is the **Android share intent**, which is handed to the web
  app through a small injected JavaScript bridge, `window.eduBridge`.
- The OAuth token and the server URL live in the WebView's own `localStorage` — the shell
  does not persist them.

The bridge is the **single coupling point** between this repo and the Angular frontend. Its
contract is specified in the community repository:

> `docs/android-app/eduBridge-contract.md` — in `TSGames/edu-sharing-community-repository`

The frontend side is `Frontend/src/app/services/cordova.service.ts`, which reads only this
bridge (no Cordova/Capacitor plugins).

## Legacy frontend compatibility (transitional, NOT part of the contract)

`LegacyFrontendCompat.kt` fakes just enough of the old Cordova globals (`window.cordova`,
`window.device.platform`) so a server that is still running the **old, pre-eduBridge**
`cordova.service.ts` still detects "running in the app" (its `isRunningCordova()` otherwise
returns `false` in this shell, since it looks for those globals / an `"ionic / edu-sharing-app"`
User-Agent marker this shell doesn't send). It's injected via
`WebViewCompat.addDocumentStartJavaScript`, scoped to the server origin, and is deliberately
minimal — it does not reimplement any real Cordova plugin, so an old frontend still won't get
share handling from this shell, only app-mode detection.

This is purely a stop-gap for native-app/server-frontend version skew and is inert for any
frontend that has adopted eduBridge (verified: its `isIOS()`/`isAndroid()`/
`isReallyRunningCordova()` all check `this.platform`, set from `window.eduBridge`, before ever
touching `window.cordova`/`window.device`). Remove it once all deployments are known to run an
eduBridge-aware frontend build.

## The `window.eduBridge` contract (as implemented here)

### JS → native (`EduBridge.kt`, `@JavascriptInterface`)

| Method | Returns | Implementation |
|---|---|---|
| `getPlatform()` | `"android"` | constant |
| `getAppVersion()` | e.g. `"1.0.0"` | `BuildConfig.VERSION_NAME` |
| `getInitialShare()` | share JSON or `""` | cold-start share, held until pulled (pull model) |
| `consumeShare()` | – | marks the cold share processed |
| `exitApp()` | – | `finishAndRemoveTask()` |
| `readFile(uri)` | base64 or `""` | reads a `content://` URI via `ContentResolver` |
| `takePhoto()` | – | starts a native camera capture; **does not block** (see below) |

### Native → JS (called on the WebView when defined)

| Callback | When |
|---|---|
| `window.eduBridgeOnReady()` | page finished loading |
| `window.eduBridgeOnShare(payloadJson)` | a share arrived while the app was running (warm) |
| `window.eduBridgeOnResume()` | returned from background after > 1 min |
| `window.eduBridgeOnBack()` | hardware back button (SPA decides history vs. exit) |
| `window.eduBridgeOnPhotoTaken(payloadJson)` | result of a `takePhoto()` capture |

`takePhoto()` is the one bridge method that isn't synchronous: a camera capture can take
arbitrarily long (user interaction), so blocking the calling JS thread for it risked a "page
unresponsive" WebView warning. It returns immediately and `MainActivity` delivers the result
later via `eduBridgeOnPhotoTaken`, using `ActivityResultContracts.TakePicture()` + a
`FileProvider`-exposed cache file (`PhotoCapture.kt`). The captured JPEG is EXIF-rotated and
downsampled to at most 2048px on the longer side before being base64-encoded, to keep memory
use and the JS payload size bounded:

```json
{ "success": true, "mimetype": "image/jpeg", "fileName": "photo_….jpg", "stream": "<base64>" }
```
```json
{ "success": false, "error": "CANCELLED" }
```

### Share payload JSON

```json
{
  "action":   "SEND",
  "mimetype": "image/jpeg",
  "text":     "https://… or text",
  "uri":      "content://…",
  "fileName": "photo.jpg",
  "stream":   "<base64>"
}
```

- **Link/text share** → `text` set, `mimetype: "text/plain"`.
- **File share** (`image/*`, `video/*`, `audio/*`, `application/pdf`) → `uri` = `content://`,
  `stream` = base64 read natively via `ContentResolver`, `fileName` set.
- **Deep link** → `action: "VIEW"`, `uri` = full edu-sharing URL (the WebView navigates there).

### App detection

The shell sets a custom `User-Agent` so the SPA can detect it:

```
<default WebView UA> edu-sharing-app/<version> android
```

## Server selection

On first launch `ServerUrlActivity` asks for the edu-sharing server URL and stores it in
`SharedPreferences`. A build-time default can be provided instead:

```
./gradlew assembleRelease -PeduSharingDefaultServerUrl=https://repository.example.org
```

The SPA (`app-login-page`) handles the actual login.

## Project layout

```
app/src/main/
├── AndroidManifest.xml          # permissions, ACTION_SEND filters, FileProvider
├── java/org/edusharing/app/
│   ├── EduSharingApp.kt         # Application (enables WebView debugging in debug builds)
│   ├── MainActivity.kt          # WebView host + share/lifecycle/camera bridging
│   ├── EduBridge.kt             # window.eduBridge @JavascriptInterface
│   ├── ServerConfig.kt          # server URL persistence
│   ├── ServerUrlActivity.kt     # first-run server picker
│   ├── ShareIntentParser.kt     # Intent -> SharePayload (reads content:// to base64)
│   ├── SharePayload.kt          # share payload model + JSON
│   ├── PhotoCapture.kt          # takePhoto(): temp file, EXIF rotation, downsampling
│   ├── PhotoResultPayload.kt    # takePhoto() result payload model + JSON
│   ├── LegacyFrontendCompat.kt  # transitional, NOT part of the eduBridge contract (see above)
│   └── IntentExt.kt             # getParcelableExtra compat helper
└── res/                         # layouts, strings, theme, adaptive launcher icon, file_paths.xml
```

## Build

Requires the **Android SDK** (`local.properties` with `sdk.dir=…`, or `ANDROID_HOME`).

```
./gradlew assembleDebug       # debug APK
./gradlew assembleRelease     # release APK (see signing below)
./gradlew bundleRelease       # AAB for Play
```

- `minSdk 26`, `targetSdk 35`, Kotlin, AGP via the version catalog (`gradle/libs.versions.toml`).
- The Gradle wrapper pins Gradle 8.11.1.

### Release signing / Play

Signing material is **never committed**. Provide it via a git-ignored `keystore.properties`
in the repo root:

```properties
storeFile=/absolute/path/to/edu-sharing.jks
storePassword=…
keyAlias=…
keyPassword=…
```

…or the equivalent environment variables `EDU_SHARING_STORE_FILE`,
`EDU_SHARING_STORE_PASSWORD`, `EDU_SHARING_KEY_ALIAS`, `EDU_SHARING_KEY_PASSWORD`. When no
keystore is configured, `release` builds are left unsigned. Upload `bundleRelease` output to
the Play Console.

## Notes

- `usesCleartextTraffic` is `false` (HTTPS only). For a plain-HTTP dev server, add a debug
  network-security-config.
- The shell keeps same-origin navigation inside the WebView and opens external links / other
  schemes (`mailto:`, `market:`, …) with the system.
- `<input type="file">` uploads are wired through `WebChromeClient.onShowFileChooser`.
