# Metal theme

A hi-fi faceplate, after the user's mockup of the library as plates of brushed metal. It replaces the Chrome theme. Anyone who had Chrome chosen is moved to Metal when the app starts (`store.js`).

## The look

- **Ground:** the window is one plate of brushed aluminium under a band of dark brushed gunmetal. The band covers the title bar and the library's and lyrics' headers and ends in a machined edge that casts a soft shadow. In the wide layout the player has no header (the user asked for nothing over the cover), so in the middle column the cover runs up through the band to the window's top edge.
- **Tiles (the library's buttons):** heavy milled plates of deep emerald brushed metal set into the chassis, after the user's two specs (premium audio hardware; `--mt-emerald-*` and `--mt-em-*` in `metal.tokens.css`, section 9 of `metal.css`). A hairline seat (shade above, light below); a three-layer bezel: a crisp 1px outer frame a little darker than the chassis, the chamfer (bright satin top left, velvet bottom right) and a razor-thin satin-sage reflection just inside it along the top and left (the user's #2A5A44, lifted with satin silver so it stays lighter than the sheen); then the face, a satin-silver sheen top left falling to a deep velvet forest green bottom right, under a fine horizontal brushing (about 2%; the rest of the theme stays unbrushed). The icon sits in a well stamped into the plate (concave: a crisp dark edge and depth shadow on its top and left inner walls, a 1px lip of light on its bottom inner edge). The icon, name and count are etched: raw silver-green metal, a crisp 1px near-black green shadow along the top. No glow, no gloss. Set into the chassis, a button doesn't lift: hover lights it a touch, a press pushes it in. Names are shown as written (no forced capitals). The construction notes below describe the graphite tiles before this, which the Folders list cards still follow.
  - The other finishes are still defined in `metal.tokens.css`. Deleting the last rule of the finishes in section 9 of `metal.css` gives each tile its own metal again. In tile order: graphite, silver, rose gold, brass, pewter, platinum, cherry, champagne, titanium, silver, smoke, copper, bronze, oxblood and jade.
- **How a card is built** (after the user's close-up of the Folders tile), from the outside in:
  - a dark cut round the card's bay;
  - the bay's rim, in shade along the top and lit low and to the right;
  - a dark channel round the card;
  - the card's edge, a soft machined bevel, lighter along its top;
  - a dark seam;
  - the face, darker on the left and lit softly towards the right.

  The cards and every button are **matte**. The user tried them glossy (a clear coat with a sharp horizon, and a sheen that slid on hover) and chose matte instead. There is no clear coat, no glint and no sheen: only soft light from above and a soft edge.

  The bay casts a long shadow on the plate. On hover it lifts and the shadow grows; on a click it presses in. The bay is the tile itself and the card is its `::before`.
- **The Folders list:** opening the Folders tile shows each folder as a **milled emerald plate built exactly like the library's buttons** (the user asked for the same on the list, after magenta, platinum and royal blue), with the same `--mt-em-*` tokens: the hairline seat, the three-layer bezel, the satin-silver-to-velvet face under the 2% brushing (the sheen held to the plate's top left corner in pixels, since the plate is wide), etched lettering. The plates are solid like the buttons (`--mt-fold-card-a`, 1; it was 0.6, see-through, before). The folder's cover is shown as it is, in a well stamped into the plate: the walls are drawn round the cover (shade on its top and left, a 1px lip of light below), never over it; a folder without a cover shows its glyph in a well like the buttons' icons. Names are bold (Barlow Semi Condensed 700, bundled) and **in their own case**: never forced to capitals (the user's rule for every future update). No lift: hover lights a plate, a press pushes it in. The hook is `.lib-page[data-node='folders']` (`libraryView.js` tags each page with its view). The royal blue ramp (`--mt-royal-*`) is still defined.
- **Tile contents:** each icon sits in a well sunk into the card: lit along its top, dark in the middle, its floor catching a little light, with a bright lip below it. The glyph is tinted with the card's metal. Names and counts are printed in light, warm grey capitals (see Lettering below).
- **Buttons:** every button in the theme is graphite, like the Folders tile, in both modes. The tokens are `--mt-btn-*` at the end of `metal.tokens.css`.
  - **Flat keys** (text buttons, chips, the theme and mode pickers, the dock's open tab, the lyrics/visualizer and equalizer tabs, the mini player button, the keys on the cover's back) are matte graphite with a soft machined edge.
  - **Round buttons:** the header buttons on the band are matte graphite keys. The transport knobs, play, the window studs and the equalizer's knobs are matte graphite faces in matte graphite rings. The five transport keys are all the same small size (44px, the user's choice), play included, with the copper progress ring close round play.
  - **Thumbs:** the slider thumbs, the equalizer's band thumbs, the seekbar's knob and the toggles' balls are graphite too.
  - **Small icon buttons** (a row's menu, a folder's remove) stay a bare glyph until hovered, then show a graphite key.
- **Lettering:** after the user's close-up of the Folders tile. The text on every button and tile is printed on the metal in a light, warm grey (`--mt-btn-text`; `--mt-card-text` on a tile, from the card's own ink), with a soft dark shadow under it (`--mt-btn-letter`). The face is Barlow Semi Condensed, a DIN-like face (`--mt-btn-font`), in weight 600.
  - **Where:** tile names (capitals) and counts, text keys, chips, the folder keys, the keys on the cover's back, the tabs (capitals) and the mini player button. The theme picker still shows each theme's name in that theme's own face where it has one. The selected entry in a list and the shortcut keycaps keep their own faces and take only the colour and the shadow.
  - **Glyphs:** icons on keys and knobs are the same light grey with a soft shadow under them (`--mt-btn-glyph`), and turn white on hover.
  - **The tabs:** the open tab's label is on its key. The closed tabs' labels sit dimmer on the dark slot.
  - **History:** the user asked for lettering engraved like a premium metal card and tried three versions: light frosted letters ("looks like written text"), dark grooves with a light edge under them ("awful"), then light letters with a hard dark edge. They then asked for all of that to be undone and for the text to look like their close-up instead, which is what it is now.
- **Copper:** the one accent. It marks anything switched on and fills the played part of the song, the ring round play, and the sliders and toggles.
  - **A key that is on** has copper lettering (a copper glyph on a knob) and a copper inlay round it; shuffle and repeat also light a small copper lamp in the knob's face.
  - **The primary text button's** label is copper.
- **Text:** on the plate it is printed dark into the metal; on the band it is light.
- **The cover:** shown as it is, as in every theme. The theme doesn't change it: the user's rule is that a theme never alters the album art.
  - **Wide layout:** it fills the middle column, as in the other themes. It runs up to the window's top edge: there is no header over it, and its sides fade into the band up there. The song's menu, the mini player and Admire art (a fullscreen key, "Fullscreen (Admire Art)") sit in the bottom corners beside the dock, drawn like the header keys. Behind the title block and the controls it fades into the plate exactly as far as the other themes fade it into their scrim, and **Admire art** shows all of it: the fade eases away and back over 0.45s with the controls (`--mt-art-open`, a registered number the mask reads).
  - **No borders between the columns,** as in Liquid Glass's Brutal mood (the user asked for the same): the cover fades out at both sides into its own light, which runs on across the plate under the library and the lyrics, so the columns meet without a seam. The light is the colour along each edge of the cover, drawn once per song (`ambient.js`). It stops at the band's edge and follows the cover's fade down the window. The masks are in `seamless.css`; Metal adds its own vertical profile.
  - **Narrow layout:** a square cover in a polished bezel.
  - An etched (line-art) cover was tried and removed: the user's rule is that the theme leaves album art alone.
- **Brushing: off.** The user found the streaks annoying even when faint, tried the theme without them and chose smooth satin metal. The last block of `metal.tokens.css` turns the brushing off everywhere (the plate, the band, the keys, the menus and the tiles). The brushing tokens are still defined, so deleting that block brings the brushed look back, at 0.19 and 0.15 opacity for the light and dark streaks.

To pick it, open **Settings → Look and feel → Theme → Metal**. Its id is `metal` and its root class is `html.theme-metal`.

## Light and dark

**Settings → Look and feel → Mode** chooses between the two. The setting is `metalMode` in `store.js`: `light` (the default) or `dark`.

- **Light:** satin aluminium under a gunmetal band, as described above.
- **Dark:** the same faceplate in dark satin steel. `app.js` sets `html.metal-dark`, and the block at the end of `metal.tokens.css` redefines:
  - the plate's ramp (`--mt-alu-0` is still the polished highlight and `--mt-alu-6` the deepest shade);
  - the text on the plate, now light;
  - the band, darker still;
  - the light: `--mt-lit` scales every bright lip and glint, so dark steel catches far less of it, and the brushing's light streaks are fainter;
  - the keys, the bezels and the cuts.

  The plate, keys, knobs, sheets and menus are built from those tokens, so the component rules don't change. The tiles and the buttons stay graphite. The seekbar (`_drawMt`) cuts its grooves in deep shade, with dimmer lips. The mini player follows the main window.

## Files

| What | Where |
| --- | --- |
| Design tokens (palette, the 13 finishes, textures, materials, shapes, motion, fonts; the dark mode block at the end) | `renderer/css/metal.tokens.css` |
| Component rules (they only read the tokens; `npm run check` fails on a literal colour) | `renderer/css/metal.css` |
| Seamless columns: the cover's side fades and its light in the panels | `renderer/css/seamless.css`; `renderer/js/ambient.js`; on while `html.seamless` (app.js `seamless`) |
| Seekbar: grooves cut in the aluminium, copper for what has played, a graphite knob | `renderer/js/waveform.js`, `_drawMt()` |
| Mini player window | `renderer/css/mini.css` (`.mini-mat`) |
| Registration | `renderer/js/store.js` (`THEMES`, `THEME_COLORS`, `metalMode`) and the Theme and Mode pickers in `renderer/js/app.js` |
| Fonts | Manrope (titles in weight 800 capitals, interface), JetBrains Mono (numbers) and Barlow Semi Condensed 600 and 700 (the lettering on the buttons and tiles; 700 for the Folders list; bundled in `renderer/fonts` with `OFL-BarlowSemiCondensed.txt`, declared at the top of `metal.tokens.css`) |

There is no art file. Every surface is a CSS gradient or a small SVG noise tile: the brushing is one stretched turbulence, split into light and dark streaks.

## Contrast

**Light mode:** primary text is 8.0:1 on the darkest part of the plate where text sits (`--mt-alu-4`) and 11.2:1 in the light. Secondary text is 6.0:1 there, and hints and numbers are 4.5:1. On the band, light text is 7.6:1 and dim text is 5.8:1 at the band's middle (4.4:1 at its very top, where only the title bar sits). Dark text on copper is 6.0:1 on the darkest copper key. The tile names are light on their cards, as in the mockup: 4.0 to 5.6:1 on the dark finishes and about 3.4:1 on brass and pewter; on the light ones (silver, platinum, rose gold, champagne) a dark edge under every letter carries them.

**Buttons:** the lettering is about 3.4 to 3.8:1 against a key and 4.0 to 4.8:1 on a tile; the soft shadow under each letter sets it off a little more. Copper lettering (on) also has a copper inlay round the key. For more contrast, raise the ink's share in `--mt-btn-text` and `--mt-card-text` (76% now).

**Dark mode:** primary text is 7.7:1 on the plate's lightest part (`--mt-alu-1`) and 12.1:1 in its shade. Secondary text is 5.8:1 and hints are 4.6:1 on the lightest part. Light text on the band is 13:1.

## Performance

- **Idle:** nothing loops, and every surface is static.
- **Textures:** the brushing is a small SVG tile that the browser rasterizes once and repeats.
- **No filters:** no blur filters and no large blurred layers.
- **Measured:** in the test harness while playing in the wide layout, about 15% of one core, against 17.5% for Midnight in the same run.
