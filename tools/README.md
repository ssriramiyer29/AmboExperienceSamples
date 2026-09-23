# Tools

`check_samples.py` and `list_samples.py` belong to this repository and are written here.

The other three files are **vendored from the AEP platform repository**, beside the binaries
and for the same reason: that repository is private, and a public CI run cannot check it out.

| File | |
|---|---|
| `check_manifests.py` | the AEP experience-manifest validator, byte-identical to `tools/verify/check_manifests.py` upstream |
| `aep-experience-manifest-v1.schema.json` | the canonical manifest schema, byte-identical to `schemas/aep-experience-manifest-v1.schema.json` upstream |
| `capability-catalogue.json` | which AmboKit capabilities exist, at which majors — generated upstream by `tools/verify/write_capability_catalogue.py`, never written by hand |

## Why vendored rather than rewritten

`check_samples.py` already carries one deliberate reimplementation — the ADR-0001 rule about
renderer-independent experience rules — and says so in its own docstring. That one is small,
stable, and expressible in a regular expression.

A manifest validator is none of those things, and the defect it exists to catch is *two
implementations of the same rule disagreeing with each other*. AEP shipped four different
answers to "are these two capability ids the same thing", which is how RockDodge came to ship
under two ids and AirDraw came to declare a capability in a spelling that, against the other
kind of catalogue, would never have been requested at all. Writing a second validator here to
check that the first one's rules are followed would be the same mistake with a different name.

So: vendored, byte-identical, and replaced wholesale when it changes upstream.

## Refreshing

From an AEP checkout beside this one:

```bash
cp ../aep/tools/verify/check_manifests.py                      tools/check_manifests.py
cp ../aep/schemas/aep-experience-manifest-v1.schema.json       tools/aep-experience-manifest-v1.schema.json
python3 ../aep/tools/verify/write_capability_catalogue.py      tools/capability-catalogue.json
python3 tools/check_manifests.py --root . \
  --schema tools/aep-experience-manifest-v1.schema.json \
  --catalogue tools/capability-catalogue.json
```

## The cost, stated plainly

Nothing signals when these fall behind upstream — the same gap the vendored binaries carry,
and the same answer: refreshing them is a step in the release procedure, not something to
assume happened.

One thing is weaker here than upstream. AEP runs this validator against eighteen fixtures,
twelve of which must be rejected for their own stated reasons; those fixtures stay there. Here
it runs against three real manifests and nothing that must fail, so **this copy is exercised
but not tested**. If you change it here rather than upstream, you are changing an untested
copy of a tested file — don't.

## Provenance

| | |
|---|---|
| Source | the AEP platform repository |
| AEP version | 0.9.0 |
| AmboKit catalogue | 1.1.0-rc.2, commit `fe8bb89` |
| Copied | 2026-09-23 |

| File | SHA-256 |
|---|---|
| `check_manifests.py` | `3d1dfd2d6c039e8225368f44f43cdefd6e943ab438634b7e2aec81f5254ea7c4` |
| `aep-experience-manifest-v1.schema.json` | `2a4233c747785384b0e86aa680780799a6d25563f3a0548ee82ce4d873dd2ba7` |
| `capability-catalogue.json` | `4befa00da21d098814eac8a87996e88528d3835b3a9328e92e8301525ce40af0` |
