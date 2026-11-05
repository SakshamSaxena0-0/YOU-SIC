# Chrome theme

Satin metal on dark brushed gunmetal, after the user's mockup of the library as metal plates. Every button is smooth satin steel: broad, soft reflections, a diffuse sheen and a very fine matte grain, never a hard glossy band (that read as plastic). The category tiles are plates of satin silver in thin machined bezels, each with a bright outer edge, a dark groove and soft light along the inner top. All the plates softly reflect the same room, with two dark figures, a band of shade, a window light and a lamp. Each tile shows the part of the room in front of it, so some plates are mostly lit and some mostly in shade. The icons sit in inset squares of dark satin, and the labels are mono capitals. Round buttons are dark satin in turned steel rings. Play is a dome of bright satin silver with its glyph engraved in it. Anything switched on or primary turns to bright satin with dark engraved text.

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
| `--cr-satin` | satin silver: a smooth fall from light to shade (tile faces) |
| `--cr-room` | the room reflected softly over the tiles, six gradients in a 600 × 760 px room; chrome.css offsets each tile into it with `--cr-rx` / `--cr-ry` by its place in the grid |
| `--cr-grain` | the matte texture: grey noise at 5% (an SVG turbulence tile), laid over every metal surface |
| `--cr-satin-lite` / `--cr-satin-dome` | bright satin for plates carrying dark text (on, primary), and its domed form (play, studs, switch balls) |
| `--cr-satin-dark` + `--cr-gloss` / `--cr-dome` | dark satin with a soft, diffuse sheen: icon squares, round buttons, the dock, the mini player |
| `--cr-bezel` / `--cr-ring` | the thin machined frame round a plate (a border-box background behind a padding-box face), and the turned satin-steel ring round buttons |
| `--cr-groove` / `--cr-rim-out` / `--cr-outline` | the inside edge of a bezel (groove, soft top light, depth), its bright outer edge, and the black outline outside every piece of metal |
| `--cr-cut` / `--cr-pressed` | grooves cut into the metal (search, sliders, switches, badges), and a pressed button |
| `--cr-floor` | the ground: dark gunmetal brushed side to side, lit from above |

## Contrast

Primary text (`--cr-text`) is 12.6:1 on the lightest panel (`--cr-iron-7`). Secondary text is 6.0:1 there and 7.5:1 on the ground. Dark engraved text (`--cr-ink`) is 11.4:1 on `--cr-steel-2` and 7.3:1 on `--cr-steel-3`, the darkest of the bright satin. Light text on the tiles has a dark shadow round it, so it stays readable wherever the reflection falls.

## Performance

Nothing loops, and every surface is a static gradient (the grain a small cached image tile) with no filters. Only hover and press briefly change brightness. Measured in the test harness at 13% of one core while playing in the wide layout, against 12% for Midnight in the same run.
