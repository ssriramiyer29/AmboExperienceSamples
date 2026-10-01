/**
 * AmboSpyglass's drawing. The rules say where the lens is; this says what that looks like.
 *
 * The one idea the whole picture serves: **the crowd is unreadable until the lens is on it.** At
 * the scale that fits a scene on a screen a character is a dozen pixels, and that illegibility is
 * the game rather than a shortcut. So the scene is drawn twice - once whole and tiny, once
 * magnified inside a circular clip - and the second drawing is the only one anybody can read.
 *
 * The role is carried by SILHOUETTE, never by colour, because colour belongs to the item the
 * prompt names. Swapping a toque for a straw hat changes who somebody is; recolouring their apron
 * changes what the sentence has to say about them. Those are different jobs and the drawing keeps
 * them apart.
 */
import type { Character, HuntSnapshot, Lexicon, Scene } from "../rules/hunt.js";

const INK = "#16222e";
const SKIN = "#e8c9a8";
const CLOTH = "#b7c4d0";
const LIGHT = "#edf2f6";
const DARK = "#36495c";

/** The six the prompt can name. Lifted from true black so "black" reads as a garment, not a hole. */
export const SWATCH: Readonly<Record<string, string>> = {
  red: "#d64545", blue: "#2f7fd6", green: "#2fae66",
  yellow: "#e8c33a", white: "#fbfdff", black: "#333f4d",
};

type Pen = CanvasRenderingContext2D;

function shape(c: Pen, fill: string, path: () => void): void {
  c.beginPath();
  path();
  c.fillStyle = fill;
  c.fill();
  c.strokeStyle = INK;
  c.lineWidth = 4;
  c.lineJoin = "round";
  c.stroke();
}

function box(c: Pen, fill: string, x: number, y: number, w: number, h: number, r = 4): void {
  shape(c, fill, () => c.roundRect(x, y, w, h, r));
}

function disc(c: Pen, fill: string, x: number, y: number, r: number): void {
  shape(c, fill, () => c.arc(x, y, r, 0, Math.PI * 2));
}

/** Headwear, which is also the legend icon - the hat is what the eye scans for. */
const HEADWEAR: Readonly<Record<string, (c: Pen) => void>> = {
  baker: (c) => { box(c, LIGHT, -17, -96, 34, 26, 8); box(c, LIGHT, -20, -74, 40, 10, 4); },
  butcher: (c) => { box(c, DARK, -20, -80, 40, 13, 5); shape(c, DARK, () => c.roundRect(-30, -72, 26, 7, 3)); },
  farmer: (c) => {
    shape(c, "#d9b26a", () => c.ellipse(0, -70, 36, 9, 0, 0, Math.PI * 2));
    shape(c, "#d9b26a", () => c.ellipse(0, -78, 19, 14, 0, 0, Math.PI * 2));
  },
  fisher: (c) => {
    shape(c, "#e0a53c", () => c.ellipse(0, -76, 22, 17, 0, 0, Math.PI * 2));
    shape(c, "#e0a53c", () => c.ellipse(0, -64, 28, 9, 0, 0, Math.PI * 2));
    shape(c, "#e0a53c", () => c.roundRect(-10, -64, 20, 16, 6));
  },
  nurse: (c) => {
    box(c, LIGHT, -18, -84, 36, 16, 4);
    c.fillStyle = "#d64545";
    c.fillRect(-3, -81, 6, 10);
    c.fillRect(-8, -78, 16, 4);
  },
  painter: (c) => {
    shape(c, "#8e3f6b", () => c.ellipse(-2, -76, 24, 12, -0.18, 0, Math.PI * 2));
    disc(c, "#8e3f6b", 14, -86, 5);
  },
  sailor: (c) => { box(c, LIGHT, -18, -82, 36, 12, 3); shape(c, "#2f5f8f", () => c.roundRect(-19, -72, 38, 6, 2)); },
  teacher: (c) => { shape(c, "#4a3b33", () => c.ellipse(0, -74, 22, 13, 0, Math.PI, 0)); },
};

