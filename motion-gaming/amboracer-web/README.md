# AmboRacer

Steer with a phone, race in a browser. The reference sample for AEP's **web renderer**.

Your laptop is the screen. Your phone is the steering wheel: tilt to steer, tap to start. The
phone's camera is never used and no video ever leaves it — the only thing on the wire is the
angle the phone is held at.

## What it demonstrates

| | |
|---|---|
| `motion.orientation@1` | steering, read with the generated `AepOrientationFrame` |
| `input.touch@1` | tap to start, read with the generated `AepTouch` |
| Discovery | neither capability has a bespoke AEP event; both arrive on the generic path and are typed from the same schema the Companion produces them from |
| ADR-0001 rule 2 | `rules/` contains the whole game and names no canvas, no DOM and no frame clock |
| ADR-0002 rule L16 | the experience declares two capabilities and is granted what the player allows — it plays without `input.touch`, and says so plainly without `motion.orientation` |

## Running it

You need the AmboKit Gateway, this page served over the same network, and an Android phone with
the AmboKit Companion.

```bash
# 1. the Gateway, from your AmboKit checkout
node server/run.mjs            # listens on :8787

# 2. this sample
npm install                    # resolves AEP from libs/, nothing from a registry
npm run build                  # bundles to dist/amboracer.js
python3 -m http.server 8080    # or any static server

# 3. open it, telling the page where the Gateway is
#    http://<your-laptop-lan-ip>:8080/?gateway=http://<your-laptop-lan-ip>:8787
```

The LAN address matters twice: the phone scans a QR pointing at the Gateway, so `localhost` is
your phone rather than your laptop and pairing silently fails. Use the address your laptop has on
the wifi both devices are on.

Deployed with the Gateway on the same origin — which is what AmboKit ships an nginx config for —
none of that applies and `?gateway=` can be dropped.

## Embedding it

The build is one self-contained file. Copy `dist/amboracer.js` and the `.amboracer` block from
`index.html`, and point it at your Gateway:

```html
<div class="amboracer" data-ambo-gateway="https://your-site.example"> … </div>
<script type="module" src="/amboracer.js"></script>
```

AEP is bundled in rather than fetched from a CDN. A sample whose behaviour depends on what a CDN
served that day is not a reference for anything.

## Controls

| | |
|---|---|
| Tilt the phone | steer |
| Tap the phone, or space | start, pause, resume, race again |
| Arrow keys | steer without a phone |
| `C` | re-centre |
| `1` `2` `3` | steer on yaw, pitch or roll - a measuring tool, see below |

The keyboard is not a fallback bolted on. A reference sample that only works when a phone, a
Gateway and a wifi network all behave is a sample nobody can open, and if the keyboard drives the
car while the phone does not, the game is fine and the capability is not.

## What a real phone taught this sample

Every one of these came from one evening's play and none of them from reading the code.

**The steering axis is yaw, not roll.** This file used to say roll, reasoned like so: a phone held
upright and tilted left and right rotates about the axis Android calls roll. The convention was
right and the model of the player was wrong - nobody holds a phone upright to steer. They hold it
flat and turn it like a wheel, which is yaw. Held flat, roll barely moves and is noisy, which is
exactly how it felt. The axis was never the uncertain part; how a person holds the thing was.

**Yaw drifts, so the centre has to follow.** `motion.orientation` is requested with
`reference: "game"` - no magnetic north, which the catalogue recommends - so yaw is integrated
rather than absolute. The captured centre follows the phone slowly while the player goes straight,
and about seven times slower while they hold a turn. The second rate exists because the first
version adapted only inside the deadzone and was a ratchet: once drift pushed the offset past
three degrees nothing could pull it back, the car developed a permanent lean, and the only cure
was re-centring by hand every single time.

**"Hold the phone still" has to mean it.** The centre was captured from the first frames that
arrived, which is precisely when the player is lowering the phone from scanning a QR code to
holding it like a wheel. The centre landed somewhere mid-movement. Capture now restarts whenever
two consecutive frames differ by more than two degrees.

**Neither edge of the road may be safe.** Obstacles spawned across `[0.12, 0.88]`, the car could
reach `0` and `1`, and the hit reach is `0.115` - so the nearest possible obstacle to a car pinned
at the edge was two thousandths too far away to ever touch it. Both verges were a lane you could
park in for the whole race. The car and the obstacles now share one `roadMargin`.

The numbers along the bottom of the screen are what found most of this, and they are still there
on purpose. They print all three angles rather than only the chosen one, because the reading that
cannot tell you whether your guess was right is the one you guessed.

## A trap worth knowing about

`libs/*.tgz` are resolved by path, so **npm will not notice a tarball that changed without a
version bump**. `npm install` says "up to date" and the old code stays in the bundle. That never
happens across a release, where a new version means a new filename - but it happens constantly
while the platform and the sample are being changed together. When in doubt:

```bash
rm -rf node_modules package-lock.json
npm install
```

The Android samples have no equivalent problem because a changed AAR has a changed filename.

## Layout

```
rules/        the game. No DOM, no canvas, no clock - runs anywhere
  steering.ts   tilt in degrees -> steering, -1..1, with a captured centre
  race.ts       car, road, obstacles, lives, distance
src/          the renderer. Canvas, the join screen, the AEP session
libs/         the three AEP packages, vendored and pinned
```
