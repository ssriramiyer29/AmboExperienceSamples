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

Hand tracking arrives at **10.5 Hz** — measured on a television, against the 15 the design
assumed — where a mouse reports at 60–125. A hand crossing a 1.1 m TV at drawing speed moves the
better part of 10 cm between samples. Joining those points with straight lines gives a visible
polygon, which is worse than the mouse it is meant to beat.

So the interesting part of AirDraw is not the gesture. It is `core/`:

- **Catmull-Rom through every sample, evaluated once per display frame.** This does nearly all
  the work — it takes the mean turn between segments from 22.8° to 6.3°, and slightly improves
  accuracy, because a curve through noisy points averages the noise.
- **A One Euro filter that defaults to off.** The textbook answer for hand tracking earns nothing
  at the jitter body pose actually has, and costs up to 25 px of lag. It starts paying above
  roughly 16 px of jitter, so `StrokeSmoothing.forJitter()` decides from a measurement.
- **Width from speed**, so a fast stroke tapers and a slow one bears down.
- **Pinch thresholds taken from a real session**, not from a guess: a held pinch reads a median of
  0.72 and dips to 0.36, an open hand reads 0.00 and reaches 0.35, so the latch enters at 0.50 and
  leaves at 0.22.

All of it is arithmetic with no renderer in it, which is both what ADR-0001 rule 2 requires and
what lets the part that makes the product good be tested without a TV.

## How it is used

| Gesture | Action |
|---|---|
| Pinch, on the canvas | draw |
| Open the hand | lift the pen |
| Pinch, on the strip | take the colour, undo, or flip the strip to the other side |
| Both hands pinching | zoom and pan |

**Whichever hand pinches is the pen.** There is nothing to set up. An earlier version asked the
player to nominate a drawing hand at the start, and then chose for them off the first pinch it
saw — which, on a real television, was a hand curled around a phone being set down. Wrong hand,
no way back, session ruined. The question was the mistake: the hand that is pinching is the hand
that is drawing.

The pen tip is the midpoint of your thumb and index fingertips rather than a single landmark,
because pinching moves either fingertip several centimetres while their midpoint stays put. A
cursor that jumps when you start drawing is a cursor nobody can aim.

### The palette is always on screen

Six percent of the width, permanently, and ninety-four percent is canvas. The version before this
hid the palette behind an off-hand gesture and saved the space. What it cost was a player stranded
on a colour they had not picked, with the gesture that would have released them refusing to fire.
A control you cannot get back to is worse than one that takes up room.

A pinch that *begins* on the strip takes what it is over. A stroke that began on the canvas keeps
drawing wherever the hand goes, including across the strip — so the palette can take a pinch, but
it can never take a mark already in progress.

There is no width picker. Ten targets down the edge of a screen is small for a hand wavering at
three metres, and a target that cannot be hit reliably is worse than one that is not offered.
Line weight still varies with speed inside a stroke; you just do not choose a base.

### Zoom measures from an anchor, not from the last frame

Two hands pinching zooms and pans. The gesture takes one anchor when it starts and measures
everything from that, which sounds like an implementation detail and is the entire difference
between a zoom that holds still and one that crawls: measured frame to frame, each frame's
tracking noise is applied and then kept, so the canvas performs a random walk while two hands are
held perfectly still. The separation is also low-passed, and changes below a threshold are
ignored — measured against what has already been applied, so a slow deliberate pan still works,
it just arrives in steps.

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
5. Pinch and draw. Either hand.

While tuning, the app prints what it sees roughly once a second:

```bash
adb logcat -s AirDraw
# tracked=true hands=2 L=0.31 R=0.78 pinchL=false pinchR=true bridging=false
#   raw=0.52,0.41 drawing=true zoom=false scale=1.00 strokes=4
```

`L` and `R` are pinch strengths, `bridging` means a pinch is being held across a dropout, and
`raw` is the hand's position in the camera frame before it is mapped onto the canvas — which is
what the active-area bounds should be set from.

## Known limits

**It works at about 5–6 feet, and not much outside that.** Further and the tracker stops finding
hands at all; closer and it responds badly. Anything built on this — a demo, a store listing, a
room — has to assume that window until the provider widens it.

