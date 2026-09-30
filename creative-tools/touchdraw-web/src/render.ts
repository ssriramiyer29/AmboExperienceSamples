import type { SketchSnapshot, Stroke } from "../rules/sketch.js";

/**
 * Draws a sketch. Owns no rules: hand it the same snapshot twice and it draws the same frame twice.
 *
 * Two things here are presentation decisions rather than rules, which is why they live in this file
 * and not in `rules/`:
 *
 * - **The palette mirror.** The phone's buttons are redrawn along the same two edges of the screen
 *   that they occupy on the pad. Nobody watching a demo can see the presenter's phone, so without
 *   this the audience sees colours change with no visible cause. The positions come from the same
 *   layout the experience sent to the Companion, so the two cannot drift.
 * - **The minimap.** The pad is a window onto a larger sheet, and a window with no frame is
 *   indistinguishable from a sheet that happens to be scrolling.
 */

export interface PaletteChip {
  readonly id: string;
  readonly label: string;
  /** 0..1 across the pad, the same numbers the Companion was given. */
  readonly x: number;
  readonly y: number;
  /** Filled with this when it is the active choice; undefined for tools that have no state. */
  readonly swatch?: string;
}

const PAPER = "#f6f1e7";
const PAPER_EDGE = "#ded5c4";
const CHIP = "rgba(12,24,34,.72)";
const CHIP_ON = "#3ad6a4";
const LABEL = "#eef6fb";

export class SketchRenderer {
  readonly #context: CanvasRenderingContext2D;

  constructor(context: CanvasRenderingContext2D) {
    this.#context = context;
  }

  draw(
    snapshot: SketchSnapshot, width: number, height: number,
    chips: readonly PaletteChip[], pointer: { readonly x: number; readonly y: number } | null,
  ): void {
    const g = this.#context;
    const { canvasWidth, canvasHeight, viewWidth, viewHeight } = snapshot.geometry;

    g.save();
    g.fillStyle = PAPER;
    g.fillRect(0, 0, width, height);

    // Canvas units to screen pixels. The viewport's aspect is chosen to match the pad's drawing
    // area, so this is one scale for both axes - a circle drawn on the phone is a circle here.
    const scale = width / viewWidth;
    g.translate(-snapshot.view.x * scale, -snapshot.view.y * scale);
    g.scale(scale, scale);

    // The edge of the sheet, so panning to the boundary is visibly a boundary and not a freeze.
    g.strokeStyle = PAPER_EDGE;
    g.lineWidth = 6 / scale;
    g.strokeRect(0, 0, canvasWidth, canvasHeight);

    g.lineCap = "round";
    g.lineJoin = "round";
    for (const stroke of snapshot.strokes) this.#stroke(stroke);
    for (const stroke of snapshot.active) this.#stroke(stroke);
    g.restore();

    this.#minimap(snapshot, width, height, canvasWidth, canvasHeight, viewWidth, viewHeight);
    this.#chips(snapshot, width, height, chips);
    if (pointer !== null) this.#pointer(pointer, width, height);
  }

