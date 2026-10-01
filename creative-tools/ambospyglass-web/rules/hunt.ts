/**
 * AmboSpyglass's rules: where the lens is looking, and what claiming it means.
 *
 * No DOM here, by design and by test - `tools/check_samples.py` reads `rules/*.ts` for DOM globals
 * and fails if it finds one. The renderer owns the canvas; this file owns the hunt.
 *
 * Two ideas everything else follows from.
 *
 * **The phone is not pointing at the screen.** It cannot: `motion.orientation` reports which way
 * the device is facing, and nothing in the protocol says where the screen is in the room. A
 * Wiimote needed a sensor bar for that. So the lens is anchored instead - `recentre()` pins "this
 * facing" to "the lens is here", and afterwards the mapping is absolute *relative to that anchor*.
 * Turning right pans right, and pointing the same way twice lands in the same place, which is what
 * makes "I already searched there" mean anything in a search game.
 *
 * **The distractors are the lesson.** A prompt names three things - a role, an accessory, and the
 * accessory's colour - and the crowd is generated so that every clause has near-misses that fail
 * on that clause alone. Recognising one word cannot win. See `buildScene`, which guarantees it
 * rather than hoping the random placement produced it.
 */

/* ------------------------------------------------------------------ the crowd */

/**
 * A prompt constrains exactly three things, and each one is a separate way to be wrong.
 *
 * Kept as a closed union rather than free strings because the whole diagnostic value of the game
 * is counting which clause a person missed: tapping the blue hat means the colour was lost, the
 * butcher in a red hat means the noun was. A string key would let a typo silently open a fourth
 * bucket that nothing ever reports.
 */
export type Clause = "role" | "accessory" | "colour";

export const CLAUSES: readonly Clause[] = ["role", "accessory", "colour"];

/** What the prompt describes and what the crowd is drawn from, as ids rather than words. */
export interface Combination {
  readonly role: string;
  readonly accessory: string;
  readonly colour: string;
}

export interface Character extends Combination {
  /** Stable within a scene, so a renderer can memoise per-character drawing. */
  readonly id: number;
  /** Position in scene units. The scene is far larger than the lens, which is the point. */
  readonly x: number;
  readonly y: number;
  /** 0..1, for drawing variety only - never for telling two characters apart in a prompt. */
  readonly pose: number;
}

export interface Scene {
  readonly width: number;
  readonly height: number;
  readonly characters: readonly Character[];
  readonly target: Character;
}

/* ------------------------------------------------------------------ the words */

/** A noun carries its article and gender, because the adjective has to agree with it. */
export interface Noun {
  readonly article: string;
  readonly word: string;
  readonly gender: "m" | "f";
}

/** Both forms, so agreement is data rather than a rule the renderer would have to know. */
export interface Adjective {
  readonly m: string;
  readonly f: string;
}

/**
 * The target language, kept out of the rules so the same game teaches Spanish or German.
 *
 * `code` is a BCP-47 tag for whatever speaks the prompt aloud on the renderer's side. The phone
 * never speaks: the room has to hear the language, and the room is looking at the screen.
 */
export interface Lexicon {
  readonly code: string;
  readonly find: string;
  /** The preposition that joins a person to what they are wearing or carrying. */
  readonly with: string;
  readonly roles: Readonly<Record<string, Noun>>;
  readonly accessories: Readonly<Record<string, Noun>>;
  readonly colours: Readonly<Record<string, Adjective>>;
  /** English, for the gloss a beginner is allowed to reveal. Keyed by the same ids. */
  readonly gloss: Readonly<Record<string, string>>;
}

/**
 * Assembles the prompt with the colour agreeing with the ACCESSORY, not the person.
 *
 * This is the grammar the game actually teaches, and it is why the colour attaches to the
 * accessory rather than to the role: "el panadero con la camisa roja" against "con el sombrero
 * rojo" puts agreement on the line in every single prompt, where a textbook puts it in one
 * exercise. A learner who ignores the ending still finds the right person - but they will have
 * read past the one thing the sentence was built to teach.
 */
