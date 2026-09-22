# RockDodge — Android TV

A motion game for Android TV: dodge falling rocks by physically stepping left and right in
front of your phone's camera. Your pose is the controller.

Built entirely on **published AEP binaries** (see [`libs/`](libs/)) — no platform source.

| | |
|---|---|
| **AEP version** | 0.7.0 |
| **Renderer** | Android TV |
| **Capabilities** | `camera.pose@1` required, `livevideo.person@1` optional |
| **minSdk** | 24 |

## Running it

1. Install the Ambo Companion app on a phone, on the same network as the TV.
2. Build and install:
   ```bash
   ./gradlew :app:installDebug
   ```
3. Launch on the TV. A QR code appears.
4. Scan it with Companion, tap join, and allow camera pose access.
5. Stand back far enough for your whole body to be in frame and play.

## What this sample demonstrates

**Graceful degradation.** `livevideo.person` renders a live cutout of the player over the
game, and is genuinely optional: decline it, or revoke it mid-session, and the game keeps
running on pose alone. Tested, not assumed.

**Capability discovery, not assumption.** The app asks what the connected participant
advertises rather than compiling in a fixed list, so a Companion offering capabilities this
build has never heard of does not break it.

**Start-up cost you can measure.** `session.joinTimeline` splits the path to first pose into
phases, and the app logs them:

```bash
adb logcat -s AEP:I
```

```
join timings: qrReady=422ms playerJoin=9127ms grant=1476ms firstPose=686ms total=11711ms
```

`qrReady` and `firstPose` are platform cost; `playerJoin` and `grant` are a person picking up
a phone. On a real Mi TV that reads as 1.1 s of platform time out of 11.7 s total — which is
the kind of claim worth being able to check rather than assert.

## Structure

| Module | What it is |
|---|---|
| `core/` | Game rules and state. Kotlin/JVM: no renderer, no Android, no emulator needed to test |
| `app/` | The Android TV renderer — drawing, input surface, QR display |

The split is not ceremony. Game rules that cannot reference a renderer are rules you can run
anywhere, and it is the same boundary AEP itself is built on.
