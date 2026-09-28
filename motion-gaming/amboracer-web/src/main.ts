import {
  Aep, AepExperienceDefinition, AepConnectionState, AepOrientationFrame, AepTouch,
  type AepCapabilityEvent, type AepSession,
} from "@ambokit/aep-core";
import { AmboKitWebHost, AepPayloadJson } from "@ambokit/aep-host";
import { WebExperienceHost } from "@ambokit/aep-web";
import { Race, raceConfig } from "../rules/race.js";
import { Steering, steeringConfig } from "../rules/steering.js";
import { RaceRenderer } from "./render.js";

/**
 * AmboRacer: steer with a phone, play in a browser.
 *
 * The whole point of the sample is the seam, so it is worth naming what is and is not here. This
 * file owns the page: a canvas, a join screen, a frame loop. `rules/` owns the game and names
 * none of those. AEP owns the session, the negotiation and the phone - there is no WebSocket in
 * this file, no message type, and no mention of the Gateway beyond its address.
 *
 * Two capabilities are declared, and what the experience does when one is refused is the thing
 * worth watching: without `motion.orientation` there is no steering and the sample says so
 * plainly; without `input.touch` you can still play, you just start the race with the keyboard.
 * Declaring is asking, never assuming (ADR-0002 rule L16).
 */

const GATEWAY = resolveGateway();

/**
 * Where the Gateway lives, in order: an explicit `?gateway=`, the script tag's
 * `data-ambo-gateway`, then the page's own origin.
 *
 * Configurable because on the web the Gateway is hosted with the page rather than embedded in
 * the app, so one built copy of this sample has to be droppable onto any site. Same-origin is the
 * default because that is the deployment AmboKit ships an nginx config for.
 */
function resolveGateway(): string {
  const fromQuery = new URLSearchParams(location.search).get("gateway");
  if (fromQuery) return fromQuery;
  const tag = document.currentScript as HTMLScriptElement | null;
  const fromTag = tag?.dataset["amboGateway"]
    ?? document.querySelector<HTMLElement>("[data-ambo-gateway]")?.dataset["amboGateway"];
  return fromTag || location.origin;
}

const ui = {
  join: element("join"),
  qr: element<HTMLImageElement>("qr"),
  joinUrl: element("joinUrl"),
  joinState: element("joinState"),
  centring: element("centring"),
  centringBar: element("centringBar"),
  hud: element("hud"),
  lives: element("lives"),
  distance: element("distance"),
  message: element("message"),
  diagnostics: element("diagnostics"),
  canvas: element<HTMLCanvasElement>("stage"),
};

function element<T extends HTMLElement = HTMLElement>(id: string): T {
  const found = document.getElementById(id);
  if (found === null) throw new Error(`the page is missing #${id}`);
  return found as T;
}

const context = ui.canvas.getContext("2d");
if (context === null) throw new Error("this browser has no 2D canvas");

const renderer = new RaceRenderer(context);
const race = new Race(raceConfig());
const steering = new Steering(steeringConfig());
let steeringGranted = false;
let lastPhase: string = "";

const host = new AmboKitWebHost({ gatewayBaseUrl: GATEWAY });
const session: AepSession = Aep.start(
  new AepExperienceDefinition({
    id: "com.ambokit.aep.amboracer",
    // Asked for, not assumed. The Companion decides, and the player decides after that.
    capabilities: ["motion.orientation", "input.touch"],
    // The car does not need a calibrated body; it needs a level phone, and Steering captures that
    // itself. Setting this true would park the session in CALIBRATING waiting for a pose that
    // this experience never requests.
    requiresCalibration: false,
  }),
  host,
);

