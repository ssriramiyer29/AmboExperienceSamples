# Locked contract

Unity must preserve the renderer-neutral core v1.0 behavior:
- 30 s round, 3 lives.
- continuous free horizontal movement.
- random X rock spawn.
- spawn interval 720-1350 ms.
- rock speeds 0.22-0.34 normalized screen heights/s.
- identical collision envelope, scoring, jump/crouch windows.
- latest-state pose consumption; stale state is not replayed.
- player-right => screen-right.
