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

`LegacyFrontendCompat.kt` fakes just enough of the old Cordova globals so a server that is
still running the **old, pre-eduBridge** `cordova.service.ts` still detects "running in the
app" *and* still gets share content redirected to `AppSharePageComponent`:

- `window.cordova` / `window.device.platform` — so `isRunningCordova()`/`isIOS()`/`isAndroid()`
  return correctly (otherwise `false` in this shell: the old code looks for those globals, or
  an `"ionic / edu-sharing-app"` User-Agent marker this shell doesn't send).
- A repeated `deviceready` DOM event dispatch (~20s) — the old `whenDeviceIsReady()`, which
  sets up the share-redirect subscription, only runs once `deviceIsReady` flips `true`, which
  requires either a real `deviceready` event or `window.plugins` being truthy. Repeating the
  dispatch covers the race against Angular's bootstrap time (`document.addEventListener`
  doesn't buffer events fired before the listener exists).
- A `window.plugins.intent.{getCordovaIntent, setNewIntentHandler}` stub (buffered:
  `MainActivity` pushes the parsed share via `LegacyFrontendCompat.deliverShare()` from the
  same cold/warm-share code paths that feed `eduBridgeOnShare`/`getInitialShare()`, mapped
  onto the old `cordova-plugin-intent` object shape — `.type`, `.extras['android.intent.extra.
  TEXT'|'STREAM']`, `.clipItems[0].file`, `.stream` (base64)). This is what the old code calls
  `this.observerShareContent.next(...)` with, which is what its `router.navigate([...,'app',
  'share'], {queryParams: data})` subscription actually redirects on.
- A `navigator.app.exitApp()`/`.backHistory()` stub, routing `exitApp()` to the
  already-injected `window.eduBridge.exitApp()` binding. Old `CordovaService.exitApp()` (e.g.
  behind the login page's close button, `AppLoginPageComponent.buttonExitApp()`) and
  `onBackKeyDown()` call `navigator.app.exitApp()` directly with no `window.eduBridge` check,
  so without this it silently no-ops.
- Tracking of whether the old code registered a `'backbutton'` listener (wraps
  `document.addEventListener` for that one event name; old `whenDeviceIsReady()` registers it
  unconditionally once reached). `MainActivity`'s back handler dispatches that event — as a
  fallback alongside the existing `eduBridgeOnBack` check — only when the tracked flag
  confirms a listener is actually present, so it doesn't guess and risk a double back
  navigation.
- A `navigator.camera.getPicture(win, fail, options)` stub plus a `cordova.plugins.permissions`
  stub (always reports the `CAMERA` permission granted — native capture delegates to the
  system camera app and needs no runtime permission of its own). Old
  `CordovaService.getPhotoFromCamera()` (e.g. the create-menu's camera option,
  `CreateMenuComponent.openCamera()`) calls these directly; without them the permission check
  throws (silently swallowed), so taking a photo had no visible effect at all. The stub routes
  the actual capture through the same already-injected `window.eduBridge.takePhoto()` binding
  and defines `window.eduBridgeOnPhotoTaken` itself to receive the result (old frontend never
  defines it, so there's no conflict with an eduBridge-aware frontend's own use of that
  callback).

No shim is needed for OAuth token storage: old `getPermanentStorage`/`setPermanentStorage`
already use `window.localStorage` unconditionally on Android (the `NativeStorage` plugin
fallback is gated behind `isIOS()`, which this shim's `window.device.platform = 'Android'`
makes `false`) — this shell's real WebView `localStorage` already satisfies that path.

A related (non-JS) intercept lives directly in `MainActivity.shellWebViewClient`, not in
`LegacyFrontendCompat.kt`: the login page's top-right cancel button
(`AppLoginPageComponent.buttonLoginBack()`) calls `CordovaService.restartCordova()`, whose old
implementation has no `window.eduBridge` check and falls through to
`window.location.replace('http://app-registry.edu-sharing.com/ng/?reset=true...')` — an
external host unrelated to the configured server. Left alone, this shell would open it in an
external browser with no way back into the app. `shouldOverrideUrlLoading` recognizes that
specific host and rewrites the navigation onto the configured server's root instead
(`<serverUrl>/?<same query>`), matching what the eduBridge-aware `restartCordova()` already
does for a current frontend.

It deliberately does **not** implement `resolveLocalFileSystemURL`/`NativeStorage`/any other
legacy plugin — `AppSharePageComponent` (unchanged across old and new) already reads
`getLastIntent().stream` directly for file content, so that's sufficient; `getFileAsBlob()`'s
result (which would use `resolveLocalFileSystemURL`) only affects a loading spinner, which
resolves fine via its error path when left unimplemented.

This is purely a stop-gap for native-app/server-frontend version skew and is inert for any
frontend that has adopted eduBridge (verified: its `isIOS()`/`isAndroid()`/
`isReallyRunningCordova()` all check `this.platform` first, and `registerOnShareContent()`'s
`eduBridge` branch returns before ever reaching the `window.plugins.intent` code this shim
feeds). Remove it once all deployments are known to run an eduBridge-aware frontend build.

## The `window.eduBridge` contract (as implemented here)

### JS → native (`EduBridge.kt`, `@JavascriptInterface`)

| Method | Returns | Implementation |
|---|---|---|
| `getPlatform()` | `"android"` | constant |
| `getAppVersion()` | e.g. `"1.0.0"` | `BuildConfig.VERSION_NAME` |
| `getInitialShare()` | share JSON or `""` | cold-start share, held until pulled (pull model) |
| `consumeShare()` | – | marks the cold share processed |
| `exitApp()` | – | on the login page (`components/app`): clears the stored server URL and reopens `ServerUrlActivity`; anywhere else: `finishAndRemoveTask()` (see below) |
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

`exitApp()` is shared by two unrelated SPA flows that give the shell no way to distinguish
them other than which screen is showing: the login page's close button
(`AppLoginPageComponent.buttonExitApp()`), where the user wants to pick a different server,
not leave the app; and "return to the originating app" after finishing a share
(`AppSharePageComponent`'s `OnBackBehaviour.closeApp`), where actually finishing the activity
is correct. `MainActivity.onExitRequested()` checks the current WebView URL (`components/app`,
the app-login-page route, vs. anything else) to decide between the two.

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
