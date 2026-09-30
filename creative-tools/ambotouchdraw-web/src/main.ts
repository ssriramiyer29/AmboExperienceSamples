import {
  Aep, AepConnectionState, AepControllerButton, AepExperienceDefinition, AepTouch,
  type AepCapabilityEvent, type AepControllerLayout, type AepSession,
} from "@ambokit/aep-core";
import { AmboKitWebHost, AepPayloadJson } from "@ambokit/aep-host";
import { WebExperienceHost } from "@ambokit/aep-web";
import { Sketch, sketchConfig, type Mode } from "../rules/sketch.js";
import { SketchRenderer, type PaletteChip } from "./render.js";

/**
 * AmboTouchDraw: the phone is the pen and the palette, the browser is the paper.
 *
 * This is the sample for a **composed AmboPad**. `input.touch` and `input.controller` are granted
 * together and the Companion puts them on one screen: where a control button is, the controller
 * gets the contact; everywhere else the touch surface does. That is why a drawing app is the right
 * thing to build here rather than a second racing game - a palette and a canvas are two capabilities
 * that a person experiences as one surface, which is exactly the claim the composition makes.
 *
 * The palette is declared BY THE EXPERIENCE, not built into the Companion. Nothing in the phone
 * knows what "Undo" is; it is told where to put a button and it reports which one was pressed.
 *
 * Zoom has no button, and that is the right shape for it. `mode: "pointer"` delivers raw contacts
 * rather than recognised verbs, so two fingers arrive as two pointers and a pinch is something this
 * experience computes - which means zoom is a gesture on the paper instead of a control taking up
 * room on it. Two fingers in Draw mode already draw two strokes, so the pinch belongs to Pan, and
 * the modes stay exactly as separate as they were.
 */

const GATEWAY = resolveGateway();

function resolveGateway(): string {
  const fromQuery = new URLSearchParams(location.search).get("gateway");
  if (fromQuery) return fromQuery;
  const tag = document.currentScript as HTMLScriptElement | null;
  const fromTag = tag?.dataset["amboGateway"]
    ?? document.querySelector<HTMLElement>("[data-ambo-gateway]")?.dataset["amboGateway"];
  return fromTag || location.origin;
}

const INK_BLACK = "#16222e";
const INK_RED = "#d64545";
const INK_BLUE = "#2f7fd6";
const INK_GREEN = "#2fae66";
const COLOURS = [INK_BLACK, INK_RED, INK_BLUE, INK_GREEN];
const WIDTHS = [4, 11, 24];

/**
 * Where the palette sits on the pad, and therefore where it does not.
 *
 * These four numbers are the only place the geometry is written down. The drawing area is DERIVED
 * from them rather than chosen separately, because the two must agree: a contact inside a button's
 * hit area never reaches the touch surface, so any drawing area that overlapped one would have a
 * dead region the experience believed it owned.
 *
 * The half-extents are the Companion's, measured rather than guessed: it sizes a control from the
 * SHORT side of the pad and hit-tests at 1.2x that radius, which on a landscape pad is a much
 * larger fraction of the height than of the width. Spacing below leaves more room than that.
 */
const COLUMN_X = 0.93;
const ROW_Y = 0.90;
const HIT_HALF_X = 0.045;
const HIT_HALF_Y = 0.10;
const DRAW_RIGHT = COLUMN_X - HIT_HALF_X;
const DRAW_BOTTOM = ROW_Y - HIT_HALF_Y;

const PALETTE: readonly PaletteChip[] = [
  { id: "mode-draw", label: "Draw", x: COLUMN_X, y: 0.10 },
  { id: "mode-pan", label: "Pan", x: COLUMN_X, y: 0.32 },
  { id: "undo", label: "Undo", x: COLUMN_X, y: 0.54 },
  { id: "clear", label: "Clr", x: COLUMN_X, y: 0.76 },
  { id: "ink-0", label: "Blk", x: 0.10, y: ROW_Y, swatch: INK_BLACK },
  { id: "ink-1", label: "Red", x: 0.22, y: ROW_Y, swatch: INK_RED },
  { id: "ink-2", label: "Blu", x: 0.34, y: ROW_Y, swatch: INK_BLUE },
  { id: "ink-3", label: "Grn", x: 0.46, y: ROW_Y, swatch: INK_GREEN },
  { id: "width", label: "Nib", x: 0.60, y: ROW_Y },
];