/** What is in the hands, on the character's right, so an item can use the other side. */
const TOOL: Readonly<Record<string, (c: Pen) => void>> = {
  baker: (c) => { box(c, "#e3d3bb", 24, 6, 44, 11, 5); box(c, "#c9b696", 20, 3, 7, 17, 3); box(c, "#c9b696", 65, 3, 7, 17, 3); },
  butcher: (c) => { box(c, "#cfd8de", 30, -24, 30, 22, 3); box(c, DARK, 36, -2, 8, 22, 3); },
  farmer: (c) => {
    box(c, "#8a6a45", 34, -38, 7, 76, 3);
    for (const x of [26, 34, 42]) box(c, "#cfd8de", x, -52, 6, 16, 2);
  },
  fisher: (c) => {
    shape(c, "#7fb6c9", () => c.ellipse(46, 12, 20, 11, 0.2, 0, Math.PI * 2));
    shape(c, "#7fb6c9", () => { c.moveTo(64, 10); c.lineTo(78, 0); c.lineTo(78, 22); });
    disc(c, LIGHT, 38, 8, 3);
  },
  nurse: (c) => { box(c, "#cfd8de", 26, -10, 32, 40, 4); box(c, LIGHT, 31, -4, 22, 28, 2); },
  painter: (c) => {
    shape(c, "#b98a5a", () => c.ellipse(44, 10, 24, 17, 0, 0, Math.PI * 2));
    disc(c, "#d64545", 36, 2, 4); disc(c, "#2f7fd6", 48, 0, 4); disc(c, "#2fae66", 52, 14, 4);
  },
  sailor: (c) => {
    box(c, "#cfd8de", 36, -16, 7, 30, 3);
    shape(c, "#cfd8de", () => c.arc(39, 16, 18, 0, Math.PI));
    box(c, "#cfd8de", 26, -14, 27, 6, 2);
  },
  teacher: (c) => {
    shape(c, LIGHT, () => c.roundRect(24, -6, 24, 32, 3));
    shape(c, "#dfe7ec", () => c.roundRect(46, -6, 24, 32, 3));
    box(c, DARK, 45, -8, 4, 36, 2);
  },
};

/** The coloured item, on the left where no tool is. Separate so the prompt can show it alone. */
const TRAIT: Readonly<Record<string, (c: Pen, paint: string) => void>> = {
  shirt: (c, paint) => box(c, paint, -26, -26, 52, 44, 12),
  scarf: (c, paint) => { box(c, paint, -25, -28, 50, 15, 6); box(c, paint, -22, -16, 14, 34, 5); },
  apron: (c, paint) => { box(c, paint, -21, -10, 42, 56, 7); box(c, paint, -4, -28, 8, 20, 3); },
  bag: (c, paint) => {
    box(c, paint, -58, 6, 32, 28, 6);
    c.strokeStyle = paint; c.lineWidth = 7; c.lineCap = "round";
    c.beginPath(); c.moveTo(-50, 4); c.lineTo(-10, -26); c.stroke();
  },
  umbrella: (c, paint) => {
    box(c, "#6b5848", -48, -34, 6, 74, 3);
    shape(c, paint, () => c.ellipse(-45, -36, 36, 22, 0, Math.PI, 0));
    c.strokeStyle = INK; c.lineWidth = 3;
    c.beginPath(); c.arc(-38, 38, 8, 0, Math.PI); c.stroke();
  },
};

/** Where each item sits on a body, so a prompt chip can centre it instead of cropping it. */
const TRAIT_CENTRE: Readonly<Record<string, { x: number; y: number; span: number }>> = {
  shirt: { x: 0, y: -4, span: 70 },
  scarf: { x: -12, y: -2, span: 70 },
  apron: { x: 0, y: 12, span: 76 },
  bag: { x: -40, y: 8, span: 74 },
  umbrella: { x: -45, y: -14, span: 96 },
};

const nothing = (): void => {};