export function promptText(lexicon: Lexicon, combination: Combination): string {
  const role = lexicon.roles[combination.role];
  const accessory = lexicon.accessories[combination.accessory];
  const colour = lexicon.colours[combination.colour];
  if (role === undefined || accessory === undefined || colour === undefined) {
    // An id with no word is a content bug, not a runtime condition to style around. Saying which
    // id is missing beats a sentence with a hole in it that a tester reports as "looks wrong".
    return `[no words for ${combination.role}/${combination.accessory}/${combination.colour}]`;
  }
  const agreed = accessory.gender === "f" ? colour.f : colour.m;
  return `${lexicon.find} ${role.article} ${role.word} ${lexicon.with} ${accessory.article} ${accessory.word} ${agreed}`;
}

/** The English the hint button reveals, assembled from the same ids. */
export function glossText(lexicon: Lexicon, combination: Combination): string {
  const parts = [combination.role, combination.accessory, combination.colour]
    .map((id) => lexicon.gloss[id] ?? id);
  return `the ${parts[0]} with the ${parts[2]} ${parts[1]}`;
}

/* ------------------------------------------------------------------ generating a scene */

export interface SceneConfig {
  readonly width: number;
  readonly height: number;
  /** Grid pitch in scene units. Characters sit in cells with jitter, so the crowd is not a table. */
  readonly cellSize: number;
  /**
   * An empty border the crowd is kept out of, and the reason it exists is not decoration.
   *
   * The crosshair cannot leave the scene by less than a lens radius - otherwise the lens would
   * hang off the edge and show paper-coloured nothing. So a character standing closer to the edge
   * than that **can never be put under the crosshair**, and a prompt naming one would be
   * unwinnable. Found by a test that aimed at a planted near-miss near the edge and hit its
   * neighbour instead.
   *
   * Keep this at or above `AimConfig.lensRadius`; `reachableMargin` below is the assertion, and
   * the two configs stay independent rather than one reaching into the other.
   */
  readonly margin: number;
  /** How far from its cell centre a character may stray, as a fraction of the cell. */
  readonly jitter: number;
  readonly roles: readonly string[];
  readonly accessories: readonly string[];
  readonly colours: readonly string[];
  /**
   * How many near-misses each clause gets: characters matching the target on the other two and
   * differing only here. Three is enough that stumbling on one is likely and that a person who
   * claims on a single word is likely to be wrong.
   */
  readonly decoysPerClause: number;
}

export function sceneConfig(overrides: Partial<SceneConfig> = {}): SceneConfig {
  return {
    width: 4680,
    height: 2700,
    cellSize: 180,
    margin: 200,
    jitter: 0.3,
    roles: ["baker", "butcher", "farmer", "fisher", "nurse", "painter", "sailor", "teacher"],
    accessories: ["hat", "shirt", "scarf", "apron", "bag"],
    colours: ["red", "blue", "green", "yellow", "white", "black"],
    decoysPerClause: 3,
    ...overrides,
  };
}

/** Same xorshift as AmboRacer's, copied rather than shared: samples do not depend on each other. */
export function seededRandom(seed: number): () => number {
  let state = seed >>> 0 || 1;
  return () => {
    state ^= state << 13; state >>>= 0;
    state ^= state >> 17;
    state ^= state << 5; state >>>= 0;
    return state / 0x100000000;
  };
}

function pick<T>(items: readonly T[], random: () => number): T {
  // Non-null asserted via a fallback rather than `!`: noUncheckedIndexedAccess is on, and an empty
  // list here would be a config error that should surface as a thrown message, not as undefined
  // flowing into a prompt.
  const item = items[Math.floor(random() * items.length)] ?? items[0];
  if (item === undefined) throw new Error("cannot pick from an empty attribute list");
  return item;
}

function sameCombination(a: Combination, b: Combination): boolean {
  return a.role === b.role && a.accessory === b.accessory && a.colour === b.colour;
}

/** Which clauses the claimed character got wrong, in a stable order. */
export function missedClauses(target: Combination, claimed: Combination): readonly Clause[] {
  return CLAUSES.filter((clause) => target[clause] !== claimed[clause]);
}

