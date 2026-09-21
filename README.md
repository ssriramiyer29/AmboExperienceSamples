# AmboExperience Samples

Working samples built on the **Ambo Experience Platform (AEP)**, the renderer-independent
platform for building experiences on AmboKit device capabilities.

Every sample here builds from **published AEP binaries only** — never from platform source.
That is the entire reason this repository is separate from the platform repository. While a
sample shares a build with the platform it can quietly depend on the platform's source, and
"an external developer can build this using only published artifacts" cannot be honestly
certified from inside the repository that holds the source. Separating them makes the binary
boundary a property of the filesystem rather than a promise.

## Layout

Samples are grouped by domain, with the renderer as a suffix:

```
motion-gaming/
  rockdodge-android-tv/
```

**Domains appear when a sample fills them.** There is no scaffold of empty folders waiting
to be populated — an empty domain advertises a sample that does not exist.

## Each sample carries its own binaries

A sample vendors the AEP binaries it was built against, in its own `libs/`, rather than every
sample sharing one set. Samples built at different times pin different AEP versions, and
keeping those pins visible turns this repository into a **living compatibility matrix**: real
version pairs that version negotiation can be tested against, rather than a snapshot that
claims everything works with everything.

Each sample's `gradle.properties` declares its `aep.version`, and its `libs/README.md` records
what else that version needs.

## Samples

| Sample | Domain | Renderer | AEP | Capabilities |
|---|---|---|---|---|
| [`rockdodge-android-tv`](motion-gaming/rockdodge-android-tv/) | motion-gaming | Android TV | 0.5.0 | `camera.pose@1`, `livevideo.person@1` |

RockDodge is a **developer sample**, not a product.

## What you need to run one

An Android TV device or emulator, the [Ambo Companion](https://github.com/ssriramiyer29/ambokit)
app on a phone, and both on the same network. Each sample's README has the specifics.