/**
 * Contacts, not verbs - and a rate a drawing surface can use.
 *
 * Held in a constant rather than written inline in the definition below for a reason that is about
 * tooling rather than taste: `tools/check_manifests.py` reads every string literal inside the
 * `AepExperienceDefinition({...})` body and treats all but the first as capability ids. That was
 * true until per-capability config existed; an inline `mode: "pointer"` is reported as an
 * undeclared capability called "pointer". The checker wants fixing - it should read the
 * `capabilities` array rather than every string it can see - and until it is, config with a string
 * value lives outside the body, which is what AmboRacer's DRIVE_CONTROLS already does.
 */
const TOUCH_TUNING = { mode: "pointer", maxMoveHz: 90 };

/** The same nine positions, in the shape the Companion validates against its own schema. */
const PALETTE_LAYOUT: AepControllerLayout = {
  controls: PALETTE.map((chip) => ({
    id: chip.id, type: "button", label: chip.label, x: chip.x, y: chip.y, size: "small",
  })),
};

const ui = {
  frame: query<HTMLElement>(".ambotouchdraw"),
  canvas: element<HTMLCanvasElement>("stage"),
  join: element("join"),
  qr: element<HTMLImageElement>("qr"),
  joinUrl: element("joinUrl"),
  joinState: element("joinState"),
  mode: element("mode"),
  tally: element("tally"),
  message: element("message"),
};

function element<T extends HTMLElement = HTMLElement>(id: string): T {
  const found = document.getElementById(id);
  if (found === null) throw new Error(`the page is missing #${id}`);
  return found as T;
}

function query<T extends HTMLElement>(selector: string): T {
  const found = document.querySelector<T>(selector);
  if (found === null) throw new Error(`the page is missing ${selector}`);
  return found;
}

const context = ui.canvas.getContext("2d");
if (context === null) throw new Error("this browser has no 2D canvas");
const renderer = new SketchRenderer(context);

/**
 * The pad's shape, which the pad does not tell us.
 *
 * `input.touch` normalises to its OWN bounds, so 0..1 is the pad rather than the screen, and an
 * experience mapping those coordinates onto a canvas has to know the pad's aspect or every circle
 * comes out an ellipse. `ready.aspectRatio` exists in the capability's schema for precisely this
 * and the Companion does not emit it yet, so this default stands in - 2.0 being a wide landscape
 * pad with a status strip taken off the top.
 *
 * Adopted rather than assumed when it does arrive: the moment a Companion sends it, the geometry
 * below is rebuilt from the real number and the default stops mattering.
 */
const ASSUMED_PAD_ASPECT = 2.0;
let padAspect = ASSUMED_PAD_ASPECT;
let aspectIsMeasured = false;

function buildSketch(aspect: number): Sketch {
  const drawAspect = aspect * (DRAW_RIGHT / DRAW_BOTTOM);
  const viewHeight = 900;
  const viewWidth = Math.round(viewHeight * drawAspect);
  ui.frame.style.aspectRatio = `${viewWidth} / ${viewHeight}`;
  fitCanvas();
  return new Sketch(sketchConfig({
    viewWidth,
    viewHeight,
    // The sheet is four padfuls: twice the window in each direction. Enough that panning is
    // obviously moving over something larger, small enough that a presenter cannot get lost.
    canvasWidth: viewWidth * 2,
    canvasHeight: viewHeight * 2,
    colours: COLOURS,
    widths: WIDTHS,
  }));
}

let sketch = buildSketch(padAspect);
/** Where the last contact was, in draw-area units, so the screen can show a crosshair. */
let hint: { readonly x: number; readonly y: number } | null = null;
let controllerGranted = false;
let touchGranted = false;
let touchEvents = 0;

