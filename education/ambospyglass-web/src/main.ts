import {
  Aep, AepConnectionState, AepControllerButton, AepExperienceDefinition, AepOrientationFrame,
  AepStreamState, AepTouchGesture,
  type AepCapabilityEvent, type AepControllerLayout, type AepSession,
} from "@ambokit/aep-core";
import { AmboKitWebHost, AepPayloadJson } from "@ambokit/aep-host";
import { WebExperienceHost } from "@ambokit/aep-web";
import {
  Hunt, LANGUAGES, aimConfig, buildScene, doesText, promptText, sceneConfig,
  type Lexicon, type Scene,
} from "../rules/hunt.js";
import { SpyglassRenderer, SWATCH, iconCanvas } from "./render.js";

/**
 * AmboSpyglass: the phone is a spyglass, the browser is the crowd.
 *
 * This is the sample for **motion.orientation as a pointing instrument**. AmboRacer already uses
 * the same capability to steer, and the difference between them is the point: steering wants a
 * single axis mapped to a rate, while aiming wants two axes mapped to a POSITION that stays put
 * when the phone does. The second is the harder claim and the one a licensee would want to see.
 *
 * The phone is not pointing at the screen and cannot be. Orientation says which way the device is
 * facing; nothing in the protocol says where the screen is in the room. So `recentre` pins "this
 * facing" to "the lens is here", and the mapping is absolute from that anchor - point the same way
 * twice and you land in the same place, which is what makes "I already searched there" mean
 * anything in a search game.
 *
 * Two capabilities, two jobs. Orientation does the coarse hunt, where hand tremor is invisible;
 * touch makes the precise claim, where it would not be. Neither could do the other half well.
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

/**
 * The palette, declared by the experience rather than built into the Companion.
 *
 * Nothing in the phone knows what "recentre" means; it is told where to put a button and reports
 * which one was pressed. Four controls, down the right where a thumb reaches without covering the
 * surface that has to stay free for the claiming tap.
 */
const CONTROLS = [
  { id: "recentre", label: "Centre", x: 0.93, y: 0.11 },
  { id: "say", label: "Say", x: 0.93, y: 0.31 },
  { id: "hint", label: "Hint", x: 0.93, y: 0.51 },
  { id: "lang", label: "Lang", x: 0.93, y: 0.71 },
  { id: "next", label: "New", x: 0.93, y: 0.91 },
] as const;

const PALETTE_LAYOUT: AepControllerLayout = {
  controls: CONTROLS.map((control) => ({
    id: control.id, type: "button", label: control.label, x: control.x, y: control.y, size: "small",
  })),
};

/**
 * Hoisted out of the definition body on purpose, and not for tidiness.
 *
 * `tools/check_manifests.py` reads every string literal inside the `AepExperienceDefinition({...})`
 * body and treats all but the first as capability ids, so an inline `mode: "gestures"` is reported
 * as an undeclared capability called "gestures". AmboTouchDraw hit the same thing with "pointer".
 * The checker wants fixing - it should read the `capabilities` array - and until it is, any config
 * carrying a string value lives out here.
 */
const TOUCH_TUNING = { mode: "gestures" };

/**
 * `motion.orientation` is configured with nothing, following AmboRacer and for its reason: it is
 * continuous, so AEP watches it by default, and the provider already registers the game rotation
 * vector at 30 Hz with `reference: "game"`. Game reference is right here anyway - the lens is
 * anchored by recentre, so magnetic north would be a dependency bought for nothing.
 */

