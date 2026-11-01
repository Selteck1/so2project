# SO2 Project

Android app for the Standoff 2 market workflow.

## Current implementation

- Setup screen with all supplied weapon types, skin name, Regular/StatTrack, minimum sticker value and minimum profit.
- Standoff 2 detection through Android's installed launcher apps.
- Android Accessibility Service for screen capture, screen taps, swipes and focused text input.
- Gesture coordinates follow the actual captured frame dimensions (Standoff 2 landscape capture 1640x720 on a 720x1640 portrait handset), rather than assuming phone portrait coordinates.
- Draggable in-game control panel: pause/resume, stop, save screenshot, show last screenshot and OCR text.
- Per-control coordinate calibration screen with saved X/Y percentages.
- Manual sticker import: select a screenshot/image, enter sticker name and market price, compute a visual fingerprint and save the image and record to Room.
- Catalog scan flow opens each unseen sticker card and returns with Android Back; market pages are scrolled using the configured right-side scrollbar points.
- For profitable offer candidates, the assistant taps each present sticker in its fixed slot (1-4), goes back, checks the same offer price and that "С наклейками" remains enabled, then proceeds to the purchase dialog.
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

## Screen calibration and guide

The supplied screenshots are landscape (1640x720) while the phone display is 720x1640 portrait. Automation gestures use the captured game frame dimensions. Coordinate defaults are estimates from the supplied annotated images; open Settings -> Coordinate calibration to adjust each button, each sticker slot, the scrollbar and purchase controls. Manual sticker screenshots should be tightly cropped around one sticker; a blank variant field uses the image fingerprint as its unique variant key.

A separate 13-page PDF manual is generated from the user's annotated photos (foto1-foto12) and documents the workflow, slot positions, purchase dialog, insufficient-gold page, inspection view, error dialog and history.

## Important limitations

The app is built and its Kotlin/unit tests pass in CI. Actual Standoff 2 UI recognition and icon matching depend on game version, display scaling and the in-game text/image layout. The new open/back flow, landscape coordinate handling and floating panel are code-level changes; a physical-device session is still needed to validate touch acceptance, screen transitions and icon crop positions. Unknown screens and uncertain sticker matches fail closed instead of purchasing. Automatic purchase is only attempted after the current candidate passes visual matching and profit checks, then the confirmation dialog must match the exact item and price. A live device/game session is required to calibrate the final coordinates and visual threshold; this environment cannot emulate the real game.