  /** A one-point stroke is a dot: a zero-length line draws nothing, so it is filled instead. */
  #stroke(stroke: Stroke): void {
    const g = this.#context;
    const [first] = stroke.points;
    if (first === undefined) return;
    if (stroke.points.length === 1) {
      g.fillStyle = stroke.colour;
      g.beginPath();
      g.arc(first.x, first.y, stroke.width / 2, 0, Math.PI * 2);
      g.fill();
      return;
    }
    g.strokeStyle = stroke.colour;
    g.lineWidth = stroke.width;
    g.beginPath();
    g.moveTo(first.x, first.y);
    for (let i = 1; i < stroke.points.length; i += 1) {
      const point = stroke.points[i];
      if (point !== undefined) g.lineTo(point.x, point.y);
    }
    g.stroke();
  }

  #minimap(
    snapshot: SketchSnapshot, width: number, height: number,
    canvasWidth: number, canvasHeight: number, viewWidth: number, viewHeight: number,
  ): void {
    const g = this.#context;
    const boxWidth = Math.min(150, width * 0.18);
    const boxHeight = boxWidth * (canvasHeight / canvasWidth);
    const x = width - boxWidth - 14;
    const y = 14;
    g.save();
    g.fillStyle = "rgba(12,24,34,.55)";
    g.fillRect(x, y, boxWidth, boxHeight);
    g.strokeStyle = "rgba(255,255,255,.35)";
    g.lineWidth = 1;
    g.strokeRect(x, y, boxWidth, boxHeight);
    g.fillStyle = "rgba(58,214,164,.30)";
    g.strokeStyle = CHIP_ON;
    const vx = x + (snapshot.view.x / canvasWidth) * boxWidth;
    const vy = y + (snapshot.view.y / canvasHeight) * boxHeight;
    const vw = (viewWidth / canvasWidth) * boxWidth;
    const vh = (viewHeight / canvasHeight) * boxHeight;
    g.fillRect(vx, vy, vw, vh);
    g.strokeRect(vx, vy, vw, vh);
    g.restore();
  }

  /**
   * The palette, at the pad's own coordinates.
   *
   * Chips are placed by the layout's x/y rather than by a layout of this file's own, so what the
   * audience sees is where the presenter's thumb actually is.
   */
  #chips(
    snapshot: SketchSnapshot, width: number, height: number, chips: readonly PaletteChip[],
  ): void {
    const g = this.#context;
    const radius = Math.max(13, Math.min(width, height) * 0.032);
    g.save();
    g.font = `600 ${Math.round(radius * 0.62)}px system-ui, -apple-system, sans-serif`;
    g.textAlign = "center";
    g.textBaseline = "middle";
    for (const chip of chips) {
      const cx = chip.x * width;
      const cy = chip.y * height;
      const active = isActive(chip, snapshot);
      g.beginPath();
      g.arc(cx, cy, radius, 0, Math.PI * 2);
      g.fillStyle = chip.swatch ?? CHIP;
      g.fill();
      g.lineWidth = active ? 3 : 1;
      g.strokeStyle = active ? CHIP_ON : "rgba(255,255,255,.28)";
      g.stroke();
      if (chip.id === "width") {
        // The tool that has no label worth writing: it shows the nib it would draw with.
        g.fillStyle = LABEL;
        g.beginPath();
        g.arc(cx, cy, Math.max(2, (snapshot.width / 24) * radius * 0.6), 0, Math.PI * 2);
        g.fill();
      } else if (chip.swatch === undefined) {
        g.fillStyle = LABEL;
        g.fillText(chip.label, cx, cy);
      }
    }
    g.restore();
  }

  /** Where the last contact was, so a presenter can point at the screen and say "there". */
  #pointer(pointer: { readonly x: number; readonly y: number }, width: number, height: number): void {
    const g = this.#context;
    g.save();
    g.strokeStyle = "rgba(20,32,44,.45)";
    g.lineWidth = 1.5;
    const x = pointer.x * width;
    const y = pointer.y * height;
    g.beginPath();
    g.moveTo(x - 11, y); g.lineTo(x + 11, y);
    g.moveTo(x, y - 11); g.lineTo(x, y + 11);
    g.stroke();
    g.restore();
  }
}

/**
 * Which chip is lit. Only the choices that have state can be active: `undo` and `clear` are verbs,
 * and a verb is never the current one.
 */
function isActive(chip: PaletteChip, snapshot: SketchSnapshot): boolean {
  if (chip.id === "mode-draw") return snapshot.mode === "draw";
  if (chip.id === "mode-pan") return snapshot.mode === "pan";
  if (chip.id.startsWith("ink-")) return chip.swatch === snapshot.colour;
  return false;
}
