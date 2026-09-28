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

## One number on screen is not decoration

The bottom-right corner shows the live tilt angle, the captured centre and the steering value.
**Which angle steers has never been checked against a real Companion.** Held upright, a phone
tilted left and right rotates about the axis Android's `SensorManager` calls roll, so roll is what
`rules/steering.ts` reads — reasoned from a convention, not measured from a device.

The schema's `screenRotationDeg` would remove the doubt by saying whether the phone is in
landscape. It is unusable: it is the capability catalogue's only numeric enum, and every AEP
renderer reads it as a string the wire never carries, so it silently never arrives.

So the first session with a phone is the measurement. Tilt left, and watch whether `roll` moves
and which way `steer` goes. When that is settled, `steeringConfig({ axis })` records it and the
diagnostics come off.

## Layout

```
rules/        the game. No DOM, no canvas, no clock - runs anywhere
  steering.ts   tilt in degrees -> steering, -1..1, with a captured centre
  race.ts       car, road, obstacles, lives, distance
src/          the renderer. Canvas, the join screen, the AEP session
libs/         the three AEP packages, vendored and pinned
```
