/**
 * Phone tilt to steering, and nothing else.
 *
 * ADR-0001 rule 2: these rules are renderer-independent. Nothing here names a canvas, a DOM node
 * or a frame clock - it takes degrees in and gives a number between -1 and 1 out, and would run
 * unchanged on a television or in Unity.
 *
 * **Which axis, and why it is not simply asserted.** `motion.orientation@1` reports yaw, pitch and
 * roll. Held upright, a phone tilted left and right rotates about the axis running up through the
 * screen, which Android's SensorManager calls roll - so roll is the steering axis, and the default
 * below says so. It has never been checked against the Companion on a real phone, which is why
 * `axis` is a parameter rather than a constant and why the game draws the live numbers on screen.
 * The first session with a phone is what settles it; until then this is arithmetic, not a fact.
 *
 * The schema's `screenRotationDeg` would say whether the phone is in landscape, which would let
 * this pick the axis by itself. It is not usable: it is the capability catalogue's only numeric
 * enum, and every AEP renderer reads it as a string the wire never carries, so it silently never
 * arrives. See the note in AEP's capability_ir.py.
 */

export type SteeringAxis = "roll" | "pitch" | "yaw";

export interface SteeringConfig {
  /** Which reported angle steers. See the note above: roll is the default, not a measured fact. */
  readonly axis: SteeringAxis;
  /** How many frames of holding still establish the centre. About half a second at 30Hz. */
  readonly neutralFrames: number;
  /** Tilt at which steering is full lock, in degrees from the captured centre. */
  readonly fullLockDeg: number;
  /** Tilt below which the car goes straight. Stops a hand that is merely not perfectly level. */
  readonly deadzoneDeg: number;
  /** 0 = follow instantly, 1 = never move. Takes the jitter off without adding felt lag. */
  readonly smoothing: number;
}

export function steeringConfig(overrides: Partial<SteeringConfig> = {}): SteeringConfig {
  return {
    axis: overrides.axis ?? "roll",
    neutralFrames: overrides.neutralFrames ?? 15,
    fullLockDeg: overrides.fullLockDeg ?? 35,
    deadzoneDeg: overrides.deadzoneDeg ?? 3,
    smoothing: overrides.smoothing ?? 0.35,
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
      this.#samples.push(degrees);
      if (this.#samples.length < this.#config.neutralFrames) return;
      // The median, not the mean: one wild sample while the player is picking the phone up
      // would drag a mean off centre and leave the car pulling to one side all game.
      const sorted = [...this.#samples].sort((a, b) => a - b);
      this.#neutralDeg = sorted[Math.floor(sorted.length / 2)] ?? 0;
      return;
    }

    const offset = shortestAngle(degrees - this.#neutralDeg);
    const beyondDeadzone = Math.abs(offset) <= this.#config.deadzoneDeg
      ? 0
      : Math.sign(offset) * (Math.abs(offset) - this.#config.deadzoneDeg);
    const span = Math.max(1, this.#config.fullLockDeg - this.#config.deadzoneDeg);
    const target = clamp(beyondDeadzone / span, -1, 1);
    this.#value += (target - this.#value) * (1 - this.#config.smoothing);
  }

  /** Forget the centre. A player who has changed how they are holding the phone re-centres. */
  recentre(): void {
    this.#samples = [];
    this.#neutralDeg = null;
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
