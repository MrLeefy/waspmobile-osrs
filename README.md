# WaspMobile OSRS Overlay & Biometric Swarm Ecosystem (v6)

A human-biometric automation and computer-vision overlay ecosystem for **Old School RuneScape (OSRS)** across **Android (`WaspMobile`)**, **Desktop (`Simba 2.0 + WaspLib`)**, and a **Mobile PWA Swarm Command Center (`Swarm`)**.

---

## MCP development status (audited draft branch)

The opt-in MCP bridge is documented in [mcp-bridge/README.md](mcp-bridge/README.md).
Executed build/test results and unresolved release gates are in [mcp-bridge/AUDIT.md](mcp-bridge/AUDIT.md).
Remote phone control uses Android 14+ window-only screenshots, verified single-window identity,
and one-shot screenshot-bound taps. It does not yet support remote split-screen selection or
high-level autonomous mobile skill plans. The legacy Swarm server is separate and unauthenticated;
do not expose it as an MCP endpoint. No motion-randomization scheme guarantees anti-cheat immunity.

## 📂 Repository File Index

### 1. Android Standalone Accessibility Overlay (`app/src/main/`)
Runs a pass-through `TYPE_ACCESSIBILITY_OVERLAY` floating HUD and computer-vision scanner directly over the official `com.jagex.oldscape.android` client (tested on Samsung Galaxy S23, One UI 6.1/7.0, Android 14/15, `1024x472` landscape aspect ratio).

- **[`AndroidManifest.xml`](app/src/main/AndroidManifest.xml)** — Declares `WaspAccessibilityService`, `StoreInstallResultReceiver`, and `<queries>` package visibility for `com.jagex.oldscape.android`.
- **[`res/xml/accessibility_service_config.xml`](app/src/main/res/xml/accessibility_service_config.xml)** — Configures `canPerformGestures="true"`, `canTakeScreenshot="true"`, and interactive-window retrieval (`canRetrieveWindowContent="true"`) for explicit foreground validation. It reads the root package rather than recursively traversing game nodes.
- **[`engine/WaspMobileState.kt`](app/src/main/java/com/example/waspmobile/engine/WaspMobileState.kt)** — Thread-safe `StateFlow<WaspTelemetry>` state container, `SharedPreferences` persistence, S23-calibrated `searchBoxNorm` / `invBoxNorm` defaults, and `TargetColorPreset` definitions.
- **[`engine/HumanTouchEngine.kt`](app/src/main/java/com/example/waspmobile/engine/HumanTouchEngine.kt)** — Biometric touch engine: 2D Gaussian thumb-pad sampling, `1.4..4.2px` capacitive thumb-roll micro-strokes, log-normal hold duration (`38..132ms`) with session fatigue drift, `gestureMutex` stroke serialization with `28..48ms` post-`ACTION_UP` flush window, 3-state Markov attention model (`FOCUSED`, `CASUAL`, `SECOND_MONITOR`), Anti-Slot-28 drop thresholds (`25..28`), and 4 human 28-slot drop patterns.
- **[`engine/MobileVisionEngine.kt`](app/src/main/java/com/example/waspmobile/engine/MobileVisionEngine.kt)** — Bulk `getPixels` computer vision engine (fewer calls, not zero native overhead):
  - **Translucent-Safe 28-Slot Inventory Detector (`detectFilledInventorySlots`)**: Uses 1px/2px sharp opaque item sprite edge gradients (`|R1-R2| + |G1-G2| + |B1-B2| > 85`), intra-slot `meanAbsDev >= 28.0f`, and luminance range/floor (`minLum <= 75 || lumRange >= 165`) to reject 3D trees/roofs showing through empty semi-transparent OSRS Mobile inventory slots.
  - **OSRS Mobile Tab & Hotkey Detectors (`isInventoryTabOpen`, `isTapToDropActive`)**: Detects whether the right-sidebar Backpack tab (`x = 0.916..0.935, y = 0.505..0.520`) and left-sidebar Tap-to-Drop button (`x = 0.055..0.075, y = 0.550..0.585`) are active.
  - **3D Cluster Scanner (`findTargetClusters`)**: Excludes the floating HUD pill (`excludeRect`), overlay paint colors, and the central player sprite (`x in 0.465..0.535, y in 0.425..0.565`), caps connected-component BFS growth to `<= 5x5` cells, and sorts clusters by distance to the player center `(0.50, 0.50)`.
