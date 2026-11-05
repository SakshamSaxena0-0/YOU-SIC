# Metal theme

A hi-fi faceplate, after the user's mockup of the library as plates of brushed metal. It replaces the Chrome theme. Anyone who had Chrome chosen is moved to Metal when the app starts (`store.js`).

## The look

- **Ground:** the window is one plate of brushed aluminium under a band of dark brushed gunmetal. The band covers the title bar and the three column headers and ends in a machined edge that casts a soft shadow.
- **Tiles:** the library's tiles are plates of brushed metal, each in its own finish. In tile order:
  graphite, silver, rose gold, brass, pewter, platinum, cherry, champagne, titanium, silver, smoke, copper, bronze, oxblood and jade.
- **Tile frames:** each plate sits in a polished bevelled frame, with a groove where the frame meets the face and a soft sheen that falls differently on each plate.
- **Tile contents:** icons sit in dark inset wells, tinted with their plate's colour. Names and counts are light mono capitals, raised off the plate.
- **Buttons on the plate:** keys and knobs of machined aluminium. The round knobs carry the rings a lathe leaves.
- **Copper:** the one accent. It marks play (a turned copper knob in a polished steel bezel), anything switched on, the played part of the song, and the sliders.
- **Header buttons:** clear rings of polished steel round a light glyph.
- **Text:** on the plate it is printed dark into the metal; on the band it is light.
- **The cover:** etched into the aluminium as line art, with outlines plus horizontal engraving lines across its dark parts. Each cut is a dark groove with a thin bright lip.
  - **Settings → Look and feel → Cover → Colour** shows the cover as it is instead.
  - Either way, in the wide layout it fades into the plate at every side, under the band, above the title block and into both panels. There is no edge and no coloured glow.

To pick it, open **Settings → Look and feel → Theme → Metal**. Its id is `metal` and its root class is `html.theme-metal`. With the etched cover on, `html.metal-etched` is set as well.

## Files

| What | Where |
| --- | --- |
| Design tokens (palette, the 13 finishes, textures, materials, shapes, motion, fonts) | `renderer/css/metal.tokens.css` |
| Component rules (they only read the tokens; `npm run check` fails on a literal colour) | `renderer/css/metal.css` |
| The etched cover: queue and cache (blob URLs, the last 12 covers) | `renderer/js/etch.js` |
| The etched cover: the image work, done in a worker | `renderer/js/etch.worker.js` |
| Where covers are drawn etched | `PlayerView._setImg` / `refreshArt` (`renderer/js/playerView.js`); `renderTrack` in `renderer/js/mini.js` |
| Seamless columns (the cover's side fades) | `renderer/css/seamless.css`; on while `html.seamless` (app.js `seamless`) |
| Seekbar: grooves cut in the aluminium, copper for what has played | `renderer/js/waveform.js`, `_drawMt()` |
| Mini player window | `renderer/css/mini.css` (`.mini-mat`) |
| Registration | `renderer/js/store.js` (`THEMES`, `THEME_COLORS`, `metalCover`) and the Theme and Cover pickers in `renderer/js/app.js` |
| Fonts | Manrope (titles in weight 800 capitals, interface) and JetBrains Mono (names on the plates, numbers); both were already bundled |

There is no art file. Every surface is a CSS gradient or a small SVG noise tile: the brushing is one stretched turbulence, split into light and dark streaks.

## The etched cover

The worker fetches the cover, scales it to 1000 px and works in luminance, stretched so that the darkest and brightest 2% reach the ends:

1. **Smoothing.** The cover is smoothed more the grainier it is, so film grain doesn't turn into speckle.
2. **Fine contours.** These come from the dark side of a difference of two small blurs. The threshold follows the cover's own edge strength (80th percentile, never below 0.018), so soft and sharp covers get a similar amount of line.
3. **Outlines of big shapes.** These are drawn where a difference of two large blurs changes sign, keeping the strongest 30% of those crossings.
4. **Engraving.** Horizontal lines run across the darkest ~22% of the tones and thicken as the tone darkens.
5. **Clean-up and output.** The lines are slightly emboldened, lone specks are removed, and the result is written as a PNG: dark groove, light lip, transparent everywhere else.

Each cover takes about 0.4 s, in the worker, so the window never stalls. The covers on either side of the current one are etched ahead of time, so a swipe finds them ready.

## Contrast

Primary text is 8.0:1 on the darkest part of the plate where text sits (`--mt-alu-4`) and 11.2:1 in the light. Secondary text is 6.0:1 there, and hints and numbers are 4.5:1. On the band, light text is 7.6:1 and dim text is 5.8:1 at the band's middle (4.4:1 at its very top, where only the title bar sits). Dark text on copper is 6.0:1 on the darkest copper key. The tile names are light on their plates, as in the mockup, with a dark raised shadow round every letter so they read on the brightest finish.

## Performance

- **Idle:** nothing loops, and every surface is static.
- **Textures:** the brushing is a small SVG tile that the browser rasterizes once and repeats.
- **No filters:** no blur filters and no large blurred layers.
- **Etching:** once per cover, off the main thread.
- **Measured:** in the test harness while playing in the wide layout, about 15% of one core, against 17.5% for Midnight in the same run.
