import type { RaceSnapshot } from "../rules/race.js";

/**
 * Draws a race snapshot. Owns no rules and decides nothing - hand it the same snapshot twice and
 * it draws the same frame twice.
 *
 * The road is drawn in false perspective: the rules work in a flat 0..1 square and this maps y to
 * a horizon so that far things are small and central. That mapping lives here rather than in the
 * rules because it is a look, not a rule - a top-down renderer of the same game would skip it.
 */

const SKY = "#0b1d2e";
const ROAD = "#20303d";
const VERGE = "#3ad6a4";
const LINE = "#e8f4ff";
const CAR = "#ffd84d";
const CONE = "#ff7a3d";
const BARRIER = "#ff4968";

/** How wide the road is at the horizon, as a fraction of its width at the player. */
const HORIZON_SCALE = 0.16;
const HORIZON_Y = 0.30;

export interface Projected { readonly x: number; readonly y: number; readonly scale: number; }

/** Road space (0..1, 0..1) to canvas pixels. Exported because the tests check the mapping. */
export function project(
  x: number, y: number, width: number, height: number,
): Projected {
  // y is eased so the near half of the road takes most of the screen, which is what makes an
  // approaching obstacle appear to accelerate towards the player.
  const depth = Math.pow(Math.max(0, Math.min(1, y)), 1.9);
  const scale = HORIZON_SCALE + (1 - HORIZON_SCALE) * depth;
  const roadTop = height * HORIZON_Y;
  return {
    x: width / 2 + (x - 0.5) * width * 0.86 * scale,
    y: roadTop + (height - roadTop) * depth,
    scale,
  };
}

export class RaceRenderer {
  readonly #context: CanvasRenderingContext2D;

  constructor(context: CanvasRenderingContext2D) {
    this.#context = context;
  }

  draw(snapshot: RaceSnapshot, width: number, height: number): void {
    const g = this.#context;
    g.save();
    g.fillStyle = SKY;
    g.fillRect(0, 0, width, height);

    this.#drawRoad(width, height, snapshot.distance);

    for (const obstacle of [...snapshot.obstacles].sort((a, b) => a.y - b.y)) {
      const at = project(obstacle.x, obstacle.y, width, height);
      const size = Math.max(4, width * 0.075 * at.scale);
      g.fillStyle = obstacle.kind === "barrier" ? BARRIER : CONE;
      if (obstacle.kind === "barrier") {
        g.fillRect(at.x - size * 1.5, at.y - size * 0.5, size * 3, size * 0.7);
      } else {
        g.beginPath();
        g.moveTo(at.x, at.y - size);
        g.lineTo(at.x + size * 0.7, at.y);
        g.lineTo(at.x - size * 0.7, at.y);
        g.closePath();
        g.fill();
      }
    }

    // The car blinks while it cannot be hit, so a player who has just been hit can see why they
    // are driving through the next cone unharmed.
    const blinking = snapshot.invulnerableMs > 0
      && Math.floor(snapshot.invulnerableMs / 120) % 2 === 0;
    if (!blinking) this.#drawCar(snapshot.carX, width, height);

    g.restore();
  }

  #drawRoad(width: number, height: number, distance: number): void {
    const g = this.#context;
    const left = project(0, 1, width, height);
    const right = project(1, 1, width, height);
    const farLeft = project(0, 0, width, height);
    const farRight = project(1, 0, width, height);

    g.fillStyle = ROAD;
    g.beginPath();
    g.moveTo(farLeft.x, farLeft.y);
    g.lineTo(farRight.x, farRight.y);
    g.lineTo(right.x, right.y);
    g.lineTo(left.x, left.y);
    g.closePath();
    g.fill();

    g.strokeStyle = VERGE;
    g.lineWidth = Math.max(2, width * 0.006);
    g.beginPath();
    g.moveTo(farLeft.x, farLeft.y); g.lineTo(left.x, left.y);
    g.moveTo(farRight.x, farRight.y); g.lineTo(right.x, right.y);
    g.stroke();

    // Centre dashes scrolling with distance, which is the only thing that shows speed when the
    // road is empty. Without them a stationary car and a car at full speed look identical.
    g.strokeStyle = LINE;
    const phase = (distance / 12) % 1;
    for (let i = 0; i < 9; i++) {
      const near = ((i + phase) / 9);
      const far = near - 0.055;
      if (far <= 0) continue;
      const a = project(0.5, far, width, height);
      const b = project(0.5, near, width, height);
      g.lineWidth = Math.max(1, width * 0.008 * b.scale);
      g.globalAlpha = Math.min(1, b.scale * 2.2);
      g.beginPath(); g.moveTo(a.x, a.y); g.lineTo(b.x, b.y); g.stroke();
    }
    g.globalAlpha = 1;
  }

  #drawCar(carX: number, width: number, height: number): void {
    const g = this.#context;
    const at = project(carX, 1, width, height);
    const w = width * 0.11;
    const h = w * 0.78;
    g.fillStyle = CAR;
    g.beginPath();
    g.moveTo(at.x, at.y - h);
    g.lineTo(at.x + w / 2, at.y);
    g.lineTo(at.x + w / 3, at.y + h * 0.18);
    g.lineTo(at.x - w / 3, at.y + h * 0.18);
    g.lineTo(at.x - w / 2, at.y);
    g.closePath();
    g.fill();
    g.fillStyle = "#123";
    g.fillRect(at.x - w * 0.18, at.y - h * 0.62, w * 0.36, h * 0.3);
  }
}
