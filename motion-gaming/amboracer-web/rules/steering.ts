/**
 * Phone tilt to steering, and nothing else.
 *
 * ADR-0001 rule 2: these rules are renderer-independent. Nothing here names a canvas, a DOM node
 * or a frame clock - it takes degrees in and gives a number between -1 and 1 out, and would run
 * unchanged on a television or in Unity.
 *
 * **Which axis, measured on a real phone on 2026-09-28: yaw.**
 *
 * This file used to default to roll, reasoned like so: a phone held upright and tilted left and
 * right rotates about the axis running up through the screen, which Android's SensorManager calls
 * roll. The convention was right and the model of the player was wrong - nobody holds a phone
 * upright to steer. They hold it flat and turn it like a wheel, and turning a flat phone is
 * rotation about the vertical axis, which is yaw. Held flat, roll barely changes and is noisy,
 * which is exactly how it felt: the car pinned to one side and followed only sometimes.
 *
 * The lesson is cheap to state and was not cheap to find: the axis was never the uncertain part.
 * How a person holds the thing was.
 *
 * `axis` stays a parameter because a different game may want a different grip - a flight sample
 * would read pitch - and the sample keeps its live switch so the next device can be checked in
 * one tilt rather than one rebuild.
 *
 * The schema's `screenRotationDeg` would have said whether the phone was in landscape and is
 * unusable: it is the capability catalogue's only numeric enum, and every AEP renderer reads it
 * as a string the wire never carries, so it silently never arrives. See AEP's capability_ir.py.
 */

export type SteeringAxis = "roll" | "pitch" | "yaw";

/**
 * Which way a positive offset steers, per axis. Measured on a real phone on 2026-09-30.
 *
 * Not a detail that could be reasoned out. Android's euler angles do not all increase in the same
 * rotational sense - turning a flat phone anticlockwise raises yaw, while the equivalent movement
 * about the other two axes lowers theirs - so a single `Math.sign(offset)` steers correctly on one
 * axis and backwards on the others. Yaw was measured on 2026-09-28 and is the default; roll and
 * pitch were measured on 2026-09-30 by switching to them mid-race and watching the car mirror the
 * tilt.
 *
 * Kept as a table rather than folded into `observe`, because each entry is an observation about a
 * device convention and the next person should be able to see which ones were checked.
 */
const AXIS_DIRECTION: Record<SteeringAxis, 1 | -1> = { yaw: 1, roll: -1, pitch: -1 };

export interface SteeringConfig {
  /** Which reported angle steers. See the note above: yaw, measured rather than reasoned. */
  readonly axis: SteeringAxis;
  /**
   * Which way a positive offset turns the car, +1 or -1.
   *
   * Defaults per axis from AXIS_DIRECTION. A game whose controller is held differently - upside
   * down in a cradle, say - flips this rather than editing the table, which records what the
   * device reports and not how anyone is holding it.
   */
  readonly direction: 1 | -1;
  /** How many frames of holding still establish the centre. About half a second at 30Hz. */
  readonly neutralFrames: number;
  /**
   * How far the phone may move between two frames and still count as held still, in degrees.
   *
   * The screen says "hold the phone still" and this is what makes that true. Without it the
   * centre was captured from the first frames that arrived, which is precisely when the player is
   * lowering the phone from scanning a QR code to holding it like a wheel - so the centre was a
   * point somewhere through that movement, and the car pulled hard to one side from the start.
   * It looked like the wrong axis, because re-centring was the thing that fixed it.
   */
  readonly stillnessDeg: number;
  /** Tilt at which steering is full lock, in degrees from the captured centre. */
  readonly fullLockDeg: number;
  /** Tilt below which the car goes straight. Stops a hand that is merely not perfectly level. */
  readonly deadzoneDeg: number;
  /** 0 = follow instantly, 1 = never move. Takes the jitter off without adding felt lag. */
  readonly smoothing: number;
  /**
   * How fast the captured centre follows the phone while the player is going straight, per frame.
   *
   * Needed because yaw is the steering axis and `motion.orientation` is requested with
   * `reference: "game"` - no magnetic north, which the catalogue recommends and which means yaw
   * is integrated rather than absolute. It drifts. Over a two-minute race a fixed centre slowly
   * becomes wrong and the car develops a pull the player has to fight.
   *
   * Applied at two rates, and the second exists because the first version was a ratchet. It
   * adapted only inside the deadzone, so the moment drift pushed the offset past three degrees
   * adaptation stopped and nothing could pull it back. The car developed a permanent lean and the
   * only cure was a manual re-centre, every time. Reported from play as having to press the axis
   * key again, which sounded like the axis and was not.
   *
   * Inside the deadzone it follows briskly: a player going straight is quietly redefining
   * straight. Outside it follows about seven times slower, which is negligible across a turn of a
   * few seconds and still absorbs a minute of drift. No state is unrecoverable.
   */
  readonly centreFollow: number;
  /** The slow rate, used while the player is deliberately turning. See centreFollow. */
  readonly centreFollowTurning: number;
}

