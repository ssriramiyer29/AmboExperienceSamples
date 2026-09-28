import type { Obstacle, RaceSnapshot } from "../rules/race.js";

/**
 * Draws a race snapshot. Owns no rules and decides nothing - hand it the same snapshot twice and
 * it draws the same frame twice.
 *
 * The road is drawn in false perspective: the rules work in a flat 0..1 square and this maps y to
 * a horizon so that far things are small and central. That mapping lives here rather than in the
 * rules because it is a look, not a rule - a top-down renderer of the same game would skip it.
 */

const HORIZON_SCALE = 0.16;
const HORIZON_Y = 0.30;

export interface Projected { readonly x: number; readonly y: number; readonly scale: number; }

/** Road space (0..1, 0..1) to canvas pixels. Exported because the tests check the mapping. */
export function project(x: number, y: number, width: number, height: number): Projected {
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

/** A rounded rectangle, filled. Written out because roundRect is not everywhere yet. */
function rounded(
  g: CanvasRenderingContext2D, x: number, y: number, w: number, h: number, r: number,
): void {
  const radius = Math.min(r, w / 2, h / 2);
  g.beginPath();
  g.moveTo(x + radius, y);
  g.arcTo(x + w, y, x + w, y + h, radius);
  g.arcTo(x + w, y + h, x, y + h, radius);
  g.arcTo(x, y + h, x, y, radius);
  g.arcTo(x, y, x + w, y, radius);
  g.closePath();
  g.fill();
}

export class RaceRenderer {
  readonly #context: CanvasRenderingContext2D;
  /** Smoothed, so the car leans into a turn instead of snapping. Presentation only. */
  #lean = 0;

  constructor(context: CanvasRenderingContext2D) {
    this.#context = context;
  }

  draw(snapshot: RaceSnapshot, width: number, height: number, steering = 0): void {
    const g = this.#context;
    this.#lean += (steering - this.#lean) * 0.12;

    g.save();
    this.#drawSky(width, height);
    this.#drawRoad(width, height, snapshot.distance);
    this.#drawPosts(width, height, snapshot.distance);

    // Far to near, so a near obstacle overlaps a far one rather than the other way round.
    for (const obstacle of [...snapshot.obstacles].sort((a, b) => a.y - b.y)) {
      this.#drawObstacle(obstacle, width, height);
    }

    // The car blinks while it cannot be hit, so a player who has just been hit can see why they
    // are driving through the next cone unharmed.
    const blinking = snapshot.invulnerableMs > 0
      && Math.floor(snapshot.invulnerableMs / 120) % 2 === 0;
    if (!blinking) this.#drawCar(snapshot.carX, width, height);

    if (snapshot.phase === "paused") this.#drawPausedWash(width, height);
    g.restore();
  }

  #drawSky(width: number, height: number): void {
    const g = this.#context;
    const horizon = height * HORIZON_Y;
    const sky = g.createLinearGradient(0, 0, 0, horizon);
    sky.addColorStop(0, "#07131f");
    sky.addColorStop(0.65, "#0d2942");
    sky.addColorStop(1, "#1d5570");
    g.fillStyle = sky;
    g.fillRect(0, 0, width, horizon);

    // A low sun on the horizon. It is the only thing that tells you which way is far.
    const glow = g.createRadialGradient(width / 2, horizon, 0, width / 2, horizon, width * 0.34);
    glow.addColorStop(0, "rgba(255,196,120,.55)");
    glow.addColorStop(1, "rgba(255,196,120,0)");
    g.fillStyle = glow;
    g.fillRect(0, 0, width, horizon + 2);

    g.fillStyle = "#08141d";
    g.fillRect(0, horizon, width, height - horizon);
  }

  #drawRoad(width: number, height: number, distance: number): void {
    const g = this.#context;
    const near = { left: project(0, 1, width, height), right: project(1, 1, width, height) };
    const far = { left: project(0, 0, width, height), right: project(1, 0, width, height) };

    const surface = g.createLinearGradient(0, far.left.y, 0, near.left.y);
    surface.addColorStop(0, "#1b2b38");
    surface.addColorStop(1, "#283b4b");
    g.fillStyle = surface;
    g.beginPath();
    g.moveTo(far.left.x, far.left.y);
    g.lineTo(far.right.x, far.right.y);
    g.lineTo(near.right.x, near.right.y);
    g.lineTo(near.left.x, near.left.y);
    g.closePath();
    g.fill();

    // Verges, in scrolling blocks rather than solid lines - a solid verge reads as a still image.
    const phase = (distance / 9) % 1;
    for (let i = 0; i < 14; i++) {
      const b = (i + phase) / 14;
      const a = b - 0.035;
      if (a <= 0) continue;
      const on = Math.floor(i + phase) % 2 === 0;
      g.fillStyle = on ? "#ff5f6d" : "#f4f7fb";
      for (const side of [0, 1]) {
        const p1 = project(side, a, width, height);
        const p2 = project(side, b, width, height);
        const w = Math.max(1.5, width * 0.018 * p2.scale);
        g.beginPath();
        g.moveTo(p1.x - w / 2, p1.y); g.lineTo(p1.x + w / 2, p1.y);
        g.lineTo(p2.x + w / 2, p2.y); g.lineTo(p2.x - w / 2, p2.y);
        g.closePath(); g.fill();
      }
    }

    // Centre dashes, the only thing that shows speed when the road is empty.
    g.fillStyle = "rgba(232,244,255,.85)";
    for (let i = 0; i < 9; i++) {
      const b = (i + ((distance / 12) % 1)) / 9;
      const a = b - 0.05;
      if (a <= 0) continue;
      const p1 = project(0.5, a, width, height);
      const p2 = project(0.5, b, width, height);
      g.globalAlpha = Math.min(1, p2.scale * 2.4);
      g.beginPath();
      g.moveTo(p1.x - width * 0.004 * p1.scale, p1.y);
      g.lineTo(p1.x + width * 0.004 * p1.scale, p1.y);
      g.lineTo(p2.x + width * 0.006 * p2.scale, p2.y);
      g.lineTo(p2.x - width * 0.006 * p2.scale, p2.y);
      g.closePath(); g.fill();
    }
    g.globalAlpha = 1;
  }

  /** Posts flicking past outside the verge. Peripheral motion is most of the sense of speed. */
  #drawPosts(width: number, height: number, distance: number): void {
    const g = this.#context;
    g.fillStyle = "#2f4a5e";
    for (let i = 0; i < 7; i++) {
      const y = (i + ((distance / 22) % 1)) / 7;
      if (y <= 0.02) continue;
      for (const side of [-0.14, 1.14]) {
        const at = project(side, y, width, height);
        const h = height * 0.13 * at.scale;
        const w = Math.max(1.5, width * 0.012 * at.scale);
        g.fillRect(at.x - w / 2, at.y - h, w, h);
      }
    }
  }

  #drawObstacle(obstacle: Obstacle, width: number, height: number): void {
    const g = this.#context;
    const at = project(obstacle.x, obstacle.y, width, height);
    const size = Math.max(3, width * 0.08 * at.scale);

    // A contact shadow, so an obstacle sits on the road rather than floating above it.
    g.fillStyle = "rgba(0,0,0,.35)";
    g.beginPath();
    g.ellipse(at.x, at.y + size * 0.08, size * 0.85, size * 0.22, 0, 0, Math.PI * 2);
    g.fill();

    if (obstacle.kind === "barrier") {
      const w = size * 2.1, h = size * 0.52;
      g.fillStyle = "#c9d6e2";
      g.fillRect(at.x - w * 0.42, at.y - h * 0.15, w * 0.06, h * 1.1);
      g.fillRect(at.x + w * 0.36, at.y - h * 0.15, w * 0.06, h * 1.1);
      const stripes = 5;
      for (let i = 0; i < stripes; i++) {
        g.fillStyle = i % 2 === 0 ? "#ff4968" : "#f4f7fb";
        g.fillRect(at.x - w / 2 + (w / stripes) * i, at.y - h, w / stripes, h * 0.72);
      }
    } else {
      g.fillStyle = "#1b1f24";
      g.fillRect(at.x - size * 0.62, at.y - size * 0.1, size * 1.24, size * 0.14);
      g.fillStyle = "#ff7a3d";
      g.beginPath();
      g.moveTo(at.x, at.y - size * 1.15);
      g.lineTo(at.x + size * 0.5, at.y);
      g.lineTo(at.x - size * 0.5, at.y);
      g.closePath();
      g.fill();
      g.fillStyle = "#fff4e8";
      g.beginPath();
      g.moveTo(at.x - size * 0.29, at.y - size * 0.52);
      g.lineTo(at.x + size * 0.29, at.y - size * 0.52);
      g.lineTo(at.x + size * 0.21, at.y - size * 0.72);
      g.lineTo(at.x - size * 0.21, at.y - size * 0.72);
      g.closePath();
      g.fill();
    }
  }

  /**
   * The car, seen from behind, because that is where the camera is.
   *
   * It was a wedge - a triangle with a windscreen - which reads as an arrow pointing away rather
   * than as a car driving away. From behind you see tyres, a bumper, a cabin narrower than the
   * body, a dark rear window and two lights. Those five things are what make it a car; the exact
   * curve of the bodywork does not.
   */
  #drawCar(carX: number, width: number, height: number): void {
    const g = this.#context;
    const at = project(carX, 1, width, height);
    const w = width * 0.15;
    const h = w * 0.78;

    g.save();
    g.translate(at.x, at.y);

    g.fillStyle = "rgba(0,0,0,.42)";
    g.beginPath();
    g.ellipse(0, h * 0.18, w * 0.58, h * 0.15, 0, 0, Math.PI * 2);
    g.fill();

    // Leaning is what makes steering feel like steering rather than sliding.
    g.rotate(this.#lean * 0.10);

    // Tyres first, so the body sits in front of them.
    g.fillStyle = "#11181f";
    rounded(g, -w * 0.54, -h * 0.34, w * 0.18, h * 0.44, w * 0.04);
    rounded(g, w * 0.36, -h * 0.34, w * 0.18, h * 0.44, w * 0.04);

    // Lower body and rear bumper.
    const body = g.createLinearGradient(0, -h * 0.95, 0, h * 0.1);
    body.addColorStop(0, "#ffe89a");
    body.addColorStop(0.55, "#ffd84d");
    body.addColorStop(1, "#d99a16");
    g.fillStyle = body;
    rounded(g, -w * 0.46, -h * 0.62, w * 0.92, h * 0.72, w * 0.10);

    g.fillStyle = "#2a3441";
    rounded(g, -w * 0.46, -h * 0.05, w * 0.92, h * 0.15, w * 0.05);

    // Cabin, narrower than the body - the single strongest cue that this is a car from behind.
    g.fillStyle = body;
    rounded(g, -w * 0.31, -h * 0.98, w * 0.62, h * 0.42, w * 0.09);

    // Rear window.
    const glass = g.createLinearGradient(0, -h * 0.95, 0, -h * 0.62);
    glass.addColorStop(0, "#16384f");
    glass.addColorStop(1, "#0b1f2e");
    g.fillStyle = glass;
    rounded(g, -w * 0.25, -h * 0.92, w * 0.50, h * 0.30, w * 0.05);

    // Taillights.
    g.fillStyle = "#ff3b52";
    rounded(g, -w * 0.42, -h * 0.46, w * 0.20, h * 0.13, w * 0.03);
    rounded(g, w * 0.22, -h * 0.46, w * 0.20, h * 0.13, w * 0.03);
    g.fillStyle = "rgba(255,120,120,.45)";
    rounded(g, -w * 0.42, -h * 0.46, w * 0.20, h * 0.05, w * 0.02);
    rounded(g, w * 0.22, -h * 0.46, w * 0.20, h * 0.05, w * 0.02);

    // Number plate, which is small and does more than its size suggests.
    g.fillStyle = "#eef3f8";
    rounded(g, -w * 0.11, -h * 0.30, w * 0.22, h * 0.11, w * 0.015);

    g.restore();
  }

  #drawPausedWash(width: number, height: number): void {
    const g = this.#context;
    g.fillStyle = "rgba(7,21,34,.62)";
    g.fillRect(0, 0, width, height);
  }
}