**Hand loss climbs during a session, and the cause is not established.** Counting frames rather
than sampling them, one session averaged 12% frames with no hand over its first eighty seconds
and 65% after them. Thermal throttling is the obvious suspect: AmboKit's certification record has
the same device reaching `THERMAL_STATUS_SEVERE` in about a minute under camera plus on-device
ML, and attributes its 14.8–18.8 Hz pose-rate spread to thermal state rather than noise.

But that session also had the player moving between distances, and distance has a large effect of
its own, so elapsed time and distance are confounded in it. The test that separates them has not
been run: a cold device, a fixed 5–6 feet, three minutes, watching whether loss climbs. Until
that exists this is a correlation with a plausible mechanism, not a diagnosis.

Either way it is the wall AirDraw is up against, and it is not in AirDraw — everything below is
compensation for it.

It is also why **zoom is unreliable**. Two hands appeared together in 2 of 25 intervals, and zoom
needs both tracked and both pinching at the same instant.

**The provider loses the hand, often.** In a logged session it reported no hands at all in 13% of
frames — a dozen dropouts in two minutes. That, not the pinch threshold, is what used to chop a
drawn line into pieces: thirty-nine strokes were recorded for a handful of intended marks. A pinch
now survives 300 ms of absence, and a hand that reappears more than a tenth of a screen away
starts a new stroke rather than ruling a line across the drawing. The dropouts themselves are the
Companion's to fix.

**The canvas learns your reach instead of assuming it.** Fixed bounds were tried and a logged
session showed why they cannot work: the player's hand never went below 0.60 in the camera frame
while the canvas bottom was pinned at 0.78, so the lowest quarter of the drawing surface was past
the end of their arm. `ReachEnvelope` starts at the first hand it sees and grows a fraction of the
way towards wherever hands actually go, so a real reach pulls it out over a few passes while one
bad detection barely moves it. It grows only while the pen is up — a mapping that shifted
mid-stroke would slide the line sideways under the hand drawing it.

This is a jitter fix as much as a comfort one. Reaching for an edge past the end of someone's arm
takes their hand out of the camera frame, tracking is lost, and the stroke breaks. Bad bounds show
up as jitter, which is not where anyone would look for them.

**Hand tracking at three metres is half proven.** The strokes came out natural on a real
television at real distance, which was the claim that could have failed. What is still unmeasured
is the landmark jitter the band edges in `StrokeSmoothing.forJitter()` were guessed from.
Everything that felt wrong on first contact was control, not rendering.

**Startup used to kill the app.** AirDraw built its QR code with `setPixel` in a nested loop on
the UI thread — 409,600 JNI crossings — which on a television's SoC took longer than the five
seconds Android waits before declaring an app unresponsive. It was intermittent because it was a
race between that loop and the window taking focus. Encoding is one array call on a worker thread
now. Worth remembering as a shape of bug: it never showed up in a build, a test or a check, only
in an ANR trace with the main thread at 94% user CPU.

**Nothing is saved.** `Drawing` keeps strokes as data and can undo, redo and restore, but nothing
writes them anywhere. A drawing lives as long as the app does.

**The phone is a camera, not a tablet.** An earlier draft of this sample also took `input.touch`,
so the phone could be a pressure-sensitive surface for detail work. It was cut, because a phone
propped up watching your hands cannot also be in your hands — and a handover that needs the player
to pick the phone up, use it, put it down and re-aim it is a user journey, not a feature. The
pipeline still has the pressure path (`StrokeSource.TOUCH`, tested) for the version that works
that out.

**One hand does less.** If the provider reports handedness as `unknown` — which the schema
permits — two hands are told apart by position, but a single hand cannot be. It still draws and
still picks colours; it cannot zoom, which needs a second hand by definition.

**Everything rides on one gesture.** Pinch draws, pinch picks, two pinches zoom. That is a lot of
meaning on a channel that is noisy at this distance, and it is why the controls felt jittery while
the strokes did not. `audio.speech@1` is the obvious way out — it is on-device, its `ready` payload
guarantees `rawAudioShared: false`, and AirDraw itself would need no microphone permission because
the phone does the listening. Saying "red" or "undo" would leave pinch meaning exactly one thing.
Not built yet.
