# AirDraw — Android TV

The TV is the canvas. Your hand in the air is the pen.

| | |
|---|---|
| **Renderer** | native Android TV |
| **AEP version** | 0.8.0 |
| **Capabilities** | `camera.hand@1` |
| **Experience rules** | `core/` — no renderer, no Android, tested without a device |

## Why it exists

Two reasons, and the second is the one that matters to AEP.

**As a drawing tool.** MS Paint's freehand was bad because a mouse is a poor drawing instrument:
the wrist pivots, the desk constrains the arm, the pointer has no momentum. An arm in the air has
none of those problems — and one much worse one.

**As a proof.** Every AEP sample before this one was driven by `camera.pose`. AirDraw is the first
built on a different capability, and building it is what found the gap that 0.8.0 closed: the
Android host used to ignore an experience's declared capabilities and request pose regardless,
which left eleven of the thirteen unreachable. A platform that supports one capability in practice
is a platform that supports one capability.

## The hard part is 15 Hz

Hand tracking arrives at roughly **15 Hz**, where a mouse reports at 60–125. A hand crossing a
1.1 m TV at drawing speed moves about 6 cm between samples. Joining those points with straight
lines gives a visible polygon, which is worse than the mouse it is meant to beat.

So the interesting part of AirDraw is not the gesture. It is `core/`:

- **Catmull-Rom through every sample, evaluated once per display frame.** This does nearly all
  the work — it takes the mean turn between segments from 22.8° to 6.3°, and slightly improves
  accuracy, because a curve through noisy points averages the noise.
- **A One Euro filter that defaults to off.** The textbook answer for hand tracking earns nothing
  at the jitter body pose actually has, and costs up to 25 px of lag. It starts paying above
  roughly 16 px of jitter, so `StrokeSmoothing.forJitter()` decides from a measurement.
- **Width from speed**, so a fast stroke tapers and a slow one bears down.

All of it is arithmetic with no renderer in it, which is both what ADR-0001 rule 2 requires and
what lets the part that makes the product good be tested without a TV.

## Two hands, two jobs

The player pinches once, at the start, with the hand they draw with. That hand is the pen for the
rest of the session; the other one opens the tools.

| Gesture | Action |
|---|---|
| Drawing hand, open | move the cursor |
| Drawing hand, pinch | draw |
| Other hand, pinch and release | open or close the tool panel |
| Panel open: drawing hand pinch | take the colour, width or undo it is over |
| Both hands pinching | zoom and pan |

The pen tip is the midpoint of your thumb and index fingertips rather than a single landmark,
because pinching moves either fingertip several centimetres while their midpoint stays put. A
cursor that jumps when you start drawing is a cursor nobody can aim.

### Why there is no menu to pick your hand with

Hands are the only input this app has, and the handedness question comes before everything else.
So it is asked with the one gesture the player is about to need anyway: pinch with the hand you
draw with. The answer and the tutorial are the same gesture.

### Why the panel opens on release

Both hands pinching already means zoom. A panel that toggled the instant the off hand closed
would fire on the way into every zoom. So the toggle waits for the release, and is cancelled if
the drawing hand pinched at any point during it. Pinch alone → panel. Pinch together → zoom. No
timers, and nothing to tune.

### Why the panel sits on the drawing side

It is the hand that has to reach it, and it overlays the canvas rather than shrinking it — a
drawing that shifted sideways every time the tools appeared would be unusable. It is laid out in
`core/`, and the renderer paints exactly the cells the hit test uses, so a button cannot look like
it is somewhere it is not.

## Build

```bash
gh release download v0.8.0 --repo ssriramiyer29/amboexperienceplatform --dir libs
./gradlew assembleDebug
```

There is no AEP source in this tree and no path to any. It builds from published binaries or it
does not build — see `libs/README.md`.

## Run

1. Install the APK on an Android TV / Google TV device.
2. Open AmboCompanion on a phone on the same network and prop it up facing you.
3. Scan the code on screen.
4. Grant `camera.hand`.
5. Pinch with the hand you draw with, and draw.

## Known limits

**Hand tracking at three metres is unproven.** Body pose is well established at that range; hand
landmarks are smaller and may be considerably noisier. The stroke pipeline is built to be tuned
from a measurement rather than an assumption, and that measurement has not been taken — the band
edges in `StrokeSmoothing.forJitter()` come from simulation.

**Nothing is saved.** `Drawing` keeps strokes as data and can undo, redo and restore, but nothing
writes them anywhere. A drawing lives as long as the app does.

**The phone is a camera, not a tablet.** An earlier draft of this sample also took `input.touch`,
so the phone could be a pressure-sensitive surface for detail work. It was cut, because a phone
propped up watching your hands cannot also be in your hands — and a handover that needs the player
to pick the phone up, use it, put it down and re-aim it is a user journey, not a feature. The
pipeline still has the pressure path (`StrokeSource.TOUCH`, tested) for the version that works
that out.

**One hand does less.** If the provider reports handedness as `unknown` — which the schema
permits — two hands are told apart by position, but a single hand cannot be. It still draws; it
just cannot zoom or open the tools, because both of those need a second hand.
