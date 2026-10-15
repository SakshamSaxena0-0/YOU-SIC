# Vector Surrealism theme

A flat, dreamlike vector-poster look: a dusk sky, a melting moon, flat hills over a checkerboard floor, an eye in a cloud that blinks now and then, a hand holding up a crescent, a soft clock and a doorway to nowhere. To pick it, open **Settings → Look and feel → Theme → Vector Surrealism**. Its id is `vector-surrealism` and its root class is `html.theme-vector-surrealism`.

## Files

| What | Where |
| --- | --- |
| Design tokens (palette, shadows, radii, motion, fonts, asset paths) | `renderer/css/vector-surrealism.tokens.css` |
| Component rules (only read the tokens) | `renderer/css/vector-surrealism.css` |
| Decorative SVG art (original, `aria-hidden` in the page) | `renderer/assets/vector-surrealism/*.svg` |
| Backdrop markup (`#vsWorld`) | `renderer/index.html` |
| Mouse parallax for the backdrop | `renderer/js/vsWorld.js` |
| River seekbar with an eye for a playhead | `renderer/js/waveform.js`, `_drawVs()` |
| Registration | `renderer/js/store.js` (`THEMES`, `THEME_COLORS`) and the Theme picker in `renderer/js/app.js` |
| Fonts | `renderer/fonts/fraunces-latin-full-normal.woff2` and `space-grotesk-latin-wght-normal.woff2`, with `OFL-*.txt` licences. JetBrains Mono was already bundled. |

Two small hooks in shared code serve this theme without changing anything for the others:
- `icons.js` tags every icon with `data-i="<name>"`, so the theme can redraw ten of them with CSS `d: path()`.
- `libraryView.js` marks every other virtualized track row `.alt`, which gives the stepping-stone offset.

## Tokens

All colours live in the tokens file. `npm run check` fails if `vector-surrealism.css` contains a literal colour.

| Token | Value | Use |
| --- | --- | --- |
| `--vs-indigo` | `#1B1340` | base background |
| `--vs-ink` | `#0F0A2B` | top of the sky; glyphs on light fills |
| `--vs-coral` | `#FF8A5B` | horizon glow, primary accent, the play "sun" |
| `--vs-lavender` | `#B8A1FF` | secondary surfaces and text |
| `--vs-teal` | `#2EC4B6` | active and selected states |
| `--vs-sun` | `#FFC93C` | highlights, focus ring |
| `--vs-blush` | `#FF5FA2` | alerts, favourites (rating stars) |
| `--vs-cream` | `#FFF1DC` | text on dark, light surfaces |
| `--vs-dusk`, `--vs-dusk-hi` | `#2A1F5C`, `#3A2C78` | raised flat surfaces (cards, rows, buttons) |
| `--vs-shade` | `#07041A` | the hard "long shadow" tint |
| `--vs-horizon` | 26% coral in indigo | where the sky glows |
| `--vs-sky`, `--vs-sky-window` | 3-stop gradients | the only smooth gradients: the world's sky and the inside of windows |
| `--vs-flow`, `--vs-flow-v` | coral → blush | filled part of the seek river and sliders |
| `--vs-long-sm`, `--vs-long`, `--vs-long-lg` (each with an `-up` variant) | 3, 6 and 12px hard offsets | shadows. The `-up` variant is used on hover: the element lifts 4px and its shadow stays put. |
| `--vs-r-blob`, `--vs-r-card`, `--vs-r-stone`, `--vs-r-arch`, `--vs-r-door`, `--vs-r-window` | asymmetric radii | the shape language |
| `--vs-ease`, `--vs-t` | soft overshoot, 280ms | transitions |
| `--vs-display`, `--vs-ui`, `--vs-num` | Fraunces, Space Grotesk, JetBrains Mono | type |

## Tweaking the palette

1. Change the eight palette values (and, if needed, the dusk surfaces) at the top of `vector-surrealism.tokens.css`. Nothing else holds a colour.
2. The SVG art in `renderer/assets/vector-surrealism/` uses the same hex values. To recolour the art, find and replace the old hex value in those files.
3. Keep contrast at WCAG AA. The text pairs used are cream, lavender, coral, sun, teal and blush on indigo/dusk/dusk-hi, and ink on every light fill. At the defaults they range from 4.9:1 to 17:1. The horizon is held at 26% coral because that is the warmest glow at which lavender and coral text still pass on it.
4. The seek river reads its colours from the tokens when the theme is applied, so it follows automatically.

## Motion and performance

- **Idle loops:** the drifting motifs, the blink and the turning sun rays animate `transform` only. They move in half-second steps (the blink in twelfths of a second), so the GPU redraws twice a second instead of every frame. Measured while playing: 8.2% CPU with smooth loops, 3.0% with the stepped ones (1.0% paused, 0.4% with Reduce effects).
- **Parallax:** pointer moves are sampled at most every 90ms and written as two CSS variables; the layers ease to them with a CSS transition. There is no per-frame JavaScript.
- **When motion stops:** idle loops pause while the window is unfocused (`html.idle`). Loops and parallax stop under Reduce effects, under Windows' reduced-motion setting (which Reduce effects follows until the user chooses) and under Reduce animations.

## Not done

- **Light mode:** the app has no light/dark mode, so only the dusk palette exists. A dawn variant would be one more token block (cream base, ink text) under a class such as `html.theme-vector-surrealism.vs-dawn`.
