# core — Rock Dodge game rules

Renderer-neutral Rock Dodge rules and state.

**In:** semantic `PlayerAction`s plus elapsed time.
**Out:** an immutable `RockDodgeSnapshot`.

The renderer owns none of scoring, spawning, collision, lives, lane state or the timer. It
receives a snapshot and draws it.

Kotlin/JVM, not Android — so these rules can be exercised without an emulator, and so the
boundary is enforced by the toolchain rather than by discipline. This mirrors how AEP Core
itself is built (ADR-0001, Principle 1).

AEP Core is a `compileOnly` dependency here: the rules are written against its types but do
not ship it.
