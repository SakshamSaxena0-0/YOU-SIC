# Liminal theme

A vintage dream, like an old film photograph. Behind everything is a soft sunset gradient: dusk periwinkle at the top melting through mauve into dusty rose and peach, with soft blooms of colour in the corners. Anything bright gets a warm halo (halation), and there are light leaks, heavy grain, a vignette and the rounded, darkened edge of a film print. The gradient's warm glow sits behind the album cover, so the cover glows. Play is the sun itself, its halo full while music plays and fading when paused. The seek times are printed like the orange date stamp of an old camera, and the seekbar is late sun glittering on water.

To pick it, open **Settings → Look and feel → Theme → Liminal**. Its id is `liminal` and its root class is `html.theme-liminal`.

## Files

| What | Where |
| --- | --- |
| Design tokens (palette, light, film effects, shapes, motion, fonts, asset paths) | `renderer/css/liminal.tokens.css` |
| Component rules (only read the tokens) | `renderer/css/liminal.css` |
| Decorative art (original, `aria-hidden` in the page) | `renderer/assets/liminal/grain.svg`; the background itself is CSS gradients (`.lm-gradient` in `liminal.css`) |
| Backdrop markup | `renderer/index.html`: `#lmWorld` behind the app (gradient, light leaks, vignette, grain) and `.lm-film` over it (the print's edge and a fine grain) |
| Lining the glow up with the cover | `renderer/js/vsWorld.js` (`alignSun()`, next to Vector Surrealism's parallax) |
| Seekbar: sun glittering on water | `renderer/js/waveform.js`, `_drawLm()` |
| Registration | `renderer/js/store.js` (`THEMES`, `THEME_COLORS`) and the Theme picker in `renderer/js/app.js` |
| Fonts | Cormorant Garamond (titles; `cormorant-garamond-latin-wght-normal.woff2`, `…-italic.woff2`), IBM Plex Sans (interface; `ibm-plex-sans-latin-wght-normal.woff2`) and DSEG7 Classic Italic (the date stamp; `DSEG7Classic-Italic.woff2`), with `OFL-CormorantGaramond.txt`, `OFL-IBMPlexSans.txt` and `OFL-DSEG.txt`. JetBrains Mono was already bundled. |

`alignSun()` measures the cover whenever the layout changes and writes its centre as `--vx`/`--vy` on `#lmWorld`; the gradient's warm glow is centred there, in the wide and the narrow layout.

## Tokens

All colours live in the tokens file. `npm run check` fails if `liminal.css` contains a literal colour.

| Token | Value | Use |
| --- | --- | --- |
| `--lm-ink` | `#241A26` | the deepest shadow (plum-brown: faded film never reaches black); glyphs on light fills |
| `--lm-dusk`, `--lm-dusk-hi` | `#3A2C40`, `#4A3A52` | panels; buttons and hover |
| `--lm-cream` | `#FFF2E2` | primary text |
| `--lm-haze` | `#D9C6DA` | secondary text |
| `--lm-peach` | `#F7C59F` | the sunset: accent, selected states |
| `--lm-gold` | `#FFD98A` | the sun: highlights, the playhead, the focus ring |
| `--lm-rose` | `#EBA6B3` | favourites (rating stars), warnings |
| `--lm-sky` | `#8E9CC8` | periwinkle: the top of the gradient |
| `--lm-stamp` | `#FFA552` | the date-stamp orange |
| `--lm-bloom`, `--lm-bloom-gold`, `--lm-text-glow` | soft glows | halation round lit things (static elements only) |
| `--lm-panel`, `--lm-panel-solid`, `--lm-sunlit`, `--lm-edge` | | dusk-haze panels, a warm wash from above, a soft lit edge |
| `--lm-sun`, `--lm-flow`, `--lm-flow-v` | gradients | the play button and small suns; filled progress (rose to gold) |
| `--lm-veil`, `--lm-floor-shade`, `--lm-dusk-top` | | the right-hand pane; a soft shade behind the player's title block and times; dusk gathering at the top |
| `--lm-vignette`, `--lm-leak-warm`, `--lm-leak-rose` | gradients | the film effects |
| `--lm-r-sm`, `--lm-r`, `--lm-r-lg`, `--lm-r-pill` | 8, 14, 22px, pill | everything soft |
| `--lm-ease`, `--lm-t` | slow ease-out, 420ms | transitions, slow like a memory |
| `--lm-display`, `--lm-ui`, `--lm-num`, `--lm-stamp-font` | Cormorant Garamond, IBM Plex Sans, JetBrains Mono, DSEG7 Classic | type |

## Tweaking

1. Change the palette at the top of `liminal.tokens.css`. Nothing else in the components holds a colour.
2. The background gradient (`.lm-gradient`) is built from the palette tokens, so it follows them.
3. The seekbar reads its colours from the tokens when the theme is applied.
4. Keep contrast at WCAG AA. On panels, cream is 9.4:1 or more and haze 6.5:1; ink on peach, gold, rose or cream is 8.5:1 or more. Text that sits straight on the background was measured from screenshots in the wide, equalizer and narrow layouts, against the lighter pixels of its background: everything came out at 4.63:1 or better. That covers the header, the title block, the date-stamp times and the dock labels. It relies on `--lm-dusk-top`, `--lm-floor-shade`, the soft shadow behind "Playing from" and the dark halo behind the stamp digits. If you brighten the gradient, measure again.

## Motion and performance

- **Idle loop:** one. The grain jumps four times a second, like real film (`transform` only); each jump costs a frame, so it is kept slow. It pauses while the window is unfocused (`html.idle`). Everything else is static.
- **Measured CPU** (whole app, test harness): 2.6% playing in the wide layout, 3.7% playing in the narrow layout, 1.0% paused, 0.5% with Reduce effects.
- **When motion stops:** under Reduce effects (which follows Windows' reduced-motion setting until the user chooses), Windows' reduced motion and Reduce animations, the grain stops jumping. The picture stays.

## Not done

- **Light mode:** the app has no light/dark mode, so only this dusk palette exists.
- **Icons** keep their standard shapes.