export function drawCharacter(c: Pen, ch: Character, detailed: boolean): void {
  const paint = SWATCH[ch.colour] ?? "#888888";
  c.save();
  c.translate(ch.x, ch.y);
  c.rotate((ch.pose - 0.5) * 0.16);

  if (!detailed) {
    // The far view: a person-shaped mark carrying the item's colour. Enough to see the square is
    // crowded, not enough to read anybody - which is the problem the lens exists to solve.
    c.fillStyle = CLOTH;
    c.beginPath(); c.roundRect(-20, -26, 40, 72, 12); c.fill();
    c.beginPath(); c.arc(0, -48, 19, 0, Math.PI * 2); c.fill();
    c.fillStyle = paint;
    c.beginPath(); c.roundRect(-20, -24, 40, 30, 8); c.fill();
    c.restore();
    return;
  }

  c.scale(0.92, 0.92);
  box(c, DARK, -19, 40, 16, 40, 5);
  box(c, DARK, 3, 40, 16, 40, 5);
  box(c, CLOTH, -26, -26, 52, 70, 12);
  box(c, SKIN, -36, -18, 13, 46, 6);
  box(c, SKIN, 23, -18, 13, 46, 6);
  disc(c, SKIN, 0, -48, 22);
  c.fillStyle = INK;
  c.beginPath(); c.arc(-7, -50, 2.6, 0, Math.PI * 2); c.arc(7, -50, 2.6, 0, Math.PI * 2); c.fill();

  (TOOL[ch.subject] ?? nothing)(c);
  (TRAIT[ch.trait] ?? ((_c: Pen, _p: string) => {}))(c, paint);
  (HEADWEAR[ch.subject] ?? nothing)(c);
  c.restore();
}

/**
 * One subject or one item, drawn into a small canvas for the prompt.
 *
 * The prompt shows the target's picture beside its word, which is what lets a child who cannot
 * read the word yet still play - and is how the word and the picture get learned together rather
 * than one after the other.
 */
export function iconCanvas(kind: "subject" | "trait", id: string, paint: string, size = 46): HTMLCanvasElement {
  const el = document.createElement("canvas");
  const ratio = Math.min(2, window.devicePixelRatio || 1);
  el.width = size * ratio;
  el.height = size * ratio;
  el.style.width = `${size}px`;
  el.style.height = `${size}px`;
  const g = el.getContext("2d");
  if (g === null) return el;
  g.scale(ratio, ratio);
  g.translate(size / 2, size / 2);
  if (kind === "subject") {
    g.scale(size / 230, size / 230);
    g.translate(0, 20);
    box(g, CLOTH, -26, -26, 52, 70, 12);
    disc(g, SKIN, 0, -48, 22);
    (HEADWEAR[id] ?? nothing)(g);
    (TOOL[id] ?? nothing)(g);
  } else {
    const centre = TRAIT_CENTRE[id] ?? { x: 0, y: 0, span: 150 };
    g.scale(size / centre.span, size / centre.span);
    g.translate(-centre.x, -centre.y);
    (TRAIT[id] ?? ((_c: Pen, _p: string) => {}))(g, paint);
  }
  return el;
}

export class SpyglassRenderer {
  readonly #canvas: HTMLCanvasElement;
  readonly #pen: Pen;
  /** How much bigger the lens draws the scene. Four is enough to read a tool at arm's length. */
  readonly #magnify = 4.2;

  constructor(canvas: HTMLCanvasElement) {
    this.#canvas = canvas;
    const pen = canvas.getContext("2d");
    if (pen === null) throw new Error("this browser has no 2d canvas");
    this.#pen = pen;
  }

  resize(): void {
    const ratio = Math.min(2, window.devicePixelRatio || 1);
    const rect = this.#canvas.getBoundingClientRect();
    this.#canvas.width = Math.max(1, Math.floor(rect.width * ratio));
    this.#canvas.height = Math.max(1, Math.floor(rect.height * ratio));
    this.#pen.setTransform(ratio, 0, 0, ratio, 0, 0);
  }

