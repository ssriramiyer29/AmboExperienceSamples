# app — Android TV renderer

The Android TV rendering implementation for the renderer-neutral Rock Dodge rules in
[`../core`](../core).

Owns: drawing, the frame surface, the join QR display, and the pose skeleton visualisation.
Players move continuously rather than between fixed lanes; rock placement, timing and speed
are random.

**Must not contain** AmboKit protocol or gateway logic — that is `AEP.HostSDK`'s job — or any
Rock Dodge gameplay rule, which belongs in `core`. If a change to this module needs a scoring
decision, it is in the wrong module.

Version numbers for the sample live in [`../README.md`](../README.md) and
[`../gradle.properties`](../gradle.properties), not here, so there is one place to bump.
