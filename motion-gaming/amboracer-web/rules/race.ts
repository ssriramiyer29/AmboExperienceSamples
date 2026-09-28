import { clamp } from "./steering.js";

/**
 * AmboRacer's rules. No canvas, no DOM, no frame clock - ADR-0001 rule 2.
 *
 * Everything here is a pure function of time and one steering number, so the same rules drive a
 * browser canvas today and would drive a television or Unity unchanged. `tick` returns an
 * immutable snapshot; a renderer presents it and owns nothing.
 *
 * The road is normalised: x runs 0 (left verge) to 1 (right verge), y runs 0 (horizon) to 1 (at
 * the player). A renderer decides what that looks like in pixels, and a wider window is a wider
 * road rather than a different game.
 */

export interface Obstacle {
  readonly id: number;
  /** Lane centre, 0..1 across the road. */
  readonly x: number;
  /** 0 at the horizon, 1 at the player. */
  readonly y: number;
  readonly kind: "cone" | "barrier";
}

export interface RaceSnapshot {
  readonly phase: "waiting" | "racing" | "crashed";
  /** The car, 0..1 across the road. */
  readonly carX: number;
  /** Metres, for the score. */
  readonly distance: number;
  /** Road-lengths per second. */
  readonly speed: number;
  readonly lives: number;
  readonly obstacles: readonly Obstacle[];
  /** Counts up while the car is unhittable after a crash, 0 when it is not. */
  readonly invulnerableMs: number;
  /** Milliseconds since the race began. */
  readonly elapsedMs: number;
}

export interface RaceConfig {
  readonly lives: number;
  /** Road-lengths per second at the start. */
  readonly startSpeed: number;
  readonly maxSpeed: number;
  /** How long, in seconds, to reach max speed. */
  readonly rampSeconds: number;
  /** How fast full lock moves the car across the road, in road-widths per second. */
  readonly steerRate: number;
  /** Half-width of the car and of an obstacle, for the overlap test. */
  readonly carHalfWidth: number;
  readonly obstacleHalfWidth: number;
  /** Seconds between spawns at the start speed; scales down as the car speeds up. */
  readonly spawnSeconds: number;
  readonly invulnerableMs: number;
}

export function raceConfig(overrides: Partial<RaceConfig> = {}): RaceConfig {
  return {
    lives: overrides.lives ?? 3,
    startSpeed: overrides.startSpeed ?? 0.45,
    maxSpeed: overrides.maxSpeed ?? 1.25,
    rampSeconds: overrides.rampSeconds ?? 75,
    steerRate: overrides.steerRate ?? 0.95,
    carHalfWidth: overrides.carHalfWidth ?? 0.055,
    obstacleHalfWidth: overrides.obstacleHalfWidth ?? 0.06,
    spawnSeconds: overrides.spawnSeconds ?? 1.15,
    invulnerableMs: overrides.invulnerableMs ?? 1200,
  };
}

/** Deterministic, so a recorded session replays identically and a test is not a coin toss. */
export function seededRandom(seed: number): () => number {
  let state = seed >>> 0 || 1;
  return () => {
    state ^= state << 13; state >>>= 0;
    state ^= state >> 17;
    state ^= state << 5; state >>>= 0;
    return state / 0x100000000;
  };
}

export class Race {
  readonly #config: RaceConfig;
  readonly #random: () => number;

  #phase: RaceSnapshot["phase"] = "waiting";
  #carX = 0.5;
  #distance = 0;
  #lives: number;
  #obstacles: Obstacle[] = [];
  #nextId = 1;
  #sinceSpawn = 0;
  #elapsedMs = 0;
  #invulnerableMs = 0;

  constructor(config: RaceConfig = raceConfig(), random: () => number = seededRandom(20260928)) {
    this.#config = config;
    this.#random = random;
    this.#lives = config.lives;
  }

  start(): void {
    this.#phase = "racing";
    this.#carX = 0.5;
    this.#distance = 0;
    this.#lives = this.#config.lives;
    this.#obstacles = [];
    this.#sinceSpawn = 0;
    this.#elapsedMs = 0;
    this.#invulnerableMs = 0;
  }

  get snapshot(): RaceSnapshot {
    return {
      phase: this.#phase,
      carX: this.#carX,
      distance: this.#distance,
      speed: this.#speed,
      lives: this.#lives,
      obstacles: [...this.#obstacles],
      invulnerableMs: this.#invulnerableMs,
      elapsedMs: this.#elapsedMs,
    };
  }

  get #speed(): number {
    const through = clamp(this.#elapsedMs / 1000 / this.#config.rampSeconds, 0, 1);
    return this.#config.startSpeed + (this.#config.maxSpeed - this.#config.startSpeed) * through;
  }

  /**
   * One step. `steering` is -1..1; `deltaMs` is however long the renderer's frame took.
   *
   * Clamped at 100ms for the same reason the AEP adapters clamp theirs: one slow frame - a tab
   * returning, a garbage collection - must not teleport the car through an obstacle it never had
   * a chance to steer around.
   */
  tick(deltaMs: number, steering: number): RaceSnapshot {
    if (this.#phase !== "racing") return this.snapshot;
    const dt = clamp(deltaMs, 0, 100) / 1000;
    this.#elapsedMs += dt * 1000;
    if (this.#invulnerableMs > 0) this.#invulnerableMs = Math.max(0, this.#invulnerableMs - dt * 1000);

    const speed = this.#speed;
    this.#distance += speed * dt * 100;
    this.#carX = clamp(this.#carX + clamp(steering, -1, 1) * this.#config.steerRate * dt, 0, 1);

    // Spawning quickens with speed, so the gaps stay about the same length of road rather than
    // the same length of time - otherwise the game gets easier as it gets faster.
    this.#sinceSpawn += dt * speed / this.#config.startSpeed;
    if (this.#sinceSpawn >= this.#config.spawnSeconds) {
      this.#sinceSpawn = 0;
      this.#obstacles.push({
        id: this.#nextId++,
        x: 0.12 + this.#random() * 0.76,
        y: 0,
        kind: this.#random() < 0.25 ? "barrier" : "cone",
      });
    }

    const moved: Obstacle[] = [];
    const reach = this.#config.carHalfWidth + this.#config.obstacleHalfWidth;
    for (const obstacle of this.#obstacles) {
      const y = obstacle.y + speed * dt;
      if (y > 1.08) continue;   // past the player, gone
      // The car sits at y = 1. An obstacle is hit while it overlaps that band, not at one
      // instant: at full speed an obstacle crosses the car's depth in under two frames, and a
      // test on a single y would let it pass through.
      const overlapping = y > 0.93 && y < 1.05
        && Math.abs(obstacle.x - this.#carX) < reach;
      if (overlapping && this.#invulnerableMs <= 0) {
        this.#lives -= 1;
        this.#invulnerableMs = this.#config.invulnerableMs;
        if (this.#lives <= 0) this.#phase = "crashed";
        continue;  // the one you hit is removed, so it cannot be hit twice
      }
      moved.push({ ...obstacle, y });
    }
    this.#obstacles = moved;
    return this.snapshot;
  }
}
