import {
  Aep, AepExperienceDefinition, AepConnectionState, AepControllerButton, AepOrientationFrame,
  AepStreamState, AepTouchGesture,
  type AepCapabilityEvent, type AepControllerLayout, type AepSession,
} from "@ambokit/aep-core";
import { AmboKitWebHost, AepPayloadJson } from "@ambokit/aep-host";
import { WebExperienceHost } from "@ambokit/aep-web";
import { Race, raceConfig } from "../rules/race.js";
import { Steering, steeringConfig, type SteeringAxis } from "../rules/steering.js";
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
/**
 * Which angle steers, changeable while the game runs.
 *
 * Not a setting anyone should ship - it is a measuring instrument. Which axis a phone steers on
 * is reasoned from Android's sensor convention and has never been checked against a real
 * Companion, and the first version of these diagnostics printed only the axis I had guessed,
 * which is exactly the number that cannot tell you whether the guess was right. All three are
 * printed now, and 1/2/3 switches between them, so one tilt answers the question.
 */
let axis: SteeringAxis = "yaw";
let steering = new Steering(steeringConfig({ axis }));
let latest = { yawDeg: 0, pitchDeg: 0, rollDeg: 0 };

function useAxis(next: SteeringAxis): void {
  axis = next;
  steering = new Steering(steeringConfig({ axis }));
  say(`Steering on ${next}. Hold the phone still.`);
}
let steeringGranted = false;
let lastPhase: string = "";
/**
 * The phase message and the phone alert share one element, and the alert wins.
 *
 * Two strings rather than one because the frame loop rewrites the phase message on every phase
 * change, and "Tap the phone to start" overwriting "the phone is gone" is precisely how this
 * sample would go back to saying nothing is wrong.
 *
 * Declared here with the rest of the session's state rather than beside the function that paints
 * them: the subscriptions below are written earlier in the file, and a `let` still in its
 * temporal dead zone would throw from inside an event handler. That is how the first run of this
 * sample against a real phone died - everything worked, then a handler touched something that
 * was not there yet.
 */
let alertText = "";
let alertRank = 0;
let phaseText = "";
let connectionText = "Waiting for a phone…";

/**
 * Which alert outranks which, because two of them fire together and the vaguer one arrives last.
 *
 * On a peer-left the host raises ParticipantLeft and THEN SessionChanged(RECONNECTING), so
 * last-writer-wins shows "reconnecting" about a phone that has gone. Worse, the host never
 * escalates that to REJOIN_REQUIRED on a peer-left: reconnect attempts are driven by its OWN
 * socket closing, and its socket is fine. The screen would promise a reconnection forever, for a
 * phone that is not coming back.
 */
const ALERT_TRANSPORT = 1;    // the link is unhappy, and may well recover by itself
const ALERT_PARTICIPANT = 2;  // the phone itself is gone; only a person can fix that
// Counted and shown, so "nothing is happening" can be told apart from "something is happening
// and I am reading it wrong" without opening a console.
let orientationFrames = 0;
let touchEvents = 0;

/**
 * The drive-mode buttons, on the phone.
 *
 * This is the experience dictating its own controls. input.controller is nothing without a layout:
 * the Companion refuses the grant outright if none arrives, so these four either appear as written
 * or the capability is declined with a reason. There is no state where it half works.
 *
 * Typed as AepControllerLayout on purpose. The model is generated from the same schema the
 * Companion validates against, so a misspelt field or a size that is not one of small/medium/large
 * fails to compile here rather than arriving as an `invalid_config` refusal on a phone.
 *
 * Coordinates are fractions of the PAD, not the screen - the pad being whatever is left below the
 * Companion's streaming strip. Stacked down the right edge so a right thumb reaches them without
 * crossing the pad, and `small` so they leave the rest of it free: everything outside a button
 * falls through to input.touch, where a tap pauses.
 *
 * Centre sits lower than the three modes because it is a different kind of action - the modes
 * choose what steers, Centre says "this is straight ahead now". Pressing a mode button re-centres
 * too, as a side effect of rebuilding Steering, which is what made those three usable as an
 * accidental recovery control before this button existed.
 */
