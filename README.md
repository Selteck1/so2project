# SO2 Project

Android companion app for the Standoff 2 market workflow.

## Current foundation

- Android setup UI for weapon, skin title, Regular/StatTrack, minimum sticker price and minimum profit.
- Standoff 2 launcher detection without a hard-coded package ID.
- User-enabled Android Accessibility Service for screen capture and gesture support.
- On-device Russian + English OCR with Tesseract4Android.
- OCR language models are downloaded once into app-private storage when the accessibility service starts.
- Local Room database schema for sticker names, prices, visual variants and image fingerprints.
- Profit calculator and unit test for the supplied 15 G example.
- Stop-monitoring control.
- GitHub Actions workflow that builds a debug APK and uploads it as an artifact.

## Build

CI runs:

\`\`\`bash
gradle --no-daemon assembleDebug testDebugUnitTest
\`\`\`

Debug APK: \`app/build/outputs/apk/debug/app-debug.apk\`.

## Important

This is the first engineering foundation, not yet the final unattended buyer. The app can launch the game, capture its screen after the user enables the accessibility service, and report recognized text/screen states. Exact market navigation, activity-feed parsing, sticker visual matching, purchase confirmation and low-balance handling still need calibration against live device captures before automatic buying is enabled. This avoids purchases caused by a wrong screen match or OCR error.

No private game API, memory editing, anti-cheat bypass or credential collection is used.