/**
 * Builds the crowd, then *plants* what the prompt needs instead of trusting the shuffle.
 *
 * The order matters. The filler is generated first and explicitly refuses the target combination,
 * so the target is unique by construction - if a second identical character existed, a correct
 * reading of the sentence could still be judged wrong, which is the one defect a teaching game
 * must not have. Then the decoys are planted, then the target. Both overwrite filler cells.
 *
 * `decoysPerClause * 3 + 1` cells are spent this way, so the grid must be bigger than that; a
 * 26x15 crowd has 390 and spends 10.
 */
export function buildScene(config: SceneConfig = sceneConfig(), random: () => number = seededRandom(20261001)): Scene {
  const usableWidth = config.width - 2 * config.margin;
  const usableHeight = config.height - 2 * config.margin;
  const columns = Math.max(1, Math.floor(usableWidth / config.cellSize));
  const rows = Math.max(1, Math.floor(usableHeight / config.cellSize));
  const cells = columns * rows;
  if (usableWidth < config.cellSize || usableHeight < config.cellSize) {
    throw new Error(`a ${config.margin}-unit margin leaves no room for a ${config.cellSize}-unit cell`);
  }
  const planted = config.decoysPerClause * CLAUSES.length + 1;
  if (cells <= planted) {
    throw new Error(`scene holds ${cells} characters, which cannot carry ${planted} planted ones`);
  }

  const target: Combination = {
    role: pick(config.roles, random),
    accessory: pick(config.accessories, random),
    colour: pick(config.colours, random),
  };

  const place = (index: number, combination: Combination): Character => {
    const column = index % columns;
    const row = Math.floor(index / columns);
    const spread = config.cellSize * config.jitter;
    // Clamped into the margin rather than trusted to stay there: the jitter is bounded by
    // construction today, but a larger jitter in a future config would silently put a character
    // where the crosshair cannot reach, which is unwinnable rather than merely untidy.
    return {
      id: index,
      x: clamp(
        config.margin + (column + 0.5) * config.cellSize + (random() - 0.5) * 2 * spread,
        config.margin,
        config.width - config.margin,
      ),
      y: clamp(
        config.margin + (row + 0.5) * config.cellSize + (random() - 0.5) * 2 * spread,
        config.margin,
        config.height - config.margin,
      ),
      pose: random(),
      ...combination,
    };
  };

  const characters: Character[] = [];
  for (let index = 0; index < cells; index += 1) {
    let combination: Combination;
    do {
      combination = {
        role: pick(config.roles, random),
        accessory: pick(config.accessories, random),
        colour: pick(config.colours, random),
      };
    } while (sameCombination(combination, target));
    characters.push(place(index, combination));
  }

  // Reserved cells are drawn without replacement, so planting a decoy cannot quietly overwrite
  // the one planted a moment ago - which would leave a clause with fewer near-misses than
  // configured and no error to say so.
  const free = characters.map((_, index) => index);
  const reserve = (): number => {
    const at = Math.floor(random() * free.length);
    const [index] = free.splice(at, 1);
    if (index === undefined) throw new Error("ran out of cells while planting");
    return index;
  };

  const alternatives: Record<Clause, readonly string[]> = {
    role: config.roles,
    accessory: config.accessories,
    colour: config.colours,
  };

  for (const clause of CLAUSES) {
    const others = alternatives[clause].filter((value) => value !== target[clause]);
    for (let n = 0; n < config.decoysPerClause; n += 1) {
      const swap = others[n % others.length];
      if (swap === undefined) continue; // A single-valued attribute has no near-miss to offer.
      const index = reserve();
      characters[index] = place(index, { ...target, [clause]: swap });
    }
  }

  const targetIndex = reserve();
  const targetCharacter = place(targetIndex, target);
  characters[targetIndex] = targetCharacter;

  return { width: config.width, height: config.height, characters, target: targetCharacter };
}

/* ------------------------------------------------------------------ aiming */