- **[`service/WaspAccessibilityService.kt`](app/src/main/java/com/example/waspmobile/service/WaspAccessibilityService.kt)** — Floating HUD pill & pass-through `VisualOverlayCanvas`:
  - Red `[✕]` full-close button in the floating pill header (`shutdownAndDisable()` + `AccessibilityService.disableSelf()`).
  - 1-tap script mode chips (`🌲 Woodcut`, `⛏️ Miner`, `🎣 Fisher`, `✨ Combine`, `📱 Split 2x`, `🎒 TapDrop`) and 1-tap target color preset chips (`🌲 Leafy Tree`, `🪵 Trunk`, `🍂 Dead Tree`, `🪨 Iron`, `🟠 Copper`, `⚪ Tin`, `⚫ Coal`, `🐟 Bubbles`).
  - 1-tap point-and-release (`ACTION_UP`) eyedropper with `55ms` pre-screenshot overlay suppression and automatic backoff on `ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT`.
  - 8-handle interactive corner/edge/body-pan calibration for Search Box and 28-Slot Inventory Grid (`✅ Done` + `🔄 Reset`).
  - Automatic Backpack tab & Tap-to-Drop activation before dropping inventory items.
- **[`ui/main/MainScreen.kt`](app/src/main/java/com/example/waspmobile/ui/main/MainScreen.kt)** — Jetpack Compose dashboard with `✕ Close Overlay & Exit App` (`finishAndRemoveTask()`), 1-tap Accessibility/App-Info unlock helpers, tolerance slider, protected slot toggles, and live telemetry logs.
- **[`service/StoreInstallResultReceiver.kt`](app/src/main/java/com/example/waspmobile/service/StoreInstallResultReceiver.kt)** — `PackageInstaller.SessionParams.setPackageSource(PACKAGE_SOURCE_STORE)` self-session installer helper.

### 2. Android Unit & Adversarial Test Suite (`app/src/test/`)
- **[`vision/RealScreenshotVisionTest.kt`](app/src/test/java/com/example/waspmobile/vision/RealScreenshotVisionTest.kt)** — Fixture-dependent ground-truth tests against real Samsung Galaxy S23 OSRS Mobile screenshots (`exact {0, 1}` 2-log detection over translucent background, `isInventoryTabOpen`, and tree cluster isolation).
- **[`engine/MobileVisionEngineTest.kt`](app/src/test/java/com/example/waspmobile/engine/MobileVisionEngineTest.kt)**
- **[`engine/BiometricStateHardeningTest.kt`](app/src/test/java/com/example/waspmobile/engine/BiometricStateHardeningTest.kt)**
- **[`engine/CoordinateCutoutStressTest.kt`](app/src/test/java/com/example/waspmobile/engine/CoordinateCutoutStressTest.kt)**

### 3. Desktop Simba 2.0 + WaspLib Scripts (`pc-scripts/`)
- **[`pc-scripts/human_core.simba`](pc-scripts/human_core.simba)** — Biometric `TMouse.Move` wrist-pivot arc & pre-click dwell override, 3-state Markov attention model, Anti-Slot-28 thresholds, endless human RSN generator/mutator (`<= 12` chars), guarded public chat + LLM responder, and Swarm telemetry sync.
- **[`pc-scripts/tutorial_island_human.simba`](pc-scripts/tutorial_island_human.simba)** — Autonomous Tutorial Island onboarding (Display Name creation, randomized Character Design appearance, all 10 instructors -> Mainland Lumbridge).
- **[`pc-scripts/dynamic_woodcutter_rotator.simba`](pc-scripts/dynamic_woodcutter_rotator.simba)** — Multi-region Woodcutter rotator (Banking, Fletching, Firemaking, Power-Dropping).
- **[`pc-scripts/dynamic_miner_rotator.simba`](pc-scripts/dynamic_miner_rotator.simba)** — Multi-region Miner rotator with anti-depleted-rock selection.
- **[`pc-scripts/master_account_director.simba`](pc-scripts/master_account_director.simba)** — Multi-activity account director.

### 4. Mobile PWA Swarm Command Center & Proxy Launcher (`swarm-server/`)
- **[`swarm-server/server.js`](swarm-server/server.js)** — Zero-dependency Node.js HTTP server streaming live bot `.png` mini-screens, serving `WaspMobile.apk`, isolating per-bot `-Duser.home` directories, and enforcing a pre-launch SOCKS5 proxy kill-switch.
- **[`swarm-server/public/index.html`](swarm-server/public/index.html)** — Installable dark-mode Mobile PWA dashboard.
- **[`swarm-server/verify_swarm.js`](swarm-server/verify_swarm.js)** — 219-check automated E2E verification suite.

---

## 🔗 Upstream Ecosystem GitHub Links
- **WaspLib:** https://github.com/WaspScripts/WaspLib
- **Simba:** https://github.com/Villavu/Simba
- **WaspScripts:** https://github.com/WaspScripts/wasp-scripts
- **Wasp Launcher:** https://github.com/Torwent/wasp-launcher
- **RuneLite:** https://github.com/runelite/runelite