const host = new AmboKitWebHost({ gatewayBaseUrl: GATEWAY });
const session: AepSession = Aep.start(
  new AepExperienceDefinition({
    id: "com.ambokit.aep.ambotouchdraw",
    capabilities: ["input.touch", "input.controller"],
    /**
     * Both capabilities are configured, and neither config is decoration.
     *
     * `layout` is required: the Companion refuses `input.controller` outright without one, because
     * it has no palette of its own to fall back on.
     *
     * `mode: "pointer"` turns OFF gesture recognition, which matters more than it looks. The
     * default is "both", and under it a single tap arrives three times - down, up, and a tap
     * gesture. AmboRacer had to learn to ignore two of those; here the events are simply not
     * produced, which is the better place to fix it. A drawing surface wants contacts, not verbs.
     *
     * `maxMoveHz` is raised because this is the one capability consumer where the sample rate is
     * visible in the output: at 60Hz a fast stroke is a row of straight segments.
     */
    capabilityConfig: {
      "input.touch": TOUCH_TUNING,
      "input.controller": { layout: PALETTE_LAYOUT },
    },
    requiresCalibration: false,
  }),
  host,
);

session.joinChanged.subscribe((join) => {
  if (join.qrUrl) ui.qr.src = join.qrUrl;
  ui.joinUrl.textContent = join.url;
});

session.connectionChanged.subscribe((state) => {
  ui.joinState.textContent =
    state === AepConnectionState.CONNECTED ? "Phone connected."
      : state === AepConnectionState.RECONNECTING ? "Phone dropped - reconnecting…"
        : state === AepConnectionState.REJOIN_REQUIRED ? "Scan again to rejoin."
          : "Waiting for a phone…";
  if (state === AepConnectionState.REJOIN_REQUIRED) say("Scan the code again to keep drawing.");
});

session.player.connected.subscribe(() => {
  ui.join.hidden = true;
  say(controllerGranted ? "" : "Drag on the phone to draw.");
});

session.player.disconnected.subscribe(() => {
  ui.join.hidden = false;
  // Every contact is dropped rather than committed: a phone that has gone is not a finger that
  // was lifted, and the strokes it was in the middle of are not strokes anyone finished.
  for (let pointerId = 0; pointerId <= 9; pointerId += 1) sketch.cancel(pointerId);
  say("Phone left. Your drawing is still here - scan again to carry on.");
});

session.error.subscribe((error) => {
  if (error.code !== "capability_denied") return;
  say(error.message.includes("input.touch")
    ? "AmboTouchDraw needs the phone's touch surface. Allow it and scan again."
    : "The palette was declined on the phone - you can still draw, and the keyboard has the tools.");
});

session.capabilities.event.subscribe((event: AepCapabilityEvent) => {
  const payload = AepPayloadJson.decode(event.payloadJson);
  if (payload === null) return;

  if (event.capability === "input.controller") {
    const button = AepControllerButton.fromMap(payload);
    // On the press, not the release: these are all one-shot tools, and acting on both would undo
    // twice per tap and switch mode back to where it started.
    if (button === null || !button.pressed) return;
    controllerGranted = true;
    press(button.controlId);
    return;
  }

  if (event.capability !== "input.touch") return;

  if (event.name === "ready") {
    adoptAspect(payload["aspectRatio"]);
    return;
  }
  // With mode "pointer" nothing else should arrive, so this is a guard rather than a filter: a
  // Companion that ignored the config would otherwise start drawing on gesture payloads too.
  if (event.name !== "touch") return;

  const touch = AepTouch.fromMap(payload);
  if (touch === null) return;
  touchGranted = true;
  touchEvents += 1;
  const point = {
    x: clamp01(touch.x / DRAW_RIGHT),
    y: clamp01(touch.y / DRAW_BOTTOM),
  };
  hint = point;
  if (touch.phase === "down") sketch.begin(touch.pointerId, point.x, point.y);
  else if (touch.phase === "move") sketch.move(touch.pointerId, point.x, point.y);
  else if (touch.phase === "up") sketch.end(touch.pointerId);
  else sketch.cancel(touch.pointerId);
});