export interface AimConfig {
  /** Lens radius in scene units. Shrinking this is the difficulty dial that costs nothing. */
  readonly lensRadius: number;
  /**
   * How much turning covers the whole scene. 70 degrees of yaw across the width is a wrist
   * movement from a sofa, not a shoulder movement - which matters because a round is 30 seconds
   * and an arm held up at a television stops being fun well before that.
   */
  readonly yawSpanDeg: number;
  readonly pitchSpanDeg: number;
  /**
   * Which way turning moves the lens. Defaults are a guess that MUST be confirmed on hardware.
   *
   * The Companion reports its axis convention in `ready.axes` as a string, and the device layer
   * has already been wrong about this once - a renderer bound pitch and roll the wrong way round
   * and it looked plausible until someone held the phone. These are two booleans precisely so the
   * fix during a demo is a constant, not a rebuild of the mapping.
   */
  readonly invertYaw: boolean;
  readonly invertPitch: boolean;
  /**
   * Pitch is clamped well short of straight up, where yaw stops meaning anything at all. Reading
   * past the clamp is not a lost feature: it is the region where the lens would slew sideways
   * because of a wrist twist the person did not make.
   */
  readonly pitchLimitDeg: number;
  /**
   * Seconds for the lens to cover most of the distance to where the phone is pointing.
   *
   * A small lens magnifies hand tremor, so the lens follows rather than tracks. Expressed as a
   * time constant and applied with the frame delta, so the feel does not change with the
   * orientation rate - which varies by handset and is one of the twelve capabilities whose
   * envelope has not been measured yet.
   */
  readonly followSeconds: number;
  /** Claiming hits the nearest character within this many lens radii of the crosshair. */
  readonly claimRadii: number;
}

export function aimConfig(overrides: Partial<AimConfig> = {}): AimConfig {
  return {
    lensRadius: 190,
    yawSpanDeg: 70,
    pitchSpanDeg: 50,
    invertYaw: false,
    invertPitch: true,
    pitchLimitDeg: 55,
    followSeconds: 0.09,
    claimRadii: 0.8,
    ...overrides,
  };
}

/**
 * The smallest `SceneConfig.margin` that leaves every character claimable, given how the lens is
 * clamped. One function so the coupling between the two configs is stated once and testable,
 * rather than being a number that happens to be large enough.
 */
export function reachableMargin(config: AimConfig): number {
  return config.lensRadius;
}

/** Shortest signed distance from `from` to `to` in degrees, across the -180/180 seam. */
export function angleDelta(from: number, to: number): number {
  let delta = (to - from) % 360;
  if (delta > 180) delta -= 360;
  if (delta < -180) delta += 360;
  return delta;
}

function clamp(value: number, low: number, high: number): number {
  return Math.min(Math.max(value, low), high);
}

export type Verdict =
  | { readonly kind: "correct"; readonly character: Character }
  | { readonly kind: "wrong"; readonly character: Character; readonly missed: readonly Clause[] }
  | { readonly kind: "empty" };

export interface HuntSnapshot {
  readonly lens: { readonly x: number; readonly y: number; readonly radius: number };
  /** Where the phone says to go, before following. Drawn faintly, it explains the lag. */
  readonly aim: { readonly x: number; readonly y: number };
  /** False while the provider reports poor orientation accuracy - the cue to recentre. */
  readonly trusted: boolean;
  /** False until the first orientation frame, so the renderer can ask for a recentre first. */
  readonly aiming: boolean;
  readonly found: number;
  readonly attempts: number;
  /** The teaching instrument: which clause each wrong claim lost, counted. */
  readonly misses: Readonly<Record<Clause, number>>;
  readonly lastVerdict: Verdict | null;
}

/**
 * The lens, the score, and the judgement. One round holds one target.
 *
 * Clock-free on purpose except for `tick(dtMs)`: timing a round is the renderer's business, and a
 * rules module that read a clock could not be tested without pretending to be one.
 */
export class Hunt {
  readonly #config: AimConfig;
  readonly #scene: Scene;

  /** Where the phone is pointing, in scene units, before following. */
  #aimX: number;
  #aimY: number;
  /** Where the lens actually is. Eases toward the aim. */
  #lensX: number;
  #lensY: number;

  /** The orientation pinned by the last recentre, and the lens position pinned with it. */
  #homeYaw: number | null = null;
  #homePitch = 0;
  #anchorX: number;
  #anchorY: number;
  #lastYaw: number | null = null;
  #lastPitch = 0;
  #trusted = true;