const DRIVE_CONTROLS: AepControllerLayout = {
  // `orientation` is deliberately absent. The Companion parses it and nothing acts on it - the pad
  // stays in whatever orientation the phone is held - so any value here is decoration, and the one
  // originally written ("portrait") was decoration that happened to be wrong: the game is played
  // landscape. Better to say nothing than to state something no code honours.
  controls: [
    // Spacing measured rather than guessed. The Companion sizes a control from the SHORT side of the
    // pad - min(width, height) * 0.11, times 0.7 for `small` - and hit-tests at 1.2x that radius.
    // On a 2293x1020 landscape pad that is a 188px hit diameter, so the 0.14 spacing first written
    // here put the circles on top of each other and a tap near Yaw could register as Roll. 0.22
    // clears it in landscape and is comfortable in portrait, where the short side is the width and
    // everything is further apart anyway.
    { id: "yaw", type: "button", label: "Yaw", x: 0.86, y: 0.10, size: "small" },
    { id: "roll", type: "button", label: "Roll", x: 0.86, y: 0.32, size: "small" },
    { id: "pitch", type: "button", label: "Pitch", x: 0.86, y: 0.54, size: "small" },
    { id: "centre", type: "button", label: "Centre", x: 0.86, y: 0.80, size: "small" },
  ],
};

const host = new AmboKitWebHost({ gatewayBaseUrl: GATEWAY });
const session: AepSession = Aep.start(
  new AepExperienceDefinition({
    id: "com.ambokit.aep.amboracer",
    // Asked for, not assumed. The Companion decides, and the player decides after that.
    capabilities: ["motion.orientation", "input.touch", "input.controller"],
    capabilityConfig: { "input.controller": { layout: DRIVE_CONTROLS } },
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
  connectionText =
    state === AepConnectionState.CONNECTED ? "Phone connected."
      : state === AepConnectionState.RECONNECTING ? "Phone dropped - reconnecting…"
        : state === AepConnectionState.REJOIN_REQUIRED ? "Scan again to rejoin."
          : "Waiting for a phone…";

  if (state === AepConnectionState.RECONNECTING) {
    phoneGone("Phone dropped - reconnecting…", ALERT_TRANSPORT);
  } else if (state === AepConnectionState.REJOIN_REQUIRED) {
    phoneGone("Scan the code again to rejoin.", ALERT_PARTICIPANT);
  } else if (state === AepConnectionState.CONNECTED) {
    phoneBack();
  }
  paint();
});

session.player.connected.subscribe(() => { ui.join.hidden = true; phoneBack(); });
session.player.disconnected.subscribe(() => {
  ui.join.hidden = false;
  // Not "reconnecting": the phone has left and the host will never say so itself. See ALERT_*.
  phoneGone("Phone left. Scan the code again to rejoin, or press space for the keyboard.",
    ALERT_PARTICIPANT);
});

/**
 * The phone stopped sending, whatever the link says.
 *
 * This is the signal that survives a Gateway which never told us, and it is the reason the
 * sample watches frames at all rather than trusting the transport. A peer-left is an edge: the
 * Gateway publishes it once, over Redis pub/sub, which keeps nothing. If this host happens to be
 * between sockets when it fires - which is what happens when the serverless Gateway's 300s
 * function limit ends the host's own socket a moment before the phone gives up - there is no
 * subscriber and the message is gone. The host reconnects to a session whose phone has left and
 * is told nothing, for as long as the page stays open. An absence cannot be lost that way,
 * because nobody has to deliver it; AEP is already counting.
 *
 * Nothing is configured here on purpose: motion.orientation is continuous, so AEP watches it by
 * default at two seconds. input.touch is episodic and exempt, which is right: a player who is not
 * tapping is not a fault.
 *
 * What this measures is frame ARRIVAL, never a change in value, and the difference is the whole
 * reason the signal is usable. OrientationProvider registers TYPE_GAME_ROTATION_VECTOR at 30 Hz
 * and emits on every sensor callback with no deadband - a continuous Android sensor reports at
 * its requested rate whether or not the device moves. A phone lying still on a table still sends
 * thirty frames a second, so two seconds of silence is sixty consecutive missing frames and
 * cannot be produced by a player choosing not to tilt. The message says "no data from the phone"
 * rather than "no tilt" for that reason: the first is what was measured, the second is a guess
 * about the player that would be wrong during ordinary play.
 *
 * The threshold is inherited and has not been measured HERE. AEP's two seconds is justified
 * against pose over loopback on a television, which is not orientation over a mobile network. The
 * on-screen frame counter is what settles it: a pause during ordinary play with the counter still
 * climbing would mean the threshold is too tight for cellular, not that the phone left.
 */
session.streamChanged.subscribe((change) => {
  if (change.capability !== "motion.orientation") return;
  if (change.state === AepStreamState.STALE) {
    phoneGone(`No data from the phone for ${(change.silentForMs / 1000).toFixed(1)}s.`
      + " Press space to keep racing on the keyboard.", ALERT_TRANSPORT);
  } else {
    phoneBack();
  }
});

/**
 * One place that says the phone is not steering any more, and stops the car.
 *
 * Stopping it is not decoration. Steering holds its last reading and isCentred stays true after
 * the phone goes, so without this the car keeps turning on a frozen tilt, into a wall nobody is
 * driving at, while the screen says nothing. recentre() drops that reading, which both hands the
 * car back to the keyboard and forces a returning phone to be re-centred - correct after any gap,
 * because a phone that has been away has been put down, pocketed or carried.
 *
 * Idempotent: three independent sources raise this and they routinely raise it together.
 */
function phoneGone(message: string, rank: number = ALERT_TRANSPORT): void {
  if (rank < alertRank) return;
  if (race.snapshot.phase === "racing") race.togglePause();
  steering.recentre();
  alertText = message;
  alertRank = rank;
  paint();
}

function phoneBack(): void {
  if (alertText.length === 0) return;
  alertText = "";
  alertRank = 0;
  paint();
}

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
    orientationFrames += 1;
    latest = { yawDeg: frame.yawDeg, pitchDeg: frame.pitchDeg, rollDeg: frame.rollDeg };
    steering.observe(frame);
    return;
  }

  if (event.capability === "input.controller") {
    const button = AepControllerButton.fromMap(payload);
    // Acted on the press, ignored on the release: these are mode switches rather than held
    // controls, and reacting to both would switch twice per tap.
    if (button === null || !button.pressed) return;
    if (button.controlId === "yaw" || button.controlId === "roll" || button.controlId === "pitch") {
      useAxis(button.controlId);
    } else if (button.controlId === "centre") {
      steering.recentre();
      say("Hold the phone level. This is straight ahead.");
    }
    return;
  }

  if (event.capability === "input.touch") {
    touchEvents += 1;
    /**
     * One tap, one action - which took a rewrite, because it was two.
     *
     * input.touch defaults to mode "both", so a single tap emits THREE events: a touch going down,
     * a touch coming up, and a tap gesture. This used to act on the first and the third, so a tap
     * paused the game and then immediately resumed it. The net effect was nothing at all, which is
     * a hard bug to see: the control appeared dead rather than wrong.
     *
     * Dispatching on event.name is what makes this straightforward, and it was not possible when
     * this code was written - the host dropped the name and a consumer had to guess a payload by
     * its shape, which is how two shapes of the same tap both got acted on.
     *
     * The gesture is the right one to take, not the pointer. A pointer going down also begins every
     * drag and swipe, so pausing on it means the game stops the instant a player starts any
     * movement; the recogniser already tells tap and double_tap apart from those.
     */
    if (event.name !== "gesture") return;   // pointer events are counted above, nothing more
    const gesture = AepTouchGesture.fromMap(payload);
    if (gesture !== null && (gesture.type === "tap" || gesture.type === "double_tap")) {
      primaryAction();
    }
  }
});

