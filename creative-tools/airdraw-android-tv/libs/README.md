# Vendored AEP binaries

This sample builds against **AEP 0.8.0**, declared as `aep.version` in `../gradle.properties`.

| File | Component | Coordinate |
|---|---|---|
| `AEP.Core-0.8.0.jar` | Core | `com.ambokit.aep:aep-core` |
| `AEP.HostSDK-0.8.0.aar` | Host SDK | `com.ambokit.aep:aep-host-sdk` |
| `AEP.AndroidTV.Adapter-0.8.0.aar` | Android TV adapter | `com.ambokit.aep:aep-androidtv-adapter` |

Download them from the platform release:

```bash
gh release download v0.8.0 --repo ssriramiyer29/amboexperienceplatform --dir .
```

## Why 0.8.0 and not something earlier

AirDraw needs `camera.hand@1` and `input.touch@1`. Before 0.8.0 an Android experience could not
request either: `AndroidAmboKitHost` built its own capability list in its constructor and never
read `AepExperienceDefinition.capabilities`, so every Android experience got `camera.pose` and
optionally `livevideo.person` no matter what it declared.

This sample is the reason that was found.

## What else 0.8.0 needs

`AEP.HostSDK` uses OkHttp at runtime. A file dependency carries no POM, so nothing brings it
transitively and `app/build.gradle.kts` declares it by hand. Keep it in step when bumping:

```kotlin
implementation("com.squareup.okhttp3:okhttp:4.12.0")
```

Leaving it out produces a build that succeeds and then dies with `NoClassDefFoundError` the
moment the host opens a socket.
