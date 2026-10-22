# Vector Surrealism theme

Dark fantasy, painted as a flat surreal vector poster. The backdrop is a night of jagged crags, with a castle on a cliff and a melting blood moon crossed by ravens. A dragon's eye in a storm cloud blinks now and then. A skeletal hand rises from a tattered sleeve holding up a crescent, candles melt over a ledge, and a serpent swims through the sky. A gothic doorway stands on the crags with a will-o'-wisp waiting in it, and relics float overhead.

The surreal vector language is unchanged: flat fills, hard offset long shadows that are never blurred, motifs at mismatched scales, a checkerboard floor and melting drips. Pointed gothic arches and scooped plaques now join the soft blobs.

To pick it, open **Settings → Look and feel → Theme → Vector Surrealism**. Its id is `vector-surrealism` and its root class is `html.theme-vector-surrealism`.

## Files

| What | Where |
| --- | --- |
| Design tokens (palette, shadows, radii and corner shapes, motion, fonts, asset paths) | `renderer/css/vector-surrealism.tokens.css` |
| Component rules (only read the tokens) | `renderer/css/vector-surrealism.css` |
| Decorative SVG art (original, `aria-hidden` in the page) | `renderer/assets/vector-surrealism/*.svg`, drawn by `scripts/gen-vector-surrealism-art.js` (run `node scripts/gen-vector-surrealism-art.js`) |
| Backdrop markup (`#vsWorld`) | `renderer/index.html` |
| Mouse parallax for the backdrop | `renderer/js/vsWorld.js` |
| River seekbar, with a dragon's eye for a playhead | `renderer/js/waveform.js`, `_drawVs()` |
| Registration | `renderer/js/store.js` (`THEMES`, `THEME_COLORS`) and the Theme picker in `renderer/js/app.js` |
| Fonts | `renderer/fonts/cinzel-latin-wght-normal.woff2`, `alegreya-sans-latin-{400,500,700,800}-normal.woff2` and `cormorant-garamond-latin-wght-normal.woff2`, each with its `OFL-*.txt` licence |

Two small hooks in shared code serve this theme without changing anything for the others:
- `icons.js` tags every icon with `data-i="<name>"`, so the theme can redraw ten of them with CSS `d: path()`. The redrawn icons are: a gothic door for the library, three spires for the equalizer, a dragon's eye for the visualization, a scroll for lyrics, a candle for the sleep timer, an eight-point star for settings, and melting transport glyphs.
- `libraryView.js` marks every other virtualized track row `.alt`, which gives the stepping-stone offset.

## The backdrop

| Motif | Asset | Where |
| --- | --- | --- |
| Night sky | `--vs-sky` | everywhere |
| Crags, a castle with lit windows, dead trees, low fog, a ridge melting over its edge | `crags.svg` | along the bottom |
| Checkerboard floor | CSS | in front of the crags |
| Floating relics: a rock island with a tower, a key, crystals, a potion, a sword, stars | `floaters.svg` | high in the sky |
| Dragon's eye in a storm cloud, with a bolt; it blinks | `storm-eye.svg`, `eyelid.svg` | top right |
| Candles melting over a ledge | `candles.svg` | off the left edge |
| Melting blood moon with two ravens | `blood-moon.svg` | behind the cover (narrow window); behind the castle, under the lyrics panel (wide window) |
| Skeletal hand holding up a crescent | `bone-hand.svg` | wide window, under the lyrics panel |
| Serpent swimming through the sky | `serpent.svg` | wide window, under the lyrics panel |
| Gothic doorway with a wisp inside | `gothic-door.svg` | wide window, on the crags under the library (hidden behind the equalizer) |

## Tokens

All colours live in the tokens file. `npm run check` fails if `vector-surrealism.css` contains a literal colour.

