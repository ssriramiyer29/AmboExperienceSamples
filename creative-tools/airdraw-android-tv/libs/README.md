# Vendored AEP binaries

This sample builds against **AEP 0.9.0**, declared as `aep.version` in `../gradle.properties`.

| File | Component | Coordinate |
|---|---|---|
| `AEP.Core-0.9.0.jar` | Core | `com.ambokit.aep:aep-core` |
| `AEP.HostSDK-0.9.0.aar` | Host SDK | `com.ambokit.aep:aep-host-sdk` |
| `AEP.AndroidTV.Adapter-0.9.0.aar` | Android TV adapter | `com.ambokit.aep:aep-androidtv-adapter` |

Download them from the platform release:

```bash
gh release download v0.9.0 --repo ssriramiyer29/amboexperienceplatform \
  --pattern "AEP.*-0.9.0.*" --dir .
```

**Download them rather than copying them out of a local platform build.** This repository's
premise is that a sample compiles against published binaries alone, and binaries that merely
resemble a release quietly void it. That is not hypothetical: the locally built 0.9.0 and the
released one differ by three bytes each in the Host SDK and the adapter - zip container
metadata, identical contents - which is exactly how little a substituted file needs to differ
to pass a glance at a directory listing.

## Why 0.9.0

AirDraw requires `camera.hand`, and two separate things had to land before it could.

**0.8.0 made it requestable at all.** `AndroidAmboKitHost` built its own capability list in its
constructor and never read `AepExperienceDefinition.capabilities`, so every Android experience
got `camera.pose` and optionally `livevideo.person` no matter what it declared - eleven of the
thirteen capabilities were unreachable from Android. This sample is the reason that was found.

**0.9.0 made the request survive negotiation.** A capability id has two legal spellings,
`camera.hand` and `camera.hand@1`, and `CapabilityNegotiator.decide` compared the whole raw
string rather than the name. An experience spelling it the way the Companion did not happen to
advertise was told `not_advertised`, and `requestCapability` was never sent - no data, and no
error, because as far as the negotiator knew the phone simply did not offer it. On 0.8.0 this
sample's one *required* capability could therefore go unfulfilled in silence, depending on
which spelling the phone chose.

`experience.json` declares that floor as `aep.minimumVersion`.

## What else this needs

`AEP.HostSDK` uses OkHttp at runtime. A file dependency carries no POM, so nothing brings it
transitively and `app/build.gradle.kts` declares it by hand. Keep it in step when bumping:

```kotlin
implementation("com.squareup.okhttp3:okhttp:4.12.0")
```

Leaving it out produces a build that succeeds and then dies with `NoClassDefFoundError` the
moment the host opens a socket.
