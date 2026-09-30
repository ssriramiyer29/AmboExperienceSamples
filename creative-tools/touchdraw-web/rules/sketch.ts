/**
 * TouchDraw's rules: what a touch means, and where on the paper it lands.
 *
 * No DOM here, by design and by test - `tools/check_samples.py` reads `rules/*.ts` for DOM globals
 * and fails if it finds one. The renderer owns the canvas; this file owns the drawing.
 *
 * The one idea everything else follows from: **the phone is not the whole canvas.** It is a window
 * onto a larger sheet. So a stroke is stored in canvas coordinates rather than pad coordinates -
 * otherwise panning would drag every existing stroke along with the view, which is the opposite of
 * what a canvas is.
 */

/** A point on the paper, in canvas units. Never in pad units - see the note above. */
export interface Point {
  readonly x: number;
  readonly y: number;
}

export interface Stroke {
  readonly colour: string;
  readonly width: number;
  readonly points: readonly Point[];
}

/**
 * Draw and pan are mutually exclusive because there is only one gesture to spend.
 *
 * A finger drag is the whole vocabulary of a touch surface. On a desktop, drawing and panning are
 * told apart by a modifier key or a second button; here there is neither, so the same drag has to
 * mean exactly one thing at a time and the phone's palette says which.
 */
export type Mode = "draw" | "pan";

export interface SketchConfig {
  /** The whole sheet, in canvas units. Larger than the viewport, which is the point. */
  readonly canvasWidth: number;
  readonly canvasHeight: number;
  /** The part of the sheet the pad maps onto. */
  readonly viewWidth: number;
  readonly viewHeight: number;
  readonly colours: readonly string[];
  readonly widths: readonly number[];
  /**
   * Caps, so a long demo cannot grow without bound. A touch surface reports at the display's rate,
   * so an idle finger resting on the pad still produces points.
   */
  readonly maxStrokes: number;
  readonly maxPointsPerStroke: number;
}

export function sketchConfig(overrides: Partial<SketchConfig> = {}): SketchConfig {
  return {
    canvasWidth: 2400,
    canvasHeight: 1600,
    viewWidth: 1200,
    viewHeight: 800,
    colours: ["#16222e", "#d64545", "#2f7fd6", "#2fae66"],
    widths: [4, 11, 24],
    maxStrokes: 400,
    maxPointsPerStroke: 4000,
    ...overrides,
  };
}

interface ActiveStroke {
  readonly colour: string;
  readonly width: number;
  readonly points: Point[];
}

/** The sheet and the window onto it, in canvas units. */
export interface SketchGeometry {
  readonly canvasWidth: number;
  readonly canvasHeight: number;
  readonly viewWidth: number;
  readonly viewHeight: number;
}

export interface SketchSnapshot {
  readonly strokes: readonly Stroke[];
  /** Strokes still under a finger, drawn the same way but not yet committed to the undo history. */
  readonly active: readonly Stroke[];
  /** Top-left of the viewport on the sheet, in canvas units. */
  readonly view: Point;
  readonly mode: Mode;
  readonly colour: string;
  readonly width: number;
  readonly colourIndex: number;
  readonly widthIndex: number;
  /**
   * Travels with the snapshot rather than being handed to the renderer separately, so a frame's
   * strokes can never be drawn against a different frame's viewport.
   */
  readonly geometry: SketchGeometry;
}

export class Sketch {
  readonly #config: SketchConfig;
  readonly #strokes: Stroke[] = [];
  /**
   * Keyed by pointer id, so several fingers draw several strokes at once rather than one stroke
   * that jumps between them. The Companion captures each pointer to whichever surface it started
   * on, so the ids arriving here are stable for the life of a contact.
   */
  readonly #active = new Map<number, ActiveStroke>();
  /** Where each pointer was last seen, in pad units. Only used for panning, which is relative. */
  readonly #lastPad = new Map<number, Point>();
  #view: Point = { x: 0, y: 0 };
  #mode: Mode = "draw";
  #colourIndex = 0;
  #widthIndex = 1;
  /** Which pointer owns the pan, so a second finger cannot fight the first over the view. */
  #panPointer: number | null = null;