function press(controlId: string): void {
  if (controlId === "mode-draw") setMode("draw");
  else if (controlId === "mode-pan") setMode("pan");
  else if (controlId === "undo") sketch.undo();
  else if (controlId === "clear") { sketch.clear(); say("Cleared."); }
  else if (controlId === "width") sketch.cycleWidth();
  else if (controlId.startsWith("ink-")) {
    const index = Number.parseInt(controlId.slice(4), 10);
    if (Number.isInteger(index)) sketch.pickColour(index);
  }
}

function setMode(mode: Mode): void {
  sketch.setMode(mode);
  say(mode === "pan" ? "Pan: drag to move over the sheet." : "");
}

/**
 * Takes the pad's real shape when a Companion reports it, and rebuilds the geometry around it.
 *
 * Only while the sheet is empty. Changing the viewport's aspect after strokes exist would move
 * every one of them relative to the paper, and the ready event arrives at grant time - before any
 * contact - so in practice the guard never bites. It is here because "in practice" is not a
 * guarantee, and silently relocating someone's drawing is worse than keeping a wrong aspect.
 */
function adoptAspect(value: unknown): void {
  if (typeof value !== "number" || !Number.isFinite(value) || value <= 0) return;
  if (aspectIsMeasured || sketch.strokeCount > 0) return;
  padAspect = value;
  aspectIsMeasured = true;
  sketch = buildSketch(padAspect);
}

/**
 * The same tools on the keyboard, because `input.controller` is declared optional and that has to
 * be true rather than merely written down: a phone that refuses the palette leaves a sample that
 * can draw in one colour and never clear, unless the laptop can do it.
 */
addEventListener("keydown", (event) => {
  if (event.key === "d") setMode("draw");
  else if (event.key === "p") setMode("pan");
  else if (event.key === "u") sketch.undo();
  else if (event.key === "c") { sketch.clear(); say("Cleared."); }
  else if (event.key === "w") sketch.cycleWidth();
  else if (event.key === "+" || event.key === "=") sketch.zoomBy(1.4);
  else if (event.key === "-") sketch.zoomBy(1 / 1.4);
  else if (event.key >= "1" && event.key <= "4") sketch.pickColour(Number(event.key) - 1);
  else return;
  event.preventDefault();
});

function say(message: string): void {
  ui.message.textContent = message;
  ui.message.hidden = message.length === 0;
}

function clamp01(value: number): number {
  return Math.min(1, Math.max(0, value));
}

function fitCanvas(): void {
  const ratio = Math.min(2, devicePixelRatio || 1);
  const box = ui.canvas.getBoundingClientRect();
  ui.canvas.width = Math.max(320, Math.round(box.width * ratio));
  ui.canvas.height = Math.max(240, Math.round(box.height * ratio));
}
addEventListener("resize", fitCanvas);
fitCanvas();

const experience = new WebExperienceHost(session, () => {
  const snapshot = sketch.snapshot();
  renderer.draw(snapshot, ui.canvas.width, ui.canvas.height, PALETTE, hint);
  ui.mode.textContent = snapshot.mode === "pan" ? "Pan" : "Draw";
  ui.tally.textContent = touchGranted
    ? `${snapshot.strokes.length} strokes · ${snapshot.zoom.toFixed(2)}x · ${touchEvents} touches`
    : "waiting for the phone's touch surface";
});

experience.start();

/**
 * Stop cleanly when the page goes away, and when whoever embedded it says to.
 *
 * The site runs this in an iframe and ends a demo by posting a message in; without the listener the
 * AEP session and its Gateway socket outlive the demo the visitor thought they had finished.
 */
let hasStopped = false;
function stopExperience(): void {
  if (hasStopped) return;
  hasStopped = true;
  experience.stop();
}
addEventListener("pagehide", stopExperience, { once: true });
addEventListener("beforeunload", stopExperience, { once: true });
addEventListener("message", (event: MessageEvent) => {
  if (event.origin !== location.origin) return;
  const type = (event.data as { type?: string } | null)?.type;
  if (type === "ambotouchdraw:stop" || type === "ambo:stop") stopExperience();
});