session.joinChanged.subscribe((join) => {
  // joinInfo.qrUrl is a link to an image the Gateway serves. Safe here, where the Gateway is the
  // Node one and does serve /qr.svg - and deliberately backed by the URL in text, because AEP's
  // EMBEDDED Gateway advertises the same field while serving no such route, which put a broken
  // image on a television. A QR that will not load must still leave something a person can type.
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

session.player.connected.subscribe(() => { ui.join.hidden = true; });
session.player.disconnected.subscribe(() => {
  ui.join.hidden = false;
  steering.recentre();
});

session.capabilities.changed.subscribe(() => {
  // Whether AEP has a typed model is decided by the registry, not by the participant. A
  // capability this build predates still appears here, which is what makes discovery meaningful.
  const advertised = session.capabilities.available.map((d) => d.id).join(", ");
  console.log(`[amboracer] companion advertises: ${advertised || "nothing yet"}`);
});

session.error.subscribe((error) => {
  if (error.code === "capability_denied") {
    say(error.message.includes("motion.orientation")
      ? "Steering needs the phone's tilt. Allow it on the phone and scan again."
      : "That was declined on the phone - the race still works.");
  }
});

// Neither of these has a typed AEP event, so both arrive on the generic path and are read with
// the generated models. That is the discovery claim in one line: an experience consumes a
// capability AEP has no bespoke handling for, using types generated from the same schema the
// Companion produces its payloads from.
session.capabilities.event.subscribe((event: AepCapabilityEvent) => {
  const payload = AepPayloadJson.decode(event.payloadJson);
  if (payload === null) return;

  if (event.capability === "motion.orientation") {
    const frame = AepOrientationFrame.fromMap(payload);
    if (frame === null) return;
    steeringGranted = true;
    steering.observe(frame);
    return;
  }

  if (event.capability === "input.touch") {
    const touch = AepTouch.fromMap(payload);
    if (touch !== null && touch.phase === "down") startOrRestart();
  }
});

function startOrRestart(): void {
  if (!steering.isCentred) return;
  if (race.snapshot.phase !== "racing") {
    race.start();
    say("");
  }
}

addEventListener("keydown", (event) => {
  if (event.key === " " || event.key === "Enter") { event.preventDefault(); startOrRestart(); }
  if (event.key.toLowerCase() === "c") steering.recentre();
});

function say(message: string): void {
  ui.message.textContent = message;
  ui.message.hidden = message.length === 0;
}

function fitCanvas(): void {
  const ratio = Math.min(2, devicePixelRatio || 1);
  const box = ui.canvas.getBoundingClientRect();
  ui.canvas.width = Math.max(320, Math.round(box.width * ratio));
  ui.canvas.height = Math.max(240, Math.round(box.height * ratio));
}
addEventListener("resize", fitCanvas);
fitCanvas();

/**
 * The frame clock, and the one call an experience owes AEP.
 *
 * pumpStreamLiveness is how a stream that has stopped arriving gets noticed: every other AEP
 * signal is raised by something happening, and an absence is raised by nobody. The adapter asks
 * on each frame, and pauses both itself and the session when the tab is hidden.
 */
const experience = new WebExperienceHost(session, (deltaMs) => {
  const snapshot = steering.isCentred
    ? race.tick(deltaMs, steering.value)
    : race.snapshot;

  renderer.draw(snapshot, ui.canvas.width, ui.canvas.height);

  ui.centring.hidden = !session.player.isConnected || steering.isCentred;
  ui.centringBar.style.width = `${Math.round(steering.centringProgress * 100)}%`;
  ui.hud.hidden = !steering.isCentred;
  ui.lives.textContent = "♥".repeat(Math.max(0, snapshot.lives));
  ui.distance.textContent = `${Math.round(snapshot.distance)} m`;

  if (snapshot.phase !== lastPhase) {
    lastPhase = snapshot.phase;
    if (snapshot.phase === "crashed") {
      say(`${Math.round(snapshot.distance)} metres. Tap the phone to race again.`);
    } else if (snapshot.phase === "waiting") {
      say("Tap the phone to start.");
    }
  }

  // Deliberately on screen. Which angle steers is reasoned from Android's sensor convention and
  // has never been checked against a real Companion, so the first session with a phone is the
  // measurement: tilt left and watch whether raw moves and which way steer goes.
  ui.diagnostics.textContent = steeringGranted
    ? `roll ${steering.rawDeg.toFixed(1)}° · centre ${steering.neutralDeg?.toFixed(1) ?? "—"}° `
      + `· steer ${steering.value.toFixed(2)} · ${Math.round(snapshot.speed * 100) / 100} rl/s`
    : "waiting for motion.orientation";
});

experience.start();
say("Scan the code with the AmboKit Companion.");
