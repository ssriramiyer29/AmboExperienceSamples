# Adding a sample

Every sample carries **two** declarations at its root, and they answer different questions.

| | |
|---|---|
| `sample.json` | what this **repository** needs to know: which binaries are vendored here, where the renderer-free rules live. |
| `experience.json` | what the **platform** needs to know: the experience's id, what it requires, what it merely prefers, and the oldest AEP it runs against. It is the canonical AEP experience manifest, and the same file an experience carries anywhere. |

Keep a fact in one of them, never both. `sample.json` used to list `capabilities` too, and
nothing read it — not the checks, not CI, not a build — so it was true only by luck, and it had
stopped being true: it said `camera.pose@1` where the code asked for `camera.pose`, and both
RockDodge samples agreed with each other while disagreeing with their own code. Capabilities
now live in `experience.json`, where `tools/check_manifests.py` holds them against the source in
the same directory.

Discovery, checks and CI key on these files, and nothing infers a sample's identity from its
build system.

That is deliberate. An earlier version of the checks looked for `settings.gradle.kts`, which
meant a Unity sample — having no such file — would have been **skipped in silence**. A skipped
check and a passing check look identical from the outside, so a directory that looks like a
project and carries no `sample.json` now fails rather than being ignored.

## `sample.json`

```json
{
  "name": "rockdodge-unity",
  "renderer": "unity",
  "aepVersion": "0.7.0",
  "experienceRules": "Assets/RockDodge/Runtime/Core",
  "description": "The Unity build of RockDodge. Same game, same AEP, different renderer."
}
```

| Field | |
|---|---|
| `name` | directory name |
| `renderer` | `android-tv` or `unity` |
| `aepVersion` | which AEP binaries are **vendored in this directory** |
| `experienceRules` | path to the renderer-free rules, **or `null`** |
| `description` | one line |

`experienceRules` is required but may be `null`. A sample with no renderer-free rules has to say
so on purpose — the alternative is a check that finds nothing to look at and reports success.

`aepVersion` and `experience.json`'s `aep.minimumVersion` are not the same number and are not
meant to be. One says which binaries sit in this folder; the other says the oldest release the
experience would run against at all.

## `experience.json`

The canonical AEP experience manifest. Its schema is
`tools/aep-experience-manifest-v1.schema.json`, vendored from the AEP repository beside the
binaries and for the same reason.

```json
{
  "schemaVersion": 1,
  "id": "com.ambokit.aep.rockdodge",
  "name": "RockDodge",
  "description": "Dodge falling rocks by stepping left and right. Pose is the controller.",
  "renderers": ["unity"],
  "requiresCalibration": true,
  "capabilities": {
    "required": ["camera.pose"],
    "optional": ["livevideo.person"]
  },
  "aep": { "minimumVersion": "0.7.0" }
}
```

| Field | |
|---|---|
| `id` | lower-case reverse-DNS, and **the same string the code passes to `AepExperienceDefinition`** |
| `renderers` | which renderers this build targets |
| `requiresCalibration` | mirrors `AepExperienceDefinition.requiresCalibration` |
| `capabilities.required` | without these the experience cannot run |
| `capabilities.optional` | it runs without these and is better with them |
| `aep.minimumVersion` | the oldest AEP release it runs against |

Three rules the checks enforce, each because it was broken:

- **One experience, one id.** RockDodge shipped as `com.ambokit.aep.rockdodge` on Android and
  `reference.rockdodge` on Unity, which makes one game into two in anything that counts them.
  Two manifests sharing an id must now agree on everything but `renderers`.
- **The manifest must match the code beside it.** A different id, a required capability the code
  never asks for, or a capability the code asks for that the manifest omits all fail. A manifest
  that lies is worse than none, because it is believed.
- **Capability ids are AmboKit's.** Every id is checked against the vendored catalogue, so
  `camera.hands` fails here rather than arriving at runtime as `not_advertised` — which reads
  like a missing sensor rather than a typo.

Both spellings are legal: `camera.pose` and `camera.pose@1` are the same capability, and the
checks compare by name.

## Layout by renderer

### `android-tv`

```
<sample>/
  sample.json
  gradle.properties        aep.version, must agree with sample.json
  settings.gradle.kts      standalone build, its own wrapper
  gradlew, gradlew.bat, gradle/wrapper/
  libs/
    AEP.Core-<version>.jar
    AEP.HostSDK-<version>.aar
    AEP.AndroidTV.Adapter-<version>.aar
    README.md              which transitive dependencies this version needs
  core/                    renderer-free rules
  app/                     the renderer
```

A vendored `.aar` carries **no POM**, so nothing arrives transitively with it. Whatever the Host
SDK depends on must be declared by hand in the app's `build.gradle.kts` — for 0.7.0 that is
`com.squareup.okhttp3:okhttp:4.12.0`. Leaving it out produces a build that succeeds and an app
that dies with `NoClassDefFoundError` the first time the host opens a socket. Record it in
`libs/README.md` and re-check it on every version bump.

### `unity`

```
<sample>/
  sample.json
  Packages/manifest.json   must NOT reference any AEP source package
  Assets/
    Plugins/AEP/
      AEP.Core.dll
      AEP.HostSDK.dll
      AEP.Unity.Adapter.dll
      VERSION              from the binary builder; must agree with sample.json
    <game>/Runtime/Core/   renderer-free rules
  ProjectSettings/
```

Unity managed plugins are identified by assembly name, so the DLL filenames carry no version.
The `VERSION` sidecar — written by the AEP binary builder, read out of the compiled `AEP.Core`
rather than typed in — is the only thing tying those DLLs to a release. Without it they are
unidentifiable, so the checks require it.

**A Unity project must not both compile AEP source and reference the DLLs.** That produces two
assemblies with one identity and Unity objects at length. Remove every AEP package from
`Packages/manifest.json` before installing the plugins.

## Vendored binaries are committed

They are deliberately not gitignored. A sample without its binaries cannot be built from
artifacts alone, which is the one thing this repository exists to demonstrate.

Samples pin different AEP versions over time, on purpose — that is what makes this repository a
compatibility matrix rather than a snapshot.

## What CI does

| | `android-tv` | `unity` |
|---|---|---|
| Structural checks | yes | yes |
| Built | yes | **no** |

Unity cannot be compiled on the runner: it needs an editor and a licence. CI emits a warning
naming each unbuilt Unity sample rather than passing quietly, so the gap stays visible. A Unity
build licence on a runner would close it.

## Checking locally

```bash
python3 tools/check_samples.py
python3 tools/list_samples.py --renderer android-tv
```
