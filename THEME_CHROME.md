# Chrome theme

Mirror-polished chrome on dark brushed gunmetal, after the user's mockup of the library as chrome plates. The category tiles are plates of mirror chrome in thick bevelled bezels: a bright sky over a soft horizon, a dark floor beneath it, and a dark groove and a raised lip inside each frame. All the plates reflect the same room, with dark figures, two window streaks and a lamp, and each tile shows the part of the room in front of it. The icons sit in inset squares of black chrome, and the labels are mono capitals raised off the metal. Round buttons are black chrome in turned steel rings. Play is a dome of bright chrome with its glyph engraved in it. Anything switched on or primary turns to bright chrome with dark engraved text.

The theme has no colour of its own. The album art keeps its colours untouched. In the wide layout its edges dissolve into the library and lyrics panels as its own light, with no lines between the columns (`css/seamless.css`, `js/ambient.js`, shared with Liquid Glass's Brutal mood).

To pick it, open **Settings → Look and feel → Theme → Chrome**. Its id is `chrome` and its root class is `html.theme-chrome`.

## Files

| What | Where |
| --- | --- |
| Design tokens (palette, light, materials, shapes, motion, fonts) | `renderer/css/chrome.tokens.css` |
| Component rules (only read the tokens) | `renderer/css/chrome.css` |
| Seamless columns (cover edges, ambient light) | `renderer/css/seamless.css`, `renderer/js/ambient.js`; on while `html.seamless` (app.js `seamless`) |
| Seekbar: steel bars on a mirror | `renderer/js/waveform.js`, `_drawCr()` |
| Mini player window | `renderer/css/mini.css` (`.mini-mat`) |
| Registration | `renderer/js/store.js` (`THEMES`, `THEME_COLORS`) and the Theme picker in `renderer/js/app.js` |
| Fonts | Manrope (titles in weight 800 capitals, interface) and JetBrains Mono (labels, numbers), both bundled already |

There is no art file: every surface is a CSS gradient.

## Materials

| Token | What it is |
| --- | --- |
| `--cr-mirror` | mirror chrome: sky, soft horizon, dark floor, lit lower edge (tile faces) |
| `--cr-room` | the reflected room laid over the tiles, five gradients in a 560 × 700 px room; chrome.css offsets each tile into it with `--cr-rx` / `--cr-ry` by its place in the grid |
| `--cr-mirror-lite` / `--cr-mirror-dome` | bright chrome for plates carrying dark text (on, primary), and its domed form (play, studs, switch balls) |
| `--cr-black` + `--cr-gloss` / `--cr-dome` | black chrome: icon squares, round buttons, the dock, the mini player |
| `--cr-bezel` / `--cr-ring` | the thick frame round a plate (a border-box background behind a padding-box face), and the turned steel ring round buttons (a glint on every corner) |
| `--cr-groove` / `--cr-outline` | the inside edge of a bezel (groove, lip, depth), and the black outline outside every piece of chrome |
| `--cr-cut` / `--cr-pressed` | grooves cut into the metal (search, sliders, switches, badges), and a pressed button |
| `--cr-floor` | the ground: dark gunmetal brushed side to side, lit from above |

## Contrast

Primary text (`--cr-text`) is 12.6:1 on the lightest panel (`--cr-iron-7`). Secondary text is 6.0:1 there and 7.5:1 on the ground. Dark engraved text (`--cr-ink`) is 11.4:1 on `--cr-steel-2` and 7.3:1 on `--cr-steel-3`, the darkest of the bright chrome. Light text on the tiles has a dark shadow round it, so it stays readable wherever the reflection falls.

## Performance

Nothing loops, and every surface is a static gradient with no filters. Only hover and press briefly change brightness. Measured in the test harness at 13% of one core while playing in the wide layout, against 12% for Midnight in the same run.