| Token | Value | Use |
| --- | --- | --- |
| `--vs-night` | `#160E20` | base background |
| `--vs-ink` | `#0A0610` | top of the sky; glyphs on light fills |
| `--vs-ember` | `#EF7D45` | candle ember: primary accent, the play orb |
| `--vs-mist` | `#A79BC2` | secondary surfaces and text |
| `--vs-wisp` | `#5ED6A8` | will-o'-wisp green: active and selected states |
| `--vs-gold` | `#E8BF5F` | old gold: highlights, focus ring, runes, flames |
| `--vs-blood` | `#EE5D72` | blood moon: alerts, favourites (rating stars) |
| `--vs-bone` | `#EEE2CB` | text on dark, light surfaces |
| `--vs-dusk`, `--vs-dusk-hi` | `#241933`, `#33234A` | raised flat surfaces (cards, rows, buttons) |
| `--vs-shade` | `#040208` | the hard "long shadow" tint |
| `--vs-horizon` | 24% blood in night | where the sky glows |
| `--vs-sky`, `--vs-sky-window` | 3-stop gradients | the only smooth gradients: the world's sky and the inside of windows |
| `--vs-flow`, `--vs-flow-v` | ember → blood | filled part of the seek river and sliders |
| `--vs-long-sm`, `--vs-long`, `--vs-long-lg` (each with an `-up` variant) | 3, 6 and 12px hard offsets | shadows. The `-up` variant is used on hover: the element lifts 4px and its shadow stays put. |
| `--vs-r-blob`, `--vs-r-card`, `--vs-r-stone` | asymmetric radii | soft blobs and stepping stones |
| `--vs-r-arch`, `--vs-r-door`, `--vs-r-window` with `--vs-cs-arch` | half-width radii + `corner-shape: superellipse(0.7)` | pointed gothic arches: buttons, tabs, the cover, dialogs, menus |
| `--vs-r-plaque` with `--vs-cs-plaque` | `corner-shape: scoop` | scooped plaques: the playing row (tiles, tooltips and toasts scoop their corners too) |
| `--vs-ease`, `--vs-t` | soft overshoot, 280ms | transitions |
| `--vs-display`, `--vs-ui`, `--vs-lyric`, `--vs-num` | Cinzel, Alegreya Sans, Cormorant Garamond, Alegreya Sans | type. Numbers use tabular lining figures (set on `body`) so times don't jitter. |

## Tweaking the palette

1. Change the eight palette values (and, if needed, the dusk surfaces) at the top of `vector-surrealism.tokens.css`. Nothing else in the CSS holds a colour.
2. The SVG art uses the same values (plus a few art-only shades). They are listed in `C` at the top of `scripts/gen-vector-surrealism-art.js`. Change them there and run the script.
3. Keep contrast at WCAG AA. The text pairs used are bone, mist, ember, gold, wisp and blood on night/dusk/dusk-hi, and ink on every light fill. At the defaults they range from 4.4:1 (blood on dusk-hi, used only for icons) to 15.6:1. Over a near-white cover in the wide layout, the player's text measures at least 6.1:1.
4. The seek river reads its colours from the tokens when the theme is applied, so it follows automatically.

## Motion and performance

- **Idle loops:** the drifting motifs, the blink and the turning rune ring animate `transform` only. They move in half-second steps (the blink in twelfths of a second), so the GPU redraws twice a second instead of every frame. The dark fantasy rework added no animations; the play button's second ring is static.
- **Parallax:** pointer moves are sampled at most every 90ms and written as two CSS variables; the layers ease to them with a CSS transition. There is no per-frame JavaScript.
- **When motion stops:** idle loops pause while the window is unfocused (`html.idle`). Loops and parallax stop under Reduce effects, under Windows' reduced-motion setting (which Reduce effects follows until the user chooses) and under Reduce animations.

## Not done

- **Light mode:** the app has no light/dark mode, so only the night palette exists. A dawn variant would be one more token block (bone base, ink text) under a class such as `html.theme-vector-surrealism.vs-dawn`.