export function steeringConfig(overrides: Partial<SteeringConfig> = {}): SteeringConfig {
  const axis = overrides.axis ?? "yaw";
  return {
    axis,
    direction: overrides.direction ?? AXIS_DIRECTION[axis],
    neutralFrames: overrides.neutralFrames ?? 15,
    stillnessDeg: overrides.stillnessDeg ?? 2.0,
    fullLockDeg: overrides.fullLockDeg ?? 35,
    deadzoneDeg: overrides.deadzoneDeg ?? 3,
    smoothing: overrides.smoothing ?? 0.35,
    centreFollow: overrides.centreFollow ?? 0.004,
    centreFollowTurning: overrides.centreFollowTurning ?? 0.0006,
  };
}

export interface OrientationReading {
  readonly yawDeg: number;
  readonly pitchDeg: number;
  readonly rollDeg: number;
}

export class Steering {
  readonly #config: SteeringConfig;
  #samples: number[] = [];
  #neutralDeg: number | null = null;
  /** What the centre was when first captured, kept only so drift can be reported. */
  #capturedDeg: number | null = null;
  #value = 0;
  #rawDeg = 0;

  constructor(config: SteeringConfig = steeringConfig()) {
    this.#config = config;
  }

  /** -1 hard left, 0 straight, +1 hard right. Zero until the centre has been captured. */
  get value(): number { return this.#value; }
  /** The angle this is reading, before any centring. On screen, so the axis can be checked. */
  get rawDeg(): number { return this.#rawDeg; }
  /** The captured centre, or null while the player is still being asked to hold still. */
  get neutralDeg(): number | null { return this.#neutralDeg; }
  /** How far the centre has moved since it was captured. Drift, made visible. */
  get centreDriftDeg(): number {
    return this.#capturedDeg === null || this.#neutralDeg === null
      ? 0
      : shortestAngle(this.#neutralDeg - this.#capturedDeg);
  }
  get isCentred(): boolean { return this.#neutralDeg !== null; }
  /** How far through capturing the centre, 0..1. */
  get centringProgress(): number {
    return this.#neutralDeg !== null ? 1 : this.#samples.length / this.#config.neutralFrames;
  }

  observe(reading: OrientationReading): void {
    const degrees = this.#config.axis === "roll" ? reading.rollDeg
      : this.#config.axis === "pitch" ? reading.pitchDeg
        : reading.yawDeg;
    this.#rawDeg = degrees;

    if (this.#neutralDeg === null) {
      // A frame that moved too far from the last one is not the player holding still; it is the
      // player still arriving. Start the count again rather than averaging the journey in.
      const previous = this.#samples[this.#samples.length - 1];
      if (previous !== undefined
        && Math.abs(shortestAngle(degrees - previous)) > this.#config.stillnessDeg) {
        this.#samples = [degrees];
        return;
      }
      this.#samples.push(degrees);
      if (this.#samples.length < this.#config.neutralFrames) return;
      // The median, not the mean: one wild sample while the player is picking the phone up
      // would drag a mean off centre and leave the car pulling to one side all game.
      const sorted = [...this.#samples].sort((a, b) => a - b);
      this.#neutralDeg = sorted[Math.floor(sorted.length / 2)] ?? 0;
      this.#capturedDeg = this.#neutralDeg;
      return;
    }

    const offset = shortestAngle(degrees - this.#neutralDeg);

    // Going straight redefines straight briskly; turning redefines it very slowly. Always one or
    // the other, so there is no state the compensation cannot climb back out of.
    const follow = Math.abs(offset) <= this.#config.deadzoneDeg
      ? this.#config.centreFollow
      : this.#config.centreFollowTurning;
    this.#neutralDeg = shortestAngle(this.#neutralDeg + offset * follow);

    const beyondDeadzone = Math.abs(offset) <= this.#config.deadzoneDeg
      ? 0
      : Math.sign(offset) * (Math.abs(offset) - this.#config.deadzoneDeg);
    const span = Math.max(1, this.#config.fullLockDeg - this.#config.deadzoneDeg);
    // The sign is applied here and not to `offset`, so the centre-following above keeps tracking
    // the angle as the device actually reports it. Only the steering output is mirrored.
    const target = clamp(beyondDeadzone / span, -1, 1) * this.#config.direction;
    this.#value += (target - this.#value) * (1 - this.#config.smoothing);
  }

  /** Forget the centre. A player who has changed how they are holding the phone re-centres. */
  recentre(): void {
    this.#samples = [];
    this.#neutralDeg = null;
    this.#capturedDeg = null;
    this.#value = 0;
  }
}

/**
 * The difference between two angles, taken the short way round.
 *
 * Without this a centre captured near 180 and a reading just over it differ by 359 degrees rather
 * than one, and the car slams to full lock as the player's hand passes the wrap point.
 */
export function shortestAngle(degrees: number): number {
  let wrapped = (degrees + 180) % 360;
  if (wrapped < 0) wrapped += 360;
  return wrapped - 180;
}

export function clamp(value: number, low: number, high: number): number {
  return Math.min(high, Math.max(low, value));
}
