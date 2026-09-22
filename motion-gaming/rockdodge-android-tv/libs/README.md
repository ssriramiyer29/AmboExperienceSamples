# Vendored AEP binaries

This sample builds against **AEP 0.6.0**, declared as `aep.version` in `../gradle.properties`.
These three files belong here, committed to the repository:

| File | Component | Coordinate (internal use only) |
|---|---|---|
| `AEP.Core-0.6.0.jar` | Core | `com.ambokit.aep:aep-core` |
| `AEP.HostSDK-0.6.0.aar` | Host SDK | `com.ambokit.aep:aep-host-sdk` |
| `AEP.AndroidTV.Adapter-0.6.0.aar` | Android TV adapter | `com.ambokit.aep:aep-androidtv-adapter` |

Core ships as a **JAR**, not an AAR: nothing in it touches Android, and an AAR would drag the
Android toolchain into an artifact that does not need it.

Download them from the AEP release and drop them in this folder:

```bash
gh release download v0.6.0 --repo ssriramiyer29/amboexperienceplatform --dir .
```

The Maven coordinates above are listed for reference. They resolve from GitHub Packages, which
is private — this repository is public, so samples vendor files instead of requiring every
reader to hold a token.

## What else 0.6.0 needs

A vendored `.aar` referenced as a file dependency carries **no POM**, so nothing comes with it
transitively. Whatever the Host SDK depends on has to be declared by hand in `../app/build.gradle.kts`:

| Dependency | Needed by | Version |
|---|---|---|
| `com.squareup.okhttp3:okhttp` | `AEP.HostSDK` — gateway REST and WebSocket transport | 4.12.0 |

Omitting it produces a build that succeeds and an app that dies with `NoClassDefFoundError`
the first time the host opens a socket.

**When bumping `aep.version`, check this table against the new Host SDK's dependencies.** It is
the one thing in this sample that can drift silently.

The embedded AmboGateway is inside `AEP.HostSDK` and is not a separate artifact. If you are
looking for a fourth file, there isn't one.