/**
 * One control for everything: start, pause, resume, race again.
 *
 * The phone has one button - the whole screen - so the space bar does what a tap does. A player
 * who has to answer the door can stop the car with either.
 */
function primaryAction(): void {
  if (race.snapshot.phase === "racing" || race.snapshot.phase === "paused") {
    race.togglePause();
    say(race.snapshot.phase === "paused" ? "Paused. Tap, or press space, to carry on." : "");
    return;
  }
  startOrRestart();
}

function startOrRestart(): void {
  if (race.snapshot.phase === "racing") return;
  // Deliberately not gated on the phone being centred. It used to be, and that turned a tilt that
  // was not arriving into a game that said "tap to start" and then ignored every tap - the player
  // is told nothing is wrong and the thing they are told to do does nothing. The race runs; it
  // simply steers with whatever input is working.
  race.start();
  say("");
}

/**
 * The keyboard, which is not a fallback bolted on.
 *
 * A reference sample that only works when a phone, a Gateway and a wifi network all behave is a
 * sample nobody can open. Arrow keys steer and space starts, so the game is always playable and
 * the phone is the thing that makes it good - the same decision as Milo's remote, for the same
 * reason. It also isolates a fault: if the keyboard drives the car and the phone does not, the
 * game is fine and the capability is not.
 */
