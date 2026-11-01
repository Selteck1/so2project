# Market workflow and valuation rules

## Interpreted navigation
1. Find the installed Standoff 2 app by launcher label.
2. Choose weapon, original skin name, and Regular or StatTrack.
3. Open Market, open Filter, select Weapons and stickers, enter \`WEAPON "SKIN"\`, select category, then Apply.
4. Open the matching skin and enable "With stickers".
5. Watch the recent-activity area for new listing events and extract the gold price.
6. If several matching listings share a price, inspect the three requested lowest/nearest rows.
7. Match sticker icon variants against local catalog references, then estimate profit.
8. Before a future automated purchase, re-check item and exact price on the confirmation dialog.
9. On insufficient balance, save a screenshot and stop. Locking the screen is not powering off the phone.

## Example calculation
- Cheapest base-skin lot: 100 G.
- One qualifying sticker: 1000 G; three other stickers: 50 G each.
- Candidate purchase price: 200 G.
- Count only stickers >= 500 G.
- 1000 * 20% = 200 G sticker credit.
- (100 + 200) * 80% = 240 G.
- 240 - 25 = 215 G.
- 215 - 200 = 15 G estimated profit.
- Accept when profit >= configured minimum, default 10 G.

The phrase "subtract 85%" conflicts with the worked example. The code uses 20% credit because that reproduces 1000 -> 200.

## Catalog records
Each sticker stores exact title, observed market price, visual variant, local image path, visual hash and update time. Near-identical holographic variants require saved image crops; text recognition alone is insufficient.

## Supplied screenshot map
- foto1: main menu; pistol icon = inventory, cart = market.
- foto2: inventory.
- foto3: market; sorting by price, Filter and History.
- foto4: filter; Weapons, Stickers, name field, Regular/Pattern/StatTrack, Reset and Apply.
- foto5: result list; item cards show title and price.
- foto6-foto7: item offers; With stickers toggle, sticker icons, Buy buttons, scroll and recent activity.
- foto8: purchase confirmation dialog.
- foto9: insufficient gold page.
- foto10: item inspection/3D view with back button.
- foto11: generic error dialog; dismiss with OK.
- foto12: market History, Sales History.

## Preconditions before unattended buying
Require exact weapon/skin/category, a newly appeared listing event, reliable price parsing, sticker-variant match, profit threshold, sufficient balance, and a confirmation dialog matching the same item and price. On unknown screen, OCR conflict or generic error, stop rather than tap Buy.