  draw(scene: Scene, snapshot: HuntSnapshot, lexicon: Lexicon): void {
    const c = this.#pen;
    const width = this.#canvas.width / (Math.min(2, window.devicePixelRatio || 1));
    const height = this.#canvas.height / (Math.min(2, window.devicePixelRatio || 1));

    c.fillStyle = "#bfe6f7";
    c.fillRect(0, 0, width, height);

    // Placeholder scenery. A painted background goes here - see art/CONTRACT.md Part One - and is
    // deliberately plain so nobody mistakes it for the commissioned piece.
    const fit = Math.min(width / scene.width, height / scene.height);
    const offsetX = (width - scene.width * fit) / 2;
    const offsetY = (height - scene.height * fit) / 2;

    c.save();
    c.translate(offsetX, offsetY);
    c.scale(fit, fit);
    const ground = c.createLinearGradient(0, 0, 0, scene.height);
    ground.addColorStop(0, "#a8dcf2");
    ground.addColorStop(0.52, "#d9f0e4");
    ground.addColorStop(0.53, "#9ed47c");
    ground.addColorStop(1, "#7cc264");
    c.fillStyle = ground;
    c.fillRect(0, 0, scene.width, scene.height);
    for (const character of scene.characters) drawCharacter(c, character, false);
    c.restore();

    const lensX = offsetX + snapshot.lens.x * fit;
    const lensY = offsetY + snapshot.lens.y * fit;
    const radius = snapshot.lens.radius * fit * this.#magnify;

    c.save();
    c.beginPath();
    c.arc(lensX, lensY, radius, 0, Math.PI * 2);
    c.clip();
    c.fillStyle = "rgba(255,255,255,0.16)";
    c.fill();
    c.translate(lensX, lensY);
    c.scale(fit * this.#magnify, fit * this.#magnify);
    c.translate(-snapshot.lens.x, -snapshot.lens.y);
    for (const character of scene.characters) {
      if (Math.hypot(character.x - snapshot.lens.x, character.y - snapshot.lens.y) < snapshot.lens.radius * 2.4) {
        drawCharacter(c, character, true);
      }
    }
    c.restore();

    // The ring glows white when the orientation is trusted and amber when the provider says it is
    // not - the cue to recentre, rather than a silent drift the player would blame on themselves.
    c.save();
    c.shadowColor = "rgba(255,255,255,0.95)";
    c.shadowBlur = 18;
    c.strokeStyle = snapshot.trusted ? "#ffffff" : "#f5a524";
    c.lineWidth = 5;
    c.beginPath();
    c.arc(lensX, lensY, radius, 0, Math.PI * 2);
    c.stroke();
    c.restore();

    // The claim reach, drawn, because a crosshair that hides how far it reaches makes a near-miss
    // look like a wrong judgement rather than a wrong aim.
    c.strokeStyle = "rgba(255,255,255,0.85)";
    c.lineWidth = 2;
    c.beginPath();
    c.arc(lensX, lensY, radius * 0.8, 0, Math.PI * 2);
    c.stroke();
    c.beginPath();
    c.moveTo(lensX - 12, lensY); c.lineTo(lensX + 12, lensY);
    c.moveTo(lensX, lensY - 12); c.lineTo(lensX, lensY + 12);
    c.stroke();

    this.#legend(c, lexicon);
  }

  /** Which hat is which word. Reading it is the vocabulary work, so it is not a cheat. */
  #legend(c: Pen, lexicon: Lexicon): void {
    const roles = Object.keys(lexicon.subjects);
    const rowHeight = 30;
    const top = 12;
    c.fillStyle = "rgba(255,250,240,.95)";
    c.strokeStyle = "#ffffff";
    c.lineWidth = 3;
    c.beginPath();
    c.roundRect(8, top, 150, roles.length * rowHeight + 16, 12);
    c.fill();
    c.stroke();
    c.font = "600 14px ui-monospace, 'JetBrains Mono', monospace";
    c.textBaseline = "middle";
    roles.forEach((role, index) => {
      const centre = top + 8 + index * rowHeight + rowHeight / 2;
      c.save();
      c.translate(32, centre);
      c.scale(0.26, 0.26);
      c.translate(0, 80);
      (HEADWEAR[role] ?? nothing)(c);
      c.restore();
      c.fillStyle = "#17405c";
      c.fillText(lexicon.subjects[role]?.word ?? role, 56, centre);
    });
    c.textBaseline = "alphabetic";
  }
}