let keyboardSteer = 0;
addEventListener("keydown", (event) => {
  if (event.key === " " || event.key === "Enter") { event.preventDefault(); primaryAction(); }
  if (event.key === "ArrowLeft") { event.preventDefault(); keyboardSteer = -1; }
  if (event.key === "ArrowRight") { event.preventDefault(); keyboardSteer = 1; }
  if (event.key.toLowerCase() === "c") steering.recentre();
  if (event.key === "1") useAxis("yaw");
  if (event.key === "2") useAxis("roll");
  if (event.key === "3") useAxis("pitch");
});
addEventListener("keyup", (event) => {
  if (event.key === "ArrowLeft" && keyboardSteer < 0) keyboardSteer = 0;
  if (event.key === "ArrowRight" && keyboardSteer > 0) keyboardSteer = 0;
});

/** Fixed width, so a number changing sign does not make the whole line jump about. */
function pad(degrees: number): string {
  return degrees.toFixed(1).padStart(6, " ");
}

function say(message: string): void {
  phaseText = message;
  paint();
}

function paint(): void {
  const shown = alertText.length > 0 ? alertText : phaseText;
  ui.message.textContent = shown;
  ui.message.hidden = shown.length === 0;
  // And into the panel too, because the panel wins: `.panel` is later in the DOM with inset: 0,
  // so whenever the join screen is up it paints straight over #message - which is exactly when a
  // phone has gone, the one moment the alert has to be readable.
  ui.joinState.textContent = alertText.length > 0 ? alertText : connectionText;
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
  // Whichever input is live. The phone wins when it is centred, because that is the one the
  // sample is about; the keyboard keeps the game playable while it is not.
  const input = steering.isCentred ? steering.value : keyboardSteer;
  const snapshot = race.tick(deltaMs, input);

  renderer.draw(snapshot, ui.canvas.width, ui.canvas.height, input);

  ui.centring.hidden = !session.player.isConnected || steering.isCentred;
  ui.centringBar.style.width = `${Math.round(steering.centringProgress * 100)}%`;
  ui.hud.hidden = !steering.isCentred;
  ui.lives.textContent = "♥".repeat(Math.max(0, snapshot.lives));
  ui.distance.textContent = `${Math.round(snapshot.distance)} m`;

  if (snapshot.phase !== lastPhase) {
    lastPhase = snapshot.phase;
    if (snapshot.phase === "crashed") {
      say(`${Math.round(snapshot.distance)} metres. Tap the phone, or press space, to race again.`);
    } else if (snapshot.phase === "waiting") {
      say(session.player.isConnected
        ? "Tap the phone, or press space, to start."
        : "Press space to start, or scan the code to steer with a phone.");
    } else if (snapshot.phase === "racing") {
      say("");
    }
  }

  // Deliberately on screen. Which angle steers is reasoned from Android's sensor convention and
  // has never been checked against a real Companion, so the first session with a phone is the
  // measurement: tilt left and watch whether raw moves and which way steer goes.
  // All three angles, then what the chosen one is doing with them. The offset is the number that
  // explains a car pinned to one side: if it is past full lock the moment the phone moves, the
  // range is wrong; if it jumps by hundreds, the axis is wrapping and is the wrong one to read.
  const centre = steering.neutralDeg;
  ui.diagnostics.textContent = steeringGranted
    ? `y ${pad(latest.yawDeg)} p ${pad(latest.pitchDeg)} r ${pad(latest.rollDeg)}`
      + ` │ ${axis}[${axis === "yaw" ? 1 : axis === "roll" ? 2 : 3}] centre ${centre === null ? "—" : pad(centre)}`
      + ` off ${pad(steering.rawDeg - (centre ?? steering.rawDeg))} drift ${pad(steering.centreDriftDeg)}`
      + ` steer ${steering.value.toFixed(2)}`
      + ` │ ${orientationFrames}f ${touchEvents}t`
    : `no motion.orientation yet · ${touchEvents} touch · keyboard ${keyboardSteer}`;
});

experience.start();

/**
 * Stop cleanly when the page goes away, and when whoever embedded it says to.
 *
 * Without this a page that is closed or navigated away from leaves the session running: the
 * socket stays up until the Gateway times it out, the phone goes on sending, and the next visit
 * finds the role already taken. `pagehide` rather than `unload` because a browser restoring from
 * the back-forward cache never fires `unload`, and Safari treats a page with an `unload` handler
 * as ineligible for that cache at all.
 *
 * Both events, because neither is reliable alone across browsers, and `hasStopped` because
 * firing both is the normal case rather than the exception.
 *
 * The message listener is for embedding: a host page that swaps this demo out of view can stop
 * it without reloading. Origin-checked, so another window cannot end someone's session.
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
  if ((event.data as { type?: string } | null)?.type === "amboracer:stop") stopExperience();
});

say("Scan the code with the AmboKit Companion.");