const ui = {
  frame: query<HTMLElement>(".ambospyglass"),
  canvas: element<HTMLCanvasElement>("stage"),
  join: element("join"),
  qr: element<HTMLImageElement>("qr"),
  joinUrl: element("joinUrl"),
  joinState: element("joinState"),
  sentence: element("sentence"),
  chips: element("chips"),
  gloss: element("gloss"),
  clock: element("clock"),
  learning: element("learning"),
  message: element("message"),
  done: element("done"),
  praise: element("praise"),
  took: element("took"),
  does: element("does"),
  doesGloss: element("doesGloss"),
  again: element<HTMLButtonElement>("again"),
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

/** The language being learned, and the one already known. English is not privileged: see hunt.ts. */
let target: Lexicon = LANGUAGES[1] ?? LANGUAGES[0] as Lexicon;
let helper: Lexicon = LANGUAGES[0] as Lexicon;

let scene: Scene = buildScene();
let hunt = new Hunt(scene, aimConfig());
let rounds = 0;
/** While the found-it panel is up the hunt is over, so a stray tap cannot score against it. */
let settled = false;
let best: number | null = null;
let orientationSeen = 0;

const renderer = new SpyglassRenderer(ui.canvas);

const host = new AmboKitWebHost({ gatewayBaseUrl: GATEWAY });
const session: AepSession = Aep.start(
  new AepExperienceDefinition({
    id: "com.ambokit.aep.ambospyglass",
    capabilities: ["motion.orientation", "input.touch", "input.controller"],
    capabilityConfig: {
      // Verbs, not contacts - the opposite of AmboTouchDraw, and for the opposite reason. A claim
      // is a tap and the fallback sweep is a drag; raw pointers would mean re-deriving both from
      // down/move/up, which is work the Companion has already done.
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
});

session.player.connected.subscribe(() => {
  ui.join.hidden = true;
  say("Hold the phone comfortably and press Centre.");
});

session.player.disconnected.subscribe(() => {
  ui.join.hidden = false;
  say("Phone left. The scene is still here - scan again to carry on.");
});

/**
 * A frozen lens and a frozen lens look identical, which is why this is wired.
 *
 * Orientation is continuous: a phone lying still on a table still reports thirty frames a second,
 * so silence is the transport, never the player holding steady. Without this the lens would simply
 * stop and the person would think the game had broken rather than the connection.
 */
session.streamChanged.subscribe((change) => {
  if (change.capability !== "motion.orientation") return;
  if (change.state === AepStreamState.STALE) {
    say(`No data from the phone for ${(change.silentForMs / 1000).toFixed(1)}s.`);
  } else {
    say("");
  }
});

session.error.subscribe((error) => {
  if (error.code !== "capability_denied") return;
  if (error.message.includes("motion.orientation")) {
    say("AmboSpyglass needs the phone's orientation to move the lens. Allow it and scan again.");
  } else if (error.message.includes("input.touch")) {
    say("Without the touch surface there is no way to claim what you have found.");
  } else {
    // The palette is optional and its loss is survivable: the lens anchors itself on the first
    // frame, so the only thing actually lost is re-anchoring later.
    say("The buttons were declined - you can still play, but you cannot recentre.");
  }
});

session.capabilities.event.subscribe((event: AepCapabilityEvent) => {
  const payload = AepPayloadJson.decode(event.payloadJson);
  if (payload === null) return;

  if (event.capability === "motion.orientation") {
    const frame = AepOrientationFrame.fromMap(payload);
    if (frame === null) return;
    orientationSeen += 1;
    hunt.orient(frame.yawDeg, frame.pitchDeg, frame.accuracy ?? null);
    return;
  }

  if (event.capability === "input.controller") {
    const button = AepControllerButton.fromMap(payload);
    // On the press, not the release: these are one-shot tools, and acting on both would skip two
    // rounds per tap.
    if (button === null || !button.pressed) return;
    press(button.controlId);
    return;
  }

  if (event.capability !== "input.touch" || event.name !== "gesture") return;
  const gesture = AepTouchGesture.fromMap(payload);
  if (gesture === null) return;
  if (gesture.type === "tap") {
    claim();
  } else if (gesture.type === "drag") {
    // NOT inverted. AmboTouchDraw's drag moves the paper under a fixed view, so its deltas are
    // subtracted; here the finger moves the LENS, so dragging right looks right. Copying the
    // subtraction across was the single most-reported thing about the first build.
    hunt.nudge((gesture.dx ?? 0) * 0.9, (gesture.dy ?? 0) * 0.9);
  }
});

function press(controlId: string): void {
  if (controlId === "recentre") { hunt.recentre(); say("Centred."); }
  else if (controlId === "say") speak(promptText(target, scene.target));
  else if (controlId === "hint") {
    const hint = promptText(helper, scene.target);
    ui.gloss.textContent = hint;
    // In the KNOWN language, not the one being learned - a hint read aloud in the language you
    // cannot yet parse is not a hint.
    speakIn(helper, hint);
  } else if (controlId === "lang") cycleLanguage();
  else if (controlId === "next") newRound();
}

/**
 * Cycles the language being learned, under the same crowd and the same target.
 *
 * The picture does not move and only the words do, which is the clearest demonstration there is of
 * what a lexicon is - and it is the thing the sample exists to show.
 */
function cycleLanguage(): void {
  const at = LANGUAGES.indexOf(target);
  target = LANGUAGES[(at + 1) % LANGUAGES.length] ?? target;
  if (target === helper) target = LANGUAGES[(LANGUAGES.indexOf(target) + 1) % LANGUAGES.length] ?? target;
  showPrompt();
  speak(promptText(target, scene.target));
  say(target.name);
}

function newRound(): void {
  scene = buildScene(sceneConfig(), seededFromClock());
  hunt = new Hunt(scene, aimConfig());
  rounds += 1;
  settled = false;
  ui.done.hidden = true;
  showPrompt();
  speak(promptText(target, scene.target));
}

/** A different crowd each round, and reproducible within one if the seed is pinned for a demo. */
function seededFromClock(): () => number {
  let state = ((Date.now() ^ (rounds * 7919)) >>> 0) || 1;
  return () => {
    state ^= state << 13; state >>>= 0;
    state ^= state >> 17;
    state ^= state << 5; state >>>= 0;
    return state / 0x100000000;
  };
}

/**
 * The sentence, and the target's picture beside its word.
 *
 * Both, always. The sentence carries the grammar - which adjective ending, which postposition -
 * and the picture carries the noun, so a child who cannot read the word yet is still playing
 * rather than waiting to be able to.
 */
function showPrompt(): void {
  ui.sentence.textContent = promptText(target, scene.target);
  ui.gloss.textContent = "";
  ui.learning.textContent = target.name;
  const subjectWord = target.subjects[scene.target.subject]?.word ?? scene.target.subject;
  const traitWord = target.traits[scene.target.trait]?.word ?? scene.target.trait;
  ui.chips.replaceChildren(
    chip("subject", scene.target.subject, "", subjectWord),
    chip("trait", scene.target.trait, SWATCH[scene.target.colour] ?? "#888888", traitWord),
  );
}

function chip(kind: "subject" | "trait", id: string, paint: string, word: string): HTMLElement {
  const el = document.createElement("span");
  el.className = "chip";
  el.appendChild(iconCanvas(kind, id, paint));
  const text = document.createElement("span");
  text.textContent = word;
  el.appendChild(text);
  // The word on its own, as often as they like, while looking at the picture of it.
  el.addEventListener("click", () => speak(word));
  return el;
}

/**
 * Says it in the language being learned, if this machine can.
 *
 * Worth checking rather than assuming: hi-IN and kn-IN voices are not installed everywhere, and a
 * browser with no matching voice stays silent instead of erroring - so the game would appear to
 * work while losing half of what it teaches.
 */
function speak(text: string): void { speakIn(target, text); }

function speakIn(lexicon: Lexicon, text: string): void {
  if (text === "") return;
  try {
    const utterance = new SpeechSynthesisUtterance(text);
    utterance.lang = lexicon.code;
    utterance.rate = 0.85;
    const base = lexicon.code.toLowerCase().split("-")[0] ?? "";
    const voice = speechSynthesis.getVoices().find((v) => v.lang.toLowerCase().replace("_", "-").startsWith(base));
    if (voice !== undefined) utterance.voice = voice;
    speechSynthesis.cancel();
    speechSynthesis.speak(utterance);
  } catch {
    /* A machine with no voices is not a reason to stop playing. */
  }
}

function claim(): void {
  if (settled) return;
  const verdict = hunt.claim();
  if (verdict.kind === "empty") { say("Nobody there."); return; }
  if (verdict.kind === "correct") {
    settled = true;
    const took = hunt.snapshot().elapsedMs;
    const beaten = best === null || took < best;
    if (beaten) best = took;
    ui.praise.textContent = target.praise;
    ui.took.textContent = `${(took / 1000).toFixed(1)}s` + (beaten ? " - best yet" : ` - best ${((best ?? took) / 1000).toFixed(1)}s`);
    ui.does.textContent = doesText(target, scene.target.subject);
    ui.doesGloss.textContent = doesText(helper, scene.target.subject);
    ui.again.textContent = target.again;
    ui.done.hidden = false;
    speak(`${target.praise} ${doesText(target, scene.target.subject)}`);
    return;
  }
  // Not "wrong" but WHICH WORD was lost. That is the whole teaching payload, and it is free.
  const names: Record<string, string> = { subject: "the person", trait: "the item", colour: "the colour" };
  const missed = verdict.missed.map((clause) => names[clause] ?? clause);
  say(missed.length === 3 ? "That is somebody else entirely."
    : missed.length === 2 ? `Not quite - ${missed[0]} and ${missed[1]}.`
      : `Not quite - ${missed[0]}.`);
  ui.gloss.textContent = promptText(helper, verdict.character);
}

function say(text: string): void {
  ui.message.textContent = text;
  ui.message.hidden = text === "";
}

ui.again.addEventListener("click", newRound);
ui.canvas.addEventListener("pointerup", claim);
window.addEventListener("resize", () => renderer.resize());

const frames = new WebExperienceHost(session, (deltaMs) => {
  hunt.tick(deltaMs);
  renderer.draw(scene, hunt.snapshot(), target);
  const snapshot = hunt.snapshot();
  ui.clock.textContent = `${(snapshot.elapsedMs / 1000).toFixed(1)}s`
    + (orientationSeen === 0 ? "  ·  waiting for the phone" : snapshot.aiming ? "" : "  ·  press Centre");
});

renderer.resize();
showPrompt();
frames.start();

window.addEventListener("pagehide", () => frames.stop());
