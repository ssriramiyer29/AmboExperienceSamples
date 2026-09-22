# AirDraw — Android TV

The TV is the canvas. Your hand in the air is one instrument; your phone is the other.

| | |
|---|---|
| **Renderer** | native Android TV |
| **AEP version** | 0.8.0 |
| **Capabilities** | `camera.hand@1`, `input.touch@1` |
| **Experience rules** | `core/` — no renderer, no Android, tested without a device |

## Why it exists

MS Paint's freehand drawing was bad because a mouse is a poor drawing instrument: the wrist
pivots, the desk constrains the arm, the pointer has no momentum. An arm in the air has none of
those problems — and one much worse one.

Hand tracking arrives at roughly **15 Hz**, where a mouse reports at 60–125. A hand crossing a
1.1 m TV at drawing speed moves about 6 cm between samples. Joining those points with straight
lines gives a visible polygon, which is worse than the mouse it is meant to beat.

So the interesting part of AirDraw is not the gesture. It is `core/`:

- **Catmull-Rom through every sample, evaluated once per display frame.** This does nearly all
  the work — it takes the mean turn between segments from 22.8° to 6.3°, and slightly improves
  accuracy, because a curve through noisy points averages the noise.
- **A One Euro filter that defaults to off.** The textbook answer for hand tracking earns nothing
  at the jitter body pose actually has, and costs up to 25 px of lag. It starts paying above
  roughly 16 px of jitter, so the app measures jitter and `StrokeSmoothing.forJitter()` decides.
- **Width from pressure or speed**, depending on which instrument drew the stroke.

All of it is arithmetic with no renderer in it, which is both what ADR-0001 rule 2 requires and
what lets the part that makes the product good be tested without a TV.

## Two instruments, one canvas

| | Air (`camera.hand`) | Phone (`input.touch`) |
|---|---|---|
| Best at | big expressive strokes | precision and detail |
| Sample rate | ~15 Hz | the screen's rate |
| Width from | speed | **pressure** |
| Zoom | two hands pinching | two fingers |
| Needs a camera | yes | **no** |

That last row matters. `input.touch@1` declares no data class and touches no camera, so AirDraw
has a genuine no-camera mode that is not a degraded one — a pressure-sensitive tablet driving a
TV canvas. For a child's drawing app, "draw on the TV without turning on a camera" is a sentence
worth being able to say.

## Gestures

| Gesture | Action |
|---|---|
| One hand, open | move the cursor |
| One hand, pinch | draw |
| Two hands, both pinching | zoom and pan |
| One finger on the phone | draw, with pressure |

The pen tip is the midpoint of your thumb and index fingertips rather than a single landmark,
because pinching moves either fingertip several centimetres while their midpoint stays put. A
cursor that jumps when you start drawing is a cursor nobody can aim.

## Build

```bash
gh release download v0.8.0 --repo ssriramiyer29/amboexperienceplatform --dir libs
./gradlew assembleDebug
```

There is no AEP source in this tree and no path to any. It builds from published binaries or it
does not build — see `libs/README.md`.

## Run

1. Install the APK on an Android TV / Google TV device.
2. Open AmboCompanion on a phone on the same network.
3. Scan the code on screen.
4. Grant `camera.hand`, `input.touch`, or both.
5. Pinch and draw.

## Known limits

This is the first slice. The palette, undo, save and the phone-side tool surface are designed in
`core/` and not yet wired to anything a player can reach: one colour, one width.

Hand tracking at three metres is unproven. Body pose is well established at that range; hand
landmarks are smaller and may be considerably noisier. The stroke pipeline is built to be tuned
from a measurement rather than an assumption, and that measurement has not been taken yet.
