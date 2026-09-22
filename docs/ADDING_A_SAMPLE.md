# Adding a sample

Every sample declares itself in a `sample.json` at its root. Discovery, checks and CI all key on
that file, and nothing infers a sample's identity from its build system.

That is deliberate. An earlier version of the checks looked for `settings.gradle.kts`, which
meant a Unity sample — having no such file — would have been **skipped in silence**. A skipped
check and a passing check look identical from the outside, so a directory that looks like a
project and carries no `sample.json` now fails rather than being ignored.

## `sample.json`

```json
{
  "name": "rockdodge-unity",
  "renderer": "unity",
  "aepVersion": "0.6.0",
  "capabilities": ["camera.pose@1", "livevideo.person@1"],
  "experienceRules": "Assets/RockDodge/Runtime/Core",
  "description": "The Unity build of RockDodge. Same game, same AEP, different renderer."
}
```

| Field | |
|---|---|
| `name` | directory name |
| `renderer` | `android-tv` or `unity` |
| `aepVersion` | which AEP this sample is built against |
| `capabilities` | what it requests, with versions |
| `experienceRules` | path to the renderer-free rules, **or `null`** |
| `description` | one line |

`experienceRules` is required but may be `null`. A sample with no renderer-free rules has to say
so on purpose — the alternative is a check that finds nothing to look at and reports success.

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
SDK depends on must be declared by hand in the app's `build.gradle.kts` — for 0.6.0 that is
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
