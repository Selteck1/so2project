# SO2 Project

Android companion app for the Standoff 2 market workflow.

## Foundation v0.1.0
- Setup UI for weapon, original skin name, and Regular/StatTrack.
- Standoff 2 launcher detection without hard-coded package ID.
- User-enabled Android Accessibility Service for screenshots and gesture support.
- On-device ML Kit text recognition and initial screen classification.
- Local Room database schema for sticker names, prices, visual variants and image fingerprints.
- Profit calculator and unit test for the supplied 15 G example.
- GitHub Actions workflow that builds a debug APK and uploads it as an artifact.

## CI build
\`gradle --no-daemon assembleDebug testDebugUnitTest\`

APK path: \`app/build/outputs/apk/debug/app-debug.apk\`.

## Scope
This is the first engineering foundation, not yet the final unattended buyer. The app currently provides setup, launches the game, captures its screen after the user enables the accessibility service, and reports recognized text/screen states. Exact market navigation, activity-feed parsing, sticker visual matching, purchase confirmation and low-balance handling must be calibrated against live device captures before automatic buying is enabled.

No private game API, memory editing, anti-cheat bypass or credential collection is used.
