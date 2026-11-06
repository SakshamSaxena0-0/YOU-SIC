# Metal theme

A hi-fi faceplate, after the user's mockup of the library as plates of brushed metal. It replaces the Chrome theme. Anyone who had Chrome chosen is moved to Metal when the app starts (`store.js`).

## The look

- **Ground:** the window is one plate of brushed aluminium under a band of dark brushed gunmetal. The band covers the title bar and the three column headers and ends in a machined edge that casts a soft shadow.
- **Tiles:** the library's tiles are thick cards of brushed metal, each in its own finish. In tile order:
  graphite, silver, rose gold, brass, pewter, platinum, cherry, champagne, titanium, silver, smoke, copper, bronze, oxblood and jade.
- **How a card is built** (after the user's close-up of the Folders tile), from the outside in:
  - a dark cut round the card's bay;
  - the bay's rim, in shade along the top and lit low and to the right;
  - a dark channel round the card;
  - the card's polished chamfer, brightest along its top;
  - a dark seam;
  - the face, brushed finely side to side, darker on the left, lit towards the right, with a soft sheen that falls differently on each card.

  The cards are glossy, because the user asked for more shine twice. The chamfer is bright along the top and glints along the bottom. Over the metal lies a clear coat (`--mt-card-polish`):
  - a fine light line under the chamfer;
  - the upper half of the face reflecting the light, down to a sharp horizon;
  - the lower half reflecting the shade;
  - a thin rim of reflected light along the bottom.

  The sheen has a bright core and slides along the card on hover. The counts sit in the brightest part of the gloss, so they get a dark edge all round. The flat keys on the plate (and the copper ones) have the same clear coat, with a horizon and a lit rim (`--mt-gloss`).

  The bay casts a long shadow on the plate. On hover it lifts and the shadow grows; on a click it presses in. The bay is the tile itself and the card is its `::before`.
- **Tile contents:** each icon sits in a well sunk into the card: lit along its top, dark in the middle, its floor catching a little light, with a bright lip below it. The glyph is tinted with the card's metal. Names and counts are light mono capitals, raised off the face by a dark edge under each letter.
- **Buttons on the plate:** keys and knobs of machined aluminium. The round knobs carry the rings a lathe leaves.
- **Copper:** the one accent. It marks play (a turned copper knob in a polished steel bezel), anything switched on, the played part of the song, and the sliders.
- **Header buttons:** clear rings of polished steel round a light glyph.
- **Text:** on the plate it is printed dark into the metal; on the band it is light.
- **The cover:** shown as it is, as in every theme. The theme doesn't change it: the user's rule is that a theme never alters the album art.
  - **Wide layout:** it fills the middle column, as in the other themes. It starts at the band's machined edge, so the band stays whole across the window and casts its shadow on the cover's top. Behind the title block and the controls it fades into the plate exactly as far as the other themes fade it into their scrim, and **Admire art** shows all of it.
  - **No borders between the columns,** as in Liquid Glass's Brutal mood (the user asked for the same): the cover fades out at both sides into its own light, which runs on across the plate under the library and the lyrics, so the columns meet without a seam. The light is the colour along each edge of the cover, drawn once per song (`ambient.js`). It stops at the band's edge and follows the cover's fade down the window. The masks are in `seamless.css`; Metal adds its own vertical profile.
  - **Narrow layout:** a square cover in a polished bezel.
  - An etched (line-art) cover was tried and removed: the user's rule is that the theme leaves album art alone.
- **Brushing: off.** The user found the streaks annoying even when faint, tried the theme without them and chose smooth satin metal. The last block of `metal.tokens.css` turns the brushing off everywhere (the plate, the band, the keys, the menus and the tiles). The brushing tokens are still defined, so deleting that block brings the brushed look back, at 0.19 and 0.15 opacity for the light and dark streaks.

To pick it, open **Settings → Look and feel → Theme → Metal**. Its id is `metal` and its root class is `html.theme-metal`.

## Light and dark

**Settings → Look and feel → Mode** chooses between the two. The setting is `metalMode` in `store.js`: `light` (the default) or `dark`.

- **Light:** brushed aluminium under a gunmetal band, as described above.
- **Dark:** the same faceplate in dark brushed steel. `app.js` sets `html.metal-dark`, and the block at the end of `metal.tokens.css` redefines:
  - the plate's ramp (`--mt-alu-0` is still the polished highlight and `--mt-alu-6` the deepest shade);
  - the text on the plate, now light;
  - the band, darker still;
  - the light: `--mt-lit` scales every bright lip and glint, so dark steel catches far less of it, and the brushing's light streaks are fainter;
  - the keys, the bezels and the cuts.

  The plate, keys, knobs, sheets and menus are built from those tokens, so the component rules don't change. The tiles keep their own metals. The seekbar (`_drawMt`) cuts its grooves in deep shade, with dimmer lips. The mini player follows the main window.

## Files

| What | Where |
| --- | --- |
| Design tokens (palette, the 13 finishes, textures, materials, shapes, motion, fonts; the dark mode block at the end) | `renderer/css/metal.tokens.css` |
| Component rules (they only read the tokens; `npm run check` fails on a literal colour) | `renderer/css/metal.css` |
| Seamless columns: the cover's side fades and its light in the panels | `renderer/css/seamless.css`; `renderer/js/ambient.js`; on while `html.seamless` (app.js `seamless`) |
| Seekbar: grooves cut in the aluminium, copper for what has played | `renderer/js/waveform.js`, `_drawMt()` |
| Mini player window | `renderer/css/mini.css` (`.mini-mat`) |
| Registration | `renderer/js/store.js` (`THEMES`, `THEME_COLORS`, `metalMode`) and the Theme and Mode pickers in `renderer/js/app.js` |
| Fonts | Manrope (titles in weight 800 capitals, interface) and JetBrains Mono (names on the plates, numbers); both were already bundled |

There is no art file. Every surface is a CSS gradient or a small SVG noise tile: the brushing is one stretched turbulence, split into light and dark streaks.

## Contrast

**Light mode:** primary text is 8.0:1 on the darkest part of the plate where text sits (`--mt-alu-4`) and 11.2:1 in the light. Secondary text is 6.0:1 there, and hints and numbers are 4.5:1. On the band, light text is 7.6:1 and dim text is 5.8:1 at the band's middle (4.4:1 at its very top, where only the title bar sits). Dark text on copper is 6.0:1 on the darkest copper key. The tile names are light on their cards, as in the mockup: 4.0 to 5.6:1 on the dark finishes and about 3.4:1 on brass and pewter; on the light ones (silver, platinum, rose gold, champagne) a dark edge under every letter carries them.

**Dark mode:** primary text is 7.7:1 on the plate's lightest part (`--mt-alu-1`) and 12.1:1 in its shade. Secondary text is 5.8:1 and hints are 4.6:1 on the lightest part. Light text on the band is 13:1.

## Performance

- **Idle:** nothing loops, and every surface is static.
- **Textures:** the brushing is a small SVG tile that the browser rasterizes once and repeats.
- **No filters:** no blur filters and no large blurred layers.
- **Measured:** in the test harness while playing in the wide layout, about 15% of one core, against 17.5% for Midnight in the same run.
