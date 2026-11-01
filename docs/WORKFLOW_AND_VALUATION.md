# Market workflow and valuation rules

## Navigation

1. Find Standoff 2 on the device and start it.
2. Choose the weapon, original skin name and Regular/StatTrack category.
3. Open Market and Filter; reset filters, retain the Weapons item type, select the requested category, enter \`WEAPON "SKIN"\`, and Apply.
4. Read the selected skin's base market price from its result card before opening it.
5. Open the listing and enable “С наклейками”.
6. Watch the bottom recent-activity panel for a new listing action and parse the gold price.
7. For matching offers at that price, inspect the three lowest visible rows.
8. Match each offer sticker icon with the local catalogue; an unknown icon rejects that row.
9. Calculate profit using the base price from the ordinary skin result card (not the sticker-offer prices).
10. Only a profitable row proceeds to Buy. The purchase modal must contain the exact selected item and exact price.
11. If insufficient gold is reported, save a screenshot, stop monitoring and lock the screen. A generic error is dismissed only if its OK button is found; otherwise automation pauses.

## Profit example

- Cheapest base-skin lot: 100 G.
- One qualifying sticker: 1000 G; three other stickers: 50 G each.
- Candidate purchase price: 200 G.
- Count sticker prices >= 500 G.
- Sticker credit: 1000 * 20% = 200 G.
- Adjusted resale estimate: (100 + 200) * 80% = 240 G.
- Fixed costs: 240 - 25 = 215 G.
- Profit: 215 - 200 = 15 G.
- Accept when profit >= configured minimum (default 10 G).

The prose “subtract 85%” conflicts with the numeric 1000 -> 200 example; the implementation follows the example with 20% credit.

## Catalogue

Sticker rows store the recognised title, price, local image path, image fingerprint and visual variant. Offer-row matching compares both the shape fingerprint and average colour. If the same-looking variants are too close for a confident comparison, the item is skipped. The user-configured sticker minimum is separate from the 500 G threshold used by the example's resale formula.

## Screens supplied

- foto1: main menu; pistol icon opens inventory, shopping-cart icon opens Market.
- foto2: inventory.
- foto3: Market: price sorting, Filter and History.
- foto4: Filter: item type, rarity, collection, category, name field, Reset and Apply.
- foto5: result cards with weapon name and base price.
- foto6-foto7: item offer rows, sticker toggle, sticker icons, buy buttons and bottom recent-activity panel.
- foto8: purchase confirmation modal; it can report insufficient funds.
- foto9: top-up page after insufficient gold.
- foto10: item inspection/3D view.
- foto11: generic error dialog with OK.
- foto12: History / Sales History.

## Failure-safe rules

On a new candidate, check the activity event, price, item context, base price, sticker icons and profit. Do not use a price from the sticker-offer list as the base skin price. On unknown screen, OCR uncertainty, unrecognised icons, missing price or mismatched purchase modal, do not confirm a purchase.


## Device and gesture calibration

The user's phone resolution is 720x1640 in portrait. The attached Standoff 2 screenshots are 1640x720 landscape. Gesture coordinates are therefore normalized against the actual captured screenshot frame, not fixed to portrait displayMetrics. Saved coordinate overrides are available for market entry, filter, reset, sticker type, price sorting, apply, "С наклейками", each of four sticker slots, buy, confirmation, close, insufficient-gold back and the right scrollbar.

## Per-sticker inspection

For a profitable candidate row, the scanner keeps the original fixed slot index for each visible sticker. It opens each visible sticker in sequence, sends Android Global Back, verifies that the original offers screen and exact row price are visible again, and confirms the "С наклейками" toggle remains enabled. Only then does it proceed to the purchase dialog. Empty slots are skipped; slot positions are never compacted. If a transition or row-price check fails, the workflow pauses.

## Manual catalog import and controls

The database page includes manual image import. A tightly cropped sticker screenshot, user-entered name, price and optional variant are stored in Room with a visual fingerprint. Leaving the variant blank makes the fingerprint the variant key. The in-game panel includes pause/resume, stop, screenshot and preview/OCR controls.

The screenshot guide is generated separately as a 13-page PDF using foto1-foto12. Its annotations are descriptive references and its coordinate defaults should still be calibrated for the current game version/device.