  constructor(config: SketchConfig) {
    this.#config = config;
    this.#view = this.#clamp({
      x: (config.canvasWidth - config.viewWidth) / 2,
      y: (config.canvasHeight - config.viewHeight) / 2,
    });
  }

  get mode(): Mode { return this.#mode; }
  get colour(): string { return this.#config.colours[this.#colourIndex] ?? "#16222e"; }
  get width(): number { return this.#config.widths[this.#widthIndex] ?? 11; }
  get config(): SketchConfig { return this.#config; }
  get strokeCount(): number { return this.#strokes.length; }

  /**
   * Switching mode ends whatever is in progress.
   *
   * Without this, lifting a finger after a mode change would finish a stroke that the person
   * thought they had stopped drawing, or pan by the distance between two unrelated contacts.
   */
  setMode(mode: Mode): void {
    if (mode === this.#mode) return;
    this.#mode = mode;
    this.#commitAll();
    this.#lastPad.clear();
    this.#panPointer = null;
  }

  toggleMode(): void { this.setMode(this.#mode === "draw" ? "pan" : "draw"); }

  pickColour(index: number): void {
    if (index >= 0 && index < this.#config.colours.length) this.#colourIndex = index;
  }

  cycleWidth(): void {
    this.#widthIndex = (this.#widthIndex + 1) % this.#config.widths.length;
  }

  /** Removes the most recent finished stroke. A stroke still under a finger is not a candidate. */
  undo(): void { this.#strokes.pop(); }

  clear(): void {
    this.#strokes.length = 0;
    this.#active.clear();
    this.#lastPad.clear();
  }

  /** A contact begins. `padX`/`padY` are 0..1 across the drawing area of the pad. */
  begin(pointerId: number, padX: number, padY: number): void {
    this.#lastPad.set(pointerId, { x: padX, y: padY });
    if (this.#mode === "pan") {
      this.#panPointer ??= pointerId;
      return;
    }
    if (this.#strokes.length >= this.#config.maxStrokes) this.#strokes.shift();
    this.#active.set(pointerId, {
      colour: this.colour,
      width: this.width,
      points: [this.toCanvas(padX, padY)],
    });
  }

  move(pointerId: number, padX: number, padY: number): void {
    const previous = this.#lastPad.get(pointerId);
    this.#lastPad.set(pointerId, { x: padX, y: padY });
    if (this.#mode === "pan") {
      if (previous === undefined || this.#panPointer !== pointerId) return;
      this.panBy(padX - previous.x, padY - previous.y);
      return;
    }
    const stroke = this.#active.get(pointerId);
    // A move without a down happens after a mode switch, and after the Companion cancels a
    // pointer it had captured. Starting a stroke here instead would draw a line from wherever the
    // finger happens to be, which looks like a glitch rather than a gesture.
    if (stroke === undefined) return;
    if (stroke.points.length >= this.#config.maxPointsPerStroke) return;
    stroke.points.push(this.toCanvas(padX, padY));
  }

  end(pointerId: number): void {
    this.#lastPad.delete(pointerId);
    if (this.#panPointer === pointerId) this.#panPointer = null;
    this.#commit(pointerId);
  }

  /**
   * The contact was taken away rather than lifted, so the stroke is discarded instead of kept.
   *
   * A cancel means something else claimed the pointer - the Companion hands a contact to whichever
   * surface it started on, and cancels the other. Committing a half-drawn line in that case leaves
   * ink the person never chose to lay down.
   */
  cancel(pointerId: number): void {
    this.#active.delete(pointerId);
    this.#lastPad.delete(pointerId);
    if (this.#panPointer === pointerId) this.#panPointer = null;
  }

  /**
   * Pans by a fraction of the viewport, in the direction that moves the paper with the finger.
   *
   * Dragging right shows what is to the LEFT, because the gesture is understood as holding the
   * sheet rather than pushing a scrollbar - which is why the deltas are subtracted.
   */
  panBy(dxPad: number, dyPad: number): void {
    this.#view = this.#clamp({
      x: this.#view.x - dxPad * this.#config.viewWidth,
      y: this.#view.y - dyPad * this.#config.viewHeight,
    });
  }

  /** Pad coordinates (0..1 across the drawing area) to a point on the sheet. */
  toCanvas(padX: number, padY: number): Point {
    return {
      x: this.#view.x + padX * this.#config.viewWidth,
      y: this.#view.y + padY * this.#config.viewHeight,
    };
  }

  snapshot(): SketchSnapshot {
    return {
      strokes: this.#strokes,
      active: [...this.#active.values()],
      view: this.#view,
      mode: this.#mode,
      colour: this.colour,
      width: this.width,
      colourIndex: this.#colourIndex,
      widthIndex: this.#widthIndex,
      geometry: {
        canvasWidth: this.#config.canvasWidth,
        canvasHeight: this.#config.canvasHeight,
        viewWidth: this.#config.viewWidth,
        viewHeight: this.#config.viewHeight,
      },
    };
  }

  #commit(pointerId: number): void {
    const stroke = this.#active.get(pointerId);
    this.#active.delete(pointerId);
    // A single tap is a dot, not a stroke, and a dot with one point has no length for a line cap
    // to round - so it is kept and the renderer draws it as a point.
    if (stroke !== undefined) this.#strokes.push(stroke);
  }

  #commitAll(): void {
    for (const id of [...this.#active.keys()]) this.#commit(id);
  }

  #clamp(view: Point): Point {
    const maxX = Math.max(0, this.#config.canvasWidth - this.#config.viewWidth);
    const maxY = Math.max(0, this.#config.canvasHeight - this.#config.viewHeight);
    return {
      x: Math.min(Math.max(view.x, 0), maxX),
      y: Math.min(Math.max(view.y, 0), maxY),
    };
  }
}
