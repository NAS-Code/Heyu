# Heyu logo

An hourglass whose top and bottom halves are chat bubbles. Their tails meet at the hourglass neck, and the
text lines inside flow through it: in the top bubble they run left to right and hook down into the neck; in
the bottom bubble they come up out of the neck and run left to right.

| File | What it is |
|---|---|
| `heyu-icon.svg` | Full icon: white artwork on the orange background, square, full bleed |
| `heyu-icon-foreground.svg` | White artwork on transparent |
| `make_icon.py` | Generator. Rebuilds both SVGs and the Android icon files from the spec below |

To change the logo, edit the constants in `make_icon.py` and run it (Python 3, standard library only):

```
python docs/logo/make_icon.py
```

It overwrites `app/src/main/res/drawable/ic_launcher_foreground.xml` and `ic_launcher_background.xml`,
plus the two SVGs here. Don't hand-edit those four files.

To export a PNG, open either SVG in a browser, Figma, Inkscape or Illustrator and export at any size.
They are vectors, so every size is sharp. The SVGs are 1080×1080 px by default.

## Colors

| Role | Hex |
|---|---|
| Background | `#FF7518` (pumpkin orange) |
| Artwork | `#FFFFFF` |

The text lines are holes cut through the bubbles, not drawn in orange, so whatever is behind the artwork
shows through them. That makes the same drawing work as Android's one-color themed icon.

## Geometry

All coordinates are in a **108 × 108** canvas, origin at the top left, y pointing down. That's Android's
adaptive-icon canvas: launchers mask it to a circle, squircle or rounded square, and only the centered
**66-unit circle** (center `54,54`, radius 33) is guaranteed visible. All artwork sits inside that circle.

The design is symmetric under a **180° rotation about the neck point (54, 54)**: the bottom half is the top
half turned upside down.

### Frame bars
Two bars, each **36 wide × 3 tall** with fully rounded ends (radius 1.5):
- Top: x 36–72, y 27–30
- Bottom: x 36–72, y 78–81

### Bubbles
Each bubble is a **32 × 16** rounded rectangle, corner radius **6**, with a triangular tail pointing at the neck.
- **Top bubble:** body x 38–70, y 32–48. Tail base from (49, 48) to (59, 48), tip at (54, 54).
- **Bottom bubble:** body x 38–70, y 60–76. Tail base from (49, 60) to (59, 60), tip at (54, 54).

The two tail tips touch at the neck.

### Text lines
Four lines, each a stroke **2.5 wide** with **square (flat) ends**, cut out of the bubbles. Each one follows a
centerline made of a straight segment and then a hook:

- **Straight:** horizontal from x = 44 to `turn_x`.
- **Hook:** a quadratic Bézier from (`turn_x`, y), control point (`turn_x` + 5, y), ending at (`turn_x` + 3, y + 7.25).
  All four lines share this exact hook.
- The whole top set is then **shifted left by 1.5**.

| Line | y | turn_x | Centerline after the shift |
|---|---|---|---|
| Top, upper (long) | 37.25 | 57 | (42.5, 37.25) → (55.5, 37.25), hook via (60.5, 37.25) to (58.5, 44.5) |
| Top, lower (short) | 41.75 | 52 | (42.5, 41.75) → (50.5, 41.75), hook via (55.5, 41.75) to (53.5, 49) |

The bottom lines are the top lines rotated 180° about (54, 54), so they shift right by 1.5 and hook
up out of the neck:

| Line | Centerline |
|---|---|
| Bottom, upper (short) | from (54.5, 59) hooking via (52.5, 66.25) to (57.5, 66.25) → (65.5, 66.25) |
| Bottom, lower (long) | from (49.5, 63.5) hooking via (47.5, 70.75) to (52.5, 70.75) → (65.5, 70.75) |

The short top line's hook ends inside the top tail, so it reads as text sliding into the neck. The short
bottom line mirrors this, coming out of the bottom tail.

## In the Android app
`app/src/main/res/mipmap-anydpi/ic_launcher.xml` (and `ic_launcher_round.xml`) is an adaptive icon made of
`@drawable/ic_launcher_background`, with `@drawable/ic_launcher_foreground` used as both the foreground and
the monochrome (themed) layer. There are no PNG launcher icons, because the app's minimum Android version
always uses the adaptive vector.
