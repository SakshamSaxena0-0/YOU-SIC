# Midnight theme

A city at 3 a.m., seen from a dark room. The air is near-black blue. A far skyline runs along the bottom with a few cold cyan windows, an odd warm one, and one bright street. Every control is dark polished gunmetal: brushed metal, a wet gloss across the top, and a chrome hairline rim that catches the city's cyan light when you reach for it. Pressing a button sinks it into the panel, and anything switched on lights up inside like lit glass. Play is a machined knob face (spun rings, a gloss dome) in a polished chrome bezel. Cold light comes up under it while music plays, and the progress ring glows cyan. The cover is a glossy print in a thin chrome frame, reflected in the wet floor beneath it. The seekbar is city light on a wet street.

To pick it, open **Settings → Look and feel → Theme → Midnight**. Its id is `midnight` and its root class is `html.theme-midnight`.

## Files

| What | Where |
| --- | --- |
| Design tokens (palette, light, materials, shapes, motion, fonts, asset paths) | `renderer/css/midnight.tokens.css` |
| Component rules (only read the tokens) | `renderer/css/midnight.css` |
| Decorative art (original, generated, `aria-hidden` in the page) | `renderer/assets/midnight/city.svg`; the sky, haze and street light are CSS gradients |
| Backdrop markup | `renderer/index.html`: `#mnWorld` (sky, city, street, vignette) |
| Seekbar: city light on a wet street | `renderer/js/waveform.js`, `_drawMn()` |
| Registration | `renderer/js/store.js` (`THEMES`, `THEME_COLORS`) and the Theme picker in `renderer/js/app.js` |
| Fonts | IBM Plex Sans (titles in weight 300, interface), Michroma (engraved labels) and JetBrains Mono (numbers), all bundled already |

## Materials

The button look comes from five tokens, layered as backgrounds and shadows:

| Token | What it is |
| --- | --- |
| `--mn-spun` / `--mn-drawn` | brushed gunmetal: a conic gradient for round buttons, fine vertical lines for flat ones |
| `--mn-gloss` / `--mn-gloss-dome` / `--mn-gloss-wide` | the wet highlight: a hard-horizon band on small buttons, a dome on round ones, an angled reflection on wide tiles |
| `--mn-rim` / `--mn-rim-lit` | the specular top edge, dark underside and chrome hairline; the lit version turns the hairline cyan (hover, on) |
| `--mn-pressed` | the sunk-in state while pressed, and grooves (search, sliders, switches) |
| `--mn-lit` | lit glass: primary buttons, chips that are on, the active EQ tab |

## Contrast

Primary text (`--mn-white`) is 10.6:1 on the lightest panel (`--mn-steel-6`), and secondary text (`--mn-mist`) is 4.8:1 there and 7.6:1 on the background. Ice text on lit glass is 6.4:1 at its middle.

## Performance

Nothing loops: the backdrop is static, and the only animation is the title block fading in on a track change (off under Reduce effects or reduced motion). Measured in the test harness at 2.1% CPU while playing (wide layout) and 0.7% while paused (narrow layout).
