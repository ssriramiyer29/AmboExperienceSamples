# Working in this repository

Public samples for the Ambo Experience Platform. This repository makes **one promise**: every
sample builds from published AEP binaries alone. Everything here exists to keep that true.

`README.md` says what the samples are. `docs/ADDING_A_SAMPLE.md` says how to add one. This file
says how to work on it.

## Ask before starting any work product

1. **Is this redundant work?**
2. **Can this be done more efficiently?**
3. **Can I reuse something already done?**
4. **Does this violate a locked decision? If yes, is that truly worth it?**

Question 1 earned its place here: `sample.json` carried a `capabilities` field that nothing read —
not the checks, not CI, not a build — so it was true only by luck, and had stopped being true.
Adding `experience.json` beside it would have made two declarations of one fact. The old field
was removed instead.

## Authorship

**Author and committer are both `Sridharan Sriram <contact.s.sriram@gmail.com>`.** Never add
`Co-Authored-By: Claude` or any Claude attribution to a commit or PR. If tooling asks, decline.

## This repository is public

Anything committed here is visible to everyone. No keystores, no tokens, no internal URLs, no
unreleased platform source. Samples vendor **published binaries**, never AEP source.

## Every check is negative-tested

A check that has only ever passed is untested. Break the thing it guards, confirm it fails,
restore. Two holes in `check_samples.py` were found exactly this way — it asserted a vendored
binary was *present* without checking it was *tracked*, so a sample built on one machine only.

## Verification

```bash
python3 tools/check_samples.py
python3 tools/check_manifests.py --root . \
  --schema tools/aep-experience-manifest-v1.schema.json \
  --catalogue tools/capability-catalogue.json
python3 tools/list_samples.py --renderer android-tv
```

## Two declarations, and they answer different questions

| | |
|---|---|
| `sample.json` | what this **repository** needs — which binaries are vendored here, where the renderer-free rules live |
| `experience.json` | what the **platform** needs — id, required and optional capabilities, calibration, minimum AEP |

Keep a fact in one of them, never both. `aepVersion` and `aep.minimumVersion` are deliberately
different numbers: one is which binaries sit in this folder, the other is the oldest release the
experience would run against at all.

**One experience, one id.** RockDodge shipped as `com.ambokit.aep.rockdodge` on Android and
`reference.rockdodge` on Unity — one game, two rows in anything that counts them. Manifests
sharing an id must agree on everything but `renderers`, and `check_manifests.py` enforces it.

## The vendored tools are copies, not source

`tools/check_manifests.py`, `tools/aep-experience-manifest-v1.schema.json` and
`tools/capability-catalogue.json` come from the AEP repository, byte-identical. **Do not edit
them here** — fix them upstream and re-copy. `tools/README.md` has the provenance and the refresh
commands.

They are vendored rather than reimplemented on purpose: the defect a manifest validator exists to
catch is two implementations of one rule disagreeing, and AEP once shipped four different answers
to "are these two capability ids the same thing". `check_samples.py` carries one deliberate
reimplementation — the ADR-0001 rule about renderer-free experience rules — and says so in its own
docstring, because that one is small, stable and a regular expression.

Their fixtures stay upstream, so **this copy is exercised here but tested there**.

## Samples declare themselves

Discovery keys on `sample.json`, not on build files. An earlier version looked for
`settings.gradle.kts`, which meant a Unity sample — having no such file — would have been
**skipped in silence**. A skipped check and a passing check look identical from the outside, so a
directory that looks like a project and carries no `sample.json` now fails rather than being
ignored.

## What hardware has taught, at cost

None of these was catchable by any check in this repository until one was written for it:

- **AirDraw could not open a session.** The Gateway runs in-process on loopback and the manifest
  permitted no cleartext. `check_samples.py` now asserts a sample can reach its own Gateway.
- **AirDraw hung itself at startup.** 409,600 `setPixel` calls on the UI thread building a QR
  bitmap, longer than the five seconds before an ANR. The only evidence was CPU percentages in an
  ANR trace.
- **Diagnostics that sample instead of counting cannot measure dropout at all.** Count everything.
- **The reconnect path left no trace**, so a device pass that failed had nothing to diagnose from
  and one that passed produced no numbers. RockDodge now logs connection and participant
  transitions with the gap since the previous one and whether calibration survived — which is how
  a 23-second disconnect-detection latency was measured.

## Longer-form context

The AMBO project on claude.ai holds the running state: `claude/aep-working-state.md`, and
`claude/disconnect-detection-latency.md` for the device-pass findings.
