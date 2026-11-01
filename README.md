# SO2 Project

Android app for the Standoff 2 market workflow.

## Current implementation

- Setup screen with all supplied weapon types, skin name, Regular/StatTrack, minimum sticker value and minimum profit.
- Standoff 2 detection through Android's installed launcher apps.
- Android Accessibility Service for screen capture, screen taps, swipes and focused text input.
- Russian + English offline OCR using Tesseract4Android.
- Word-level OCR coordinates for price and button localization.
- Market navigation by screen state: open Market, reset filters, select category, fill the skin search field, apply filter and open the matching skin.
- “With stickers” toggle detection that avoids toggling a checkbox already enabled.
- Recent activity observer and price extraction (comma/dot decimal formats).
- Local Room sticker catalogue with source name, observed price, image, visual fingerprint and visual variant.
- Catalogue scrolling and card collection.
- Similarity matching of offer-row sticker icons against stored visual fingerprints.
- Three bottom-most offers inspected for each newly observed matching price.
- Profit calculator; the formula reproduces the supplied example (15 G profit).
- Strict purchase-dialog validation: the dialog must show the selected weapon/skin and the exact candidate price.
- Insufficient-balance detection, screenshot saving, monitoring stop and Android screen lock.
- GitHub Actions workflow to build and publish a debug APK artifact.

## Build

\`\`\`bash
gradle --no-daemon assembleDebug testDebugUnitTest
\`\`\`

APK output: \`app/build/outputs/apk/debug/app-debug.apk\`.

## Important limitations

The app is built and its Kotlin/unit tests pass in CI. Actual Standoff 2 UI recognition and icon matching depend on game version, display scaling and the in-game text/image layout. Unknown screens and uncertain sticker matches fail closed instead of purchasing. Automatic purchase is only attempted after the current candidate passes visual matching and profit checks, then the confirmation dialog must match the exact item and price. A live device/game session is required to calibrate the final coordinates and visual threshold; this environment cannot emulate the real game.