  #found = 0;
  #attempts = 0;
  readonly #misses: Record<Clause, number> = { role: 0, accessory: 0, colour: 0 };
  #lastVerdict: Verdict | null = null;

  constructor(scene: Scene, config: AimConfig = aimConfig()) {
    this.#scene = scene;
    this.#config = config;
    this.#aimX = scene.width / 2;
    this.#aimY = scene.height / 2;
    this.#lensX = this.#aimX;
    this.#lensY = this.#aimY;
    this.#anchorX = this.#aimX;
    this.#anchorY = this.#aimY;
  }

  get scene(): Scene { return this.#scene; }
  get config(): AimConfig { return this.#config; }
  get aiming(): boolean { return this.#homeYaw !== null; }

  /**
   * An orientation frame. `accuracy` is passed through rather than acted on, because a provider
   * that says "unreliable" is still the only orientation there is - the right response is to tell
   * the person to recentre, not to freeze the lens and look broken.
   */
  orient(yawDeg: number, pitchDeg: number, accuracy?: string | null): void {
    this.#lastYaw = yawDeg;
    this.#lastPitch = pitchDeg;
    this.#trusted = accuracy !== "unreliable";
    if (this.#homeYaw === null) {
      // The first frame anchors itself, so a person who never presses recentre still has a lens
      // that tracks. Pressing it later re-anchors wherever they are comfortable holding the phone.
      this.recentre();
      return;
    }
    const yaw = angleDelta(this.#homeYaw, yawDeg) * (this.#config.invertYaw ? -1 : 1);
    const pitchHere = clamp(pitchDeg, -this.#config.pitchLimitDeg, this.#config.pitchLimitDeg);
    const pitchHome = clamp(this.#homePitch, -this.#config.pitchLimitDeg, this.#config.pitchLimitDeg);
    const pitch = (pitchHere - pitchHome) * (this.#config.invertPitch ? -1 : 1);
    this.#aimTo(
      this.#anchorX + (yaw / this.#config.yawSpanDeg) * this.#scene.width,
      this.#anchorY + (pitch / this.#config.pitchSpanDeg) * this.#scene.height,
    );
  }

  /**
   * Pins the current facing to the current lens position.
   *
   * Anchors to the aim rather than to the followed lens, so repeatedly pressing recentre while
   * holding still does not creep: the aim is where the phone says, and re-anchoring to it is a
   * no-op by construction.
   */
  recentre(): void {
    if (this.#lastYaw !== null) {
      this.#homeYaw = this.#lastYaw;
      this.#homePitch = this.#lastPitch;
    }
    this.#anchorX = this.#aimX;
    this.#anchorY = this.#aimY;
  }

  /**
   * The fallback driver: move the lens by a fraction of the scene, from a touch drag.
   *
   * Here because orientation is the one capability this sample depends on that has never been
   * measured on hardware, and a demo with no second way to move the lens would have nothing to
   * fall back to. It also re-anchors, so switching back to the phone's facing does not jump.
   */
  nudge(dxFraction: number, dyFraction: number): void {
    this.#aimTo(
      this.#aimX + dxFraction * this.#scene.width,
      this.#aimY + dyFraction * this.#scene.height,
    );
    this.recentre();
  }

  /** Eases the lens toward the aim. `dtMs` is the renderer's frame delta. */
  tick(dtMs: number): void {
    const seconds = Math.max(0, dtMs) / 1000;
    // 1 - e^(-dt/tau): the fraction of the remaining distance to cover this frame. Frame-rate
    // independent, unlike a fixed per-frame fraction, which would drift faster on a faster screen.
    const alpha = this.#config.followSeconds <= 0 ? 1 : 1 - Math.exp(-seconds / this.#config.followSeconds);
    this.#lensX += (this.#aimX - this.#lensX) * alpha;
    this.#lensY += (this.#aimY - this.#lensY) * alpha;
  }

  /**
   * Claims whatever is under the crosshair.
   *
   * Judged against the LENS, not the aim: the lens is what the person can see, and judging against
   * a position they were not shown would make a confident tap wrong for a reason invisible on
   * screen.
   */
  claim(): Verdict {
    const reach = this.#config.lensRadius * this.#config.claimRadii;
    let best: Character | null = null;
    let bestDistance = Infinity;
    for (const character of this.#scene.characters) {
      const distance = Math.hypot(character.x - this.#lensX, character.y - this.#lensY);
      if (distance < bestDistance) { best = character; bestDistance = distance; }
    }
    if (best === null || bestDistance > reach) {
      // Not counted as an attempt. A tap into empty market is a miss of the pointing, not of the
      // language, and folding it into the score would corrupt the one number worth reporting.
      this.#lastVerdict = { kind: "empty" };
      return this.#lastVerdict;
    }
    this.#attempts += 1;
    const missed = missedClauses(this.#scene.target, best);
    if (missed.length === 0) {
      this.#found += 1;
      this.#lastVerdict = { kind: "correct", character: best };
      return this.#lastVerdict;
    }
    for (const clause of missed) this.#misses[clause] += 1;
    this.#lastVerdict = { kind: "wrong", character: best, missed };
    return this.#lastVerdict;
  }

  /** Characters whose centre is within the lens. The renderer draws these legibly. */
  inLens(): readonly Character[] {
    return this.#scene.characters.filter(
      (character) => Math.hypot(character.x - this.#lensX, character.y - this.#lensY) <= this.#config.lensRadius,
    );
  }

  snapshot(): HuntSnapshot {
    return {
      lens: { x: this.#lensX, y: this.#lensY, radius: this.#config.lensRadius },
      aim: { x: this.#aimX, y: this.#aimY },
      trusted: this.#trusted,
      aiming: this.aiming,
      found: this.#found,
      attempts: this.#attempts,
      misses: { ...this.#misses },
      lastVerdict: this.#lastVerdict,
    };
  }

  /** Clamped to the scene, so sweeping past the edge stops rather than losing the crowd. */
  #aimTo(x: number, y: number): void {
    const margin = this.#config.lensRadius;
    this.#aimX = clamp(x, margin, Math.max(margin, this.#scene.width - margin));
    this.#aimY = clamp(y, margin, Math.max(margin, this.#scene.height - margin));
  }
}

/* ------------------------------------------------------------------ one language, as data */

/**
 * Spanish, as the shipped example. Swapping this object is the whole cost of another language,
 * which is the reason the words are not in the code above.
 */
export const SPANISH: Lexicon = {
  code: "es-ES",
  find: "Encuentra",
  with: "con",
  roles: {
    baker: { article: "al", word: "panadero", gender: "m" },
    butcher: { article: "al", word: "carnicero", gender: "m" },
    farmer: { article: "al", word: "granjero", gender: "m" },
    fisher: { article: "al", word: "pescador", gender: "m" },
    nurse: { article: "a la", word: "enfermera", gender: "f" },
    painter: { article: "al", word: "pintor", gender: "m" },
    sailor: { article: "al", word: "marinero", gender: "m" },
    teacher: { article: "al", word: "maestro", gender: "m" },
  },
  accessories: {
    hat: { article: "el", word: "sombrero", gender: "m" },
    shirt: { article: "la", word: "camisa", gender: "f" },
    scarf: { article: "la", word: "bufanda", gender: "f" },
    apron: { article: "el", word: "delantal", gender: "m" },
    bag: { article: "la", word: "bolsa", gender: "f" },
  },
  colours: {
    red: { m: "rojo", f: "roja" },
    blue: { m: "azul", f: "azul" },
    green: { m: "verde", f: "verde" },
    yellow: { m: "amarillo", f: "amarilla" },
    white: { m: "blanco", f: "blanca" },
    black: { m: "negro", f: "negra" },
  },
  gloss: {
    baker: "baker", butcher: "butcher", farmer: "farmer", fisher: "fisherman",
    nurse: "nurse", painter: "painter", sailor: "sailor", teacher: "teacher",
    hat: "hat", shirt: "shirt", scarf: "scarf", apron: "apron", bag: "bag",
    red: "red", blue: "blue", green: "green", yellow: "yellow", white: "white", black: "black",
  },
};
