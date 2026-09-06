# Search Card for Android

A native Kotlin implementation of this repository's Home Assistant search card. A resizable home-screen search bar opens a native search field over the widget and reveals status and results downward. Android widgets cannot contain editable text fields; typing happens in the panel launched by the widget.

The app supports Android 17 (API 37) and newer. No dashboard URL is built in. All installed widgets share the server and search card selected in the app.

## Build and install

Install **JDK 17**, **Android SDK Platform 37.0**, **Build Tools 37.0.0**, **Platform Tools**, and **Node.js 22** (Node is needed for compatibility tests, not the APK). Use an Android Studio version supporting AGP 9.3.2, or install the current SDK command-line tools. AGP 9.3.2, Gradle 9.5.0, Kotlin 2.2.21, and dependency versions are pinned in the project. Initial builds require internet access to download dependencies.

Open the **android/** folder as an Android Studio project and select JDK 17 as the Gradle JDK. For command-line builds, set `JAVA_HOME` and `ANDROID_HOME`, or put the SDK path in an untracked `android/local.properties`:

```properties
sdk.dir=/absolute/path/to/Android/Sdk
```

Accept the SDK licenses and install the packages if needed:

```sh
sdkmanager --licenses
sdkmanager 'platforms;android-37.0' 'build-tools;37.0.0' 'platform-tools'
cd android
./gradlew assembleDebug
./gradlew test lint verifySearchCardParity
./gradlew installDebug
```

On Windows use `gradlew.bat`. `installDebug` requires a connected device/emulator with USB debugging enabled. The APK is **app/build/outputs/apk/debug/app-debug.apk**. It can also be copied to your phone and installed with permission to install apps from that source. No Google Play services or Home Assistant Companion app is required.

### Persistent signing and GitHub Actions

Push and manual workflow runs publish **search-card-release** (recommended for everyday use) and **search-card-debug**, both signed with the same persistent key. Signing runs in a separate job after successful build, unit tests, lint, and parity checks; that job does not check out or execute repository code. PR jobs never receive the key and publish **search-card-pr-debug** with a temporary debug signature. The **search-card-signing-inputs** artifact is an intermediate build artifact, not the signed download to install.

Configure these **repository Actions secrets** in GitHub Settings → Secrets and variables → Actions:

| Secret | Value |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | Base64 encoding of the complete JKS keystore file |
| `ANDROID_KEYSTORE_PASSWORD` | Keystore password |
| `ANDROID_KEY_ALIAS` | `search-card` for the provided keystore |
| `ANDROID_KEY_PASSWORD` | Private-key password |

If using the provided signing backup, extract its `signing-private/` directory into `android/`, retain a private backup, and run this from the repository root to install all four secrets:

```sh
gh secret set --repo splitice/search-card --env-file android/signing-private/github-secrets.env
```

The backup contains the private key (`search-card-release.jks`), password, ready-to-import secret values, local signing configuration, and public certificate. Keep the entire directory private; it is excluded from Git. Do not upload it as a workflow artifact or commit it. Missing secrets fail the signing job rather than producing a silently incompatible download. The workflow removes its temporary keystore after signing and prints only public certificate information when verifying each APK.

For a new project without an existing signing key, create and retain one **once**, for example:

```sh
keytool -genkeypair -storetype JKS -keystore /absolute/path/to/search-card.jks -alias search-card -keyalg RSA -keysize 3072 -validity 10000
```

For local builds, copy the supplied `android/signing-private/signing.properties` to `android/signing.properties`, or put the following in that untracked file (paths are relative to `android/`):

```properties
storeFile=/absolute/path/to/search-card.jks
storePassword=YOUR_STORE_PASSWORD
keyAlias=search-card
keyPassword=YOUR_KEY_PASSWORD
```

With this configuration, both `assembleDebug` and `assembleRelease` use your persistent key. Release output is `app/build/outputs/apk/release/app-release.apk`. Without it, local debug builds use the workstation's debug key and release builds are unsigned. Keep the same signing key and increment `versionCode` in `app/build.gradle.kts` when publishing new versions; local and CI builds use that same version code.

Install matching-key updates with `adb install -r path/to/app-release.apk` to retain app data. A new signing key cannot update an old differently signed installation without the old key or a valid signing lineage. A normal uninstall clears account data, and `adb uninstall -k` does **not** bypass the signature requirement for retained data. There is no automatic uninstall in this workflow. If the old key is unavailable, transitioning to the persistent key requires a one-time clean install and sign-in; subsequent matching-key updates preserve storage. See [Android's update requirements](https://developer.android.com/google/play/app-updates).

## Setup and use

1. Open Search Card or tap its widget. If no dashboard URL has been saved, the app asks for it before opening sign-in. Enter an HTTPS URL with a dashboard and view path, such as `https://ha.example:8123/dashboard-phone/main`, then choose **Continue to sign in**. Invalid or blank URLs keep you on setup; cancelling closes the panel without opening login. Previously saved URLs are retained. You can change the URL later in Settings; changing it clears the current login and cache.
2. Choose **Sign in to Home Assistant** and complete your server's normal login/MFA screen. Only this screen uses a temporary WebView. Search and controls are native Kotlin. TLS certificate errors are never bypassed. External SSO redirects are not supported in this version. Leaving the app during sign-in cancels the current request and destroys the WebView. Returning opens a fresh authorization attempt automatically; **Cancel** returns to search. Token responses are read off the UI thread, and exchange failures are shown in the login screen.
3. The app reads `lovelace/config`, selects the requested view, and discovers static `custom:search-card` configurations inside `cards`, `card`, and `sections`. If there is more than one card, select one. Settings → **Choose search card again** resets selection. An exact configuration match follows a card when it moves; configuration edits at the saved path sync automatically. Missing or ambiguous matches require selection again.
4. Add **Search Card** through your launcher's Widgets menu, or use **Add home-screen widget** in Settings. Resize horizontally; tap anywhere on the bar to search. The opened field matches the widget's position and width when space permits. Connection status and results reveal below it over 200 ms; **Search options** below the field contains Refresh, Settings, and Close. Entity results put the name on the first row and the current value with its native control on the second row, including on narrow widgets.
5. Tap an entity result for details in the Home Assistant Companion app when installed, with a browser fallback if it cannot be opened. Use native switches for lights/switches/input booleans, **Activate** for scenes, and **Run** for scripts. Configured actions execute on tap. Local service links open in your browser and require access to the configured host (LAN/VPN as appropriate).

Entity details use the dashboard's `more-info-entity-id` query parameter with Companion's documented [`homeassistant://navigate` deep link](https://companion.home-assistant.io/docs/integrations/url-handler/). The dashboard path, query, and fragment are preserved. Companion manages its own login and asks which server to use when multiple servers are configured; select the server used by Search Card. Without a Companion handler, the original dashboard HTTPS link opens instead. The app does not transfer native API tokens to external links. On older Home Assistant frontends without entity deep-link support, the dashboard opens and you can select the entity there.

Widget placement uses the launcher's click bounds and the current keyboard/system-bar insets. The field moves upward only when needed to leave space for status and at least one 72 dp result row, with an 8 dp gap above the keyboard. Status, errors, and results share a scrollable area when the window is very short. The reveal respects Android's animation-duration setting; keyboard movement follows the keyboard's own animation. Large fonts can increase the field height to keep text readable. Exact alignment requires accurate bounds from the launcher. Opening the app icon, missing/invalid bounds, or stale bounds after a window change uses the ordinary unanchored panel. No overlay permission or background position tracking is used.

Configuration refreshes when the panel opens, reconnects, or you press Refresh. No HACS resource URL or copied YAML is needed. Template-generated card configurations, arbitrary wrapper evaluation, and conditional visibility rules are not evaluated; choose a static card configuration. This is a search client, so any wrapper's dashboard visibility conditions are not access controls. Home Assistant still enforces account permissions for API operations.

With a saved session, the first status is **Connecting…** while the local snapshot loads and the app reconnects. Cache-age text is not displayed. When offline, the last successfully loaded snapshot remains searchable. Home Assistant actions are disabled until fresh states/configuration are loaded. Links remain available. Failed or timed-out service calls are **not replayed**; check the actual result before retrying. If registry access is denied, a visible warning explains that priority/hidden label filtering could not be applied, matching the web card's metadata-failure fallback.

Search also includes registered sidebar pages such as Energy, Settings, and dashboards. These come from the authenticated `get_panels` response and refresh whenever the panel reconnects or you press Refresh. Their names, URL paths, and the `dashboard` category are searchable; built-in labels are English and custom titles are preserved. Navigation results follow priority entities and local services, precede ordinary entities, and are included in the total result count. Untitled, internal, and server-hidden entries are excluded. Cached navigation links remain usable offline and open through Companion with a browser fallback, using the selected server's origin. If page metadata cannot be loaded, entity search still works and a warning is shown.

All matches remain searchable and scrollable, including with an old `max_results: 10` configuration. `max_results` sizes the web dropdown on desktop and mobile; it never truncates matches in either engine. The native panel uses the available window and keyboard space for its height. Android exposes the first 100 results, adds batches of 100 when you approach the end, and composes only visible rows using a lazy list. Paging uses the local snapshot and makes no extra network requests. A new query resets the list to the first page; state restoration retains the loaded page and scroll position. The count shows all matches, including those not yet loaded into the list.

Native entity controls stay in the panel. Only the first (name) row opens entity details in Companion. Lights, switches, and input booleans send explicit `turn_on`/`turn_off` calls; scenes and scripts send their domain's `turn_on`. Scenes with no previous activation (`unknown` state) can still be activated, consistent with [Home Assistant scene state semantics](https://www.home-assistant.io/integrations/scene/). Unsupported domains show their value and link to details.

The same native second row supports these additional domains:

| Domains | Control | Home Assistant service |
| --- | --- | --- |
| `button`, `input_button` | Press button (including never-pressed entities) | `<domain>.press` |
| `select`, `input_select` | Dropdown showing the entity's available options | `<domain>.select_option` with `option` |
| `text`, `input_text` | Text box with Save / keyboard Done | `<domain>.set_value` with string `value` |
| `number`, `input_number` | Number box with Save, or a slider | `<domain>.set_value` with numeric `value` |

Number controls honor `min`, `max`, `step`, units, and `mode`. An explicit `box` or `slider` mode is respected. For `number` entities in `auto` mode, up to 256 steps uses a slider; larger ranges use a box, following the [Home Assistant frontend's choice](https://github.com/home-assistant/frontend/blob/dev/src/data/number.ts). Number helper mode defaults to slider. Box values are checked against the range and step before sending. Sliders preview locally while dragging and submit only when the gesture ends; they never send a stream of intermediate values.

Text boxes check length limits and common regex patterns before Save. Uncommon Python/JVM regex differences may require editing through Home Assistant details. Password-mode text is masked, including in the current-value display; draft text is not placed in saved instance state. Editing does not submit on blur or dismissal, and incoming state updates do not overwrite an unsaved draft. Select options are refreshed from the entity attributes. These controls use the same pending-call guard, inline failure reporting, and no-replay behavior as toggles and activation buttons.

Controls remain visible but disabled until fresh data is available, while an invocation is pending, when the entity is unavailable, or when the required service is absent. The row explains why. A pending invocation displays **Sending…**; rapid taps cannot submit it twice. Rejections and unconfirmed calls are displayed below the affected entity or configured action. Reconnection never replays an invocation; only another explicit tap can send it again.

## Battery and account storage

The widget is static, with `updatePeriodMillis=0`. There are no workers, alarms, foreground services, wake locks, or requests to exempt the app from battery optimization. Only the foreground panel connects. Its socket, heartbeat, event subscription, pending requests, and reconnect delays are cancelled immediately when the panel is dismissed and when the activity stops, including when opening a local link. Automatic focus waits for the search field to be laid out in a resumed, focused window and is suppressed while the activity is finishing. Cached data makes the next search available before reconnection completes.

An active socket subscribes before taking the initial state snapshot, then reconciles buffered events by server timestamps. Subsequent updates refresh native rows and coalesce cache writes. Token refresh happens only during foreground use. The initial authorization-code exchange is cancelled if the app leaves the foreground.

Refresh tokens are AES-GCM encrypted with an Android Keystore key. Entity snapshots live in app-private, no-backup storage; account preferences, WebView storage, and snapshots are excluded from cloud backup and device transfer. Sign out clears local tokens, cached data, selection, and WebView login state and attempts server-side token revocation while the screen remains open. If offline, revoke the old session from your Home Assistant profile separately.

## Maintaining compatibility with search-card.js

The web card and Kotlin port share comparison fixtures, including navigation pages and their click destinations. `core/` holds the Kotlin engine; `app/` handles authentication, networking, lifecycle, storage, and UI. The application does not load or execute JavaScript search code at runtime.

`tools/reference.mjs` loads the **actual repository search-card.js** in Node with small frontend stubs. It calls the card's search and action-row methods against `fixtures/search.json`. Gradle generates reference results under `core/build/`, and Kotlin tests compare ordered results, counts, actions, icons, and nested service payloads. The reviewed source hash in `compatibility.json` also catches upstream changes not covered by the fixtures. Both the hash check and parity tests run in CI whenever the Android project or web card changes.

When updating from the search-card base:

1. Inspect the source diff from the commit recorded in `compatibility.json`. Decide which changes affect native behavior; web-only editor or styling changes may need no Kotlin change.
2. Extend shared fixtures for every changed search/action behavior. Update `core/`, networking, or native presentation as needed.
3. After reviewing the changes, run `node tools/reference.mjs --record-review` from `android/`. This records the source hash and last commit touching the file; it is a review acknowledgement, not a compatibility proof.
4. Run `./gradlew test lint verifySearchCardParity assembleDebug`. Commit the implementation, fixtures, and compatibility record together.
5. Increment the APK version and rebuild when Kotlin behavior changes. Dashboard configuration changes alone do not require a new APK.

Kotlin's regex engine is JVM `Pattern`, with ASCII case-insensitive matching for the card's non-Unicode JavaScript expressions. Common character classes, captures, lookaheads, alternation, and quantifiers are covered. Named groups/lookbehind, inline flags, atomic/possessive expressions, Java-only escapes, and character-class intersections are rejected with a visible message. ECMAScript and JVM behavior can still differ for uncommon escapes, Unicode case folding, backreferences, and pathological patterns. Entity ordering uses the US collator; non-ASCII ordering may differ from a browser configured for another locale. Add fixtures before relying on new syntax; this is a tested native port, not an ECMAScript interpreter. Common MDI icons map to bundled native icons; others use a domain/generic icon.

## Verification

Automated checks cover button/select/text/number payloads, option and text validation, numeric bounds/decimal steps/modes, native on/off payloads, never-activated scenes, service availability, synchronous duplicate prevention, action errors across reconnects, cancellation without replay, search behavior, dashboard discovery/reselection, OAuth callback validation, label metadata, snapshot reconciliation/serialization, token HTTP errors, token response threading and cancellation during body reads, socket events, service failures without replay, and closing a socket with pending work. Placement tests cover exact alignment, minimum upward movement, keyboard animation, narrow widgets, large text, cutouts, small windows, and invalid/stale bounds. Native UI tests check text/number submission, select menus, slider release, password semantics, draft preservation, and supported controls at narrow width, name/value/control placement, independent detail navigation, pending/disabled states, and row-level failures using simulated calls. Device tests also cover scrolling past 100 and 200 matches, query resets, and paging state restoration. Device tests check bounds delivered through widget PendingIntents, switching widget instances, query preservation across recreation, missing-bounds fallback, repeated immediate widget dismissal, control shutdown before closing animations, search focus and background taps, first-launch setup and login across activity stops/recreation, Keystore encryption, clearing the cache on logout, and the widget's no-update policy:

```sh
./gradlew connectedDebugAndroidTest
```

GitHub Actions builds signed debug/release downloads on push/manual runs and runs device tests on Android 17 (API 37). PR debug artifacts are for testing and do not share the persistent signing identity. Use the `system-images;android-37.0;google_apis;x86_64` emulator image with a configured WebView provider for the login test. Those tests do not log into a private Home Assistant instance or operate actual devices.

Before installing a release for daily use, perform this manual matrix on Android 17 (or an API 37 emulator). It requires your own server/login:

| Scenario | Expected result |
| --- | --- |
| Widget placement and horizontal resize | Search bar stays usable; tap opens the native panel |
| Widgets near the top, middle, and bottom; multiple instances | Field matches the tapped widget's bounds when there is room; low widgets shift only as far upward as needed |
| Minimum widget width, large fonts, cutouts, and short windows | Labels and controls remain usable; status/results scroll without overlapping system bars or keyboard |
| Different keyboard heights; hardware keyboard; animations disabled | Placement follows actual insets; the field stays fixed when possible; disabled animations reveal immediately |
| Keyboard, Back, rotation, light/dark mode | Input focuses, keyboard fits, Back hides keyboard then closes; query survives rotation |
| Rapid widget open/background-dismiss cycles | No crash or late keyboard request; controls and connection stop at dismissal |
| Process killed and reopened | Login and cached snapshot persist; controls wait for a fresh connection |
| Multiple cards; card moved, edited, removed | Correct match syncs; missing/ambiguous selections request a choice |
| Offline, reconnect, server restart | Cached results remain searchable; no control calls while stale |
| Token expiry/revocation and MFA | Foreground refresh succeeds or sign-in is requested |
| Toggle, scene, script, regex action | Exactly one call per tap; busy state prevents duplicates; failures are visible |
| Unavailable entity | Entity remains searchable; its native control is disabled |
| Entity tap with/without Companion installed | Companion opens the selected dashboard and entity details; without it, the browser opens the same details; multiple Companion servers prompt for selection |
| Sign out | Cache, API token, and login WebView data are cleared |
| Close panel/open browser/turn screen off | Socket closes; app emits no continuing network traffic |

For battery inspection, record a foreground connection and then dismiss the panel. Use Android Studio Network Profiler (debug build) or server WebSocket logs to verify closure and no subsequent requests; `adb shell dumpsys jobscheduler` and `adb shell dumpsys alarm` should show no jobs/alarms owned by `com.splitice.searchcard`. Use `adb shell dumpsys batterystats com.splitice.searchcard` for a longer idle observation. Running the panel again should create a new connection, not reuse a background one.
