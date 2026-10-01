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
export type Clause = "subject" | "trait" | "colour";

export const CLAUSES: readonly Clause[] = ["subject", "trait", "colour"];

/** What the prompt describes and what the crowd is drawn from, as ids rather than words. */
export interface Combination {
  readonly subject: string;
  readonly trait: string;
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

/**
 * A noun carries its article and its gender, because the adjective has to agree with it.
 *
 * `gender` is a free string rather than "m" | "f": German has three, Hindi's adjectives agree with
 * two but many are invariant, and a union fixed to the first language added would have to be
 * widened by every language after it. The only contract is that it keys into `Adjective`.
 * An article of "" is normal - several languages have none.
 */
export interface Noun {
  readonly article: string;
  /** The citation form - what goes on the label beside the picture. */
  readonly word: string;
  /**
   * The form the sentence needs, when the language inflects it. Falls back to `word`.
   *
   * Required by any language that marks case on the noun itself rather than with a separate
   * particle: Kannada's accusative is a suffix, and Hindi's oblique changes the vowel. Keeping the
   * citation form separate matters because the picture is labelled with the word a learner should
   * remember, not with the case-marked form that happens to appear in this one sentence.
   */
  readonly inflected?: string;
  /**
   * The key an adjective agrees with. Not only a gender: Hindi needs a different adjective form in
   * the oblique, so its masculine oblique nouns declare "m.obl" and its colours answer to it.
   */
  readonly gender: string;
}

/**
 * One form per gender, plus an optional fallback for the many colours that do not inflect at all.
 * Agreement is data rather than a rule the renderer would have to know in each language.
 */
export interface Adjective {
  readonly [gender: string]: string | undefined;
}

/**
 * The target language, kept out of the rules so the same game teaches Spanish or German.
 *
 * `code` is a BCP-47 tag for whatever speaks the prompt aloud on the renderer's side. The phone
 * never speaks: the room has to hear the language, and the room is looking at the screen.
 */
export interface Lexicon {
  readonly code: string;
  /** How this language names itself, for the picker. Never the English name. */
  readonly name: string;
  /**
   * The sentence, as a template, because word order is part of a language and not a constant.
   *
   * Spanish puts the colour after the noun; Hindi puts it before and the verb at the end. Building
   * the sentence in code would have meant one language's grammar hardcoded and every other
   * language bent to fit it. Placeholders: {find} {subjectArticle} {subject} {with} {traitArticle}
   * {trait} {colour}. Runs of spaces left by an empty article are collapsed.
   */
  readonly pattern: string;
  readonly find: string;
  /**
   * The word joining a person to what they are wearing or carrying, agreeing with the SUBJECT.
   *
   * An Adjective rather than a string because Hindi inflects it - vaale for a man, vaali for a
   * woman - and a plain string would have silently produced one wrong sentence in eight. Languages
   * that do not inflect it declare an invariant form, and languages that have no such word at all
   * declare it empty.
   */
  readonly with: Adjective;
  readonly subjects: Readonly<Record<string, Noun>>;
  readonly traits: Readonly<Record<string, Noun>>;
  readonly colours: Readonly<Record<string, Adjective>>;
  /**
   * What each profession actually does, in the target language.
   *
   * Said when the person finds them, which is the moment the word has just been earned - the
   * sentence lands on a picture they are already looking at rather than on a vocabulary list.
   */
  readonly does: Readonly<Record<string, string>>;
  readonly praise: string;
  readonly again: string;
}

/** The adjective form agreeing with `gender`, falling back to an invariant form. */
export function agree(adjective: Adjective, gender: string): string {
  return adjective[gender] ?? adjective["*"] ?? Object.values(adjective)[0] ?? "";
}

/**
 * Assembles the prompt with the colour agreeing with the ACCESSORY, not the person.
 *
 * This is the grammar the game actually teaches, and it is why the colour attaches to the
 * accessory rather than to the subject: "el panadero con la camisa roja" against "con el sombrero
 * rojo" puts agreement on the line in every single prompt, where a textbook puts it in one
 * exercise. A learner who ignores the ending still finds the right person - but they will have
 * read past the one thing the sentence was built to teach.
 */
export function promptText(lexicon: Lexicon, combination: Combination): string {
  const subject = lexicon.subjects[combination.subject];
  const trait = lexicon.traits[combination.trait];
  const colour = lexicon.colours[combination.colour];
  if (subject === undefined || trait === undefined || colour === undefined) {
    // An id with no word is a content bug, not a runtime condition to style around. Saying which
    // id is missing beats a sentence with a hole in it that a tester reports as "looks wrong".
    return `[no words for ${combination.subject}/${combination.trait}/${combination.colour}]`;
  }
  const filled: Record<string, string> = {
    find: lexicon.find,
    subjectArticle: subject.article,
    subject: subject.inflected ?? subject.word,
    with: agree(lexicon.with, subject.gender),
    traitArticle: trait.article,
    trait: trait.inflected ?? trait.word,
    colour: agree(colour, trait.gender),
  };
  return lexicon.pattern
    .replace(/\{(\w+)\}/g, (_, key: string) => filled[key] ?? "")
    .replace(/\s+/g, " ")
    .replace(/\s+([,.!?])/g, "$1")
    .trim();
}

/** What the found profession does, in the target language. Empty when the language has not said. */
export function doesText(lexicon: Lexicon, subjectId: string): string {
  return lexicon.does[subjectId] ?? "";
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
  readonly subjects: readonly string[];
  /**
   * Body items only, and never headwear.
   *
   * The role is read from a silhouette - a toque, a straw hat, a sailor cap, and the tool in the
   * hands - because eight professions have to be told apart across a room at a glance. That makes
   * headwear the role's, so "the baker with the red hat" would name two things at once and the
   * prompt would contradict the picture. The accessory is what takes the colour; the role's
   * headwear and tool stay in ink.
   */
  readonly traits: readonly string[];
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
    margin: 300,
    jitter: 0.3,
    subjects: JOBS.subjects,
    traits: JOBS.traits,
    colours: JOBS.colours,
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
  return a.subject === b.subject && a.trait === b.trait && a.colour === b.colour;
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
    subject: pick(config.subjects, random),
    trait: pick(config.traits, random),
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
        subject: pick(config.subjects, random),
        trait: pick(config.traits, random),
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
    subject: config.subjects,
    trait: config.traits,
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

/* ------------------------------------------------------------------ a painted scene */

/**
 * A scene that was painted rather than generated.
 *
 * `buildScene` above guarantees the near-misses by construction, because it places them. A painting
 * contains whatever the illustrator painted, so the guarantee moves here: the annotation says what
 * is findable and where, and `nearMissesFor` reports - per clause - whether the picture can
 * actually hold a prompt to account. That report is advisory at runtime and a gate at build time,
 * which is the only honest arrangement when the data is drawn by hand.
 */
export interface SceneAnnotation {
  readonly id: string;
  readonly name: string;
  /** The painted file, relative to the sample. The rules never load it; the renderer does. */
  readonly image: string;
  readonly width: number;
  readonly height: number;
  readonly findables: readonly AnnotatedFindable[];
}

export interface AnnotatedFindable extends Combination {
  /** Centre of the thing in the painting, in the painting's own pixels. */
  readonly x: number;
  readonly y: number;
}

/**
 * Turns an annotation into the same Scene the generator produces, so Hunt cannot tell them apart.
 *
 * The target is chosen rather than taken: a painted scene offers several candidates and they are
 * not equally good. `preferTarget` picks one by id for a fixed demo; otherwise the candidate with
 * the most clauses covered by a near-miss wins, falling back to any unique combination.
 */
export function sceneFromAnnotation(
  annotation: SceneAnnotation,
  preferTarget?: number,
  random: () => number = seededRandom(20261001),
): Scene {
  const characters: Character[] = annotation.findables.map((findable, index) => ({
    id: index,
    x: findable.x,
    y: findable.y,
    pose: random(),
    subject: findable.subject,
    trait: findable.trait,
    colour: findable.colour,
  }));
  if (characters.length === 0) throw new Error(`${annotation.id}: annotation lists nothing findable`);

  const unique = characters.filter(
    (candidate) => characters.filter((other) => sameCombination(other, candidate)).length === 1,
  );
  if (unique.length === 0) {
    // Every findable has a twin, so no prompt in this scene has one right answer.
    throw new Error(`${annotation.id}: no findable is uniquely described by subject, trait and colour`);
  }

  let target = unique[0] as Character;
  if (preferTarget !== undefined) {
    const chosen = characters.find((c) => c.id === preferTarget);
    if (chosen === undefined) throw new Error(`${annotation.id}: no findable with id ${preferTarget}`);
    if (!unique.includes(chosen)) throw new Error(`${annotation.id}: findable ${preferTarget} is not unique`);
    target = chosen;
  } else {
    let best = -1;
    for (const candidate of unique) {
      const report = nearMissesFor(characters, candidate);
      const covered = CLAUSES.filter((clause) => (report[clause] ?? 0) > 0).length;
      if (covered > best) { best = covered; target = candidate; }
    }
  }
  return { width: annotation.width, height: annotation.height, characters, target };
}

/**
 * How many near-misses the scene holds for each clause of this target.
 *
 * A zero means a prompt naming that target can be won without reading that clause - spot the only
 * monkey and the colour never mattered. For a generated scene this is guaranteed non-zero; for a
 * painted one it is a measurement, and the number is what the illustrator's brief is held to.
 */
export function nearMissesFor(
  characters: readonly Character[],
  target: Combination,
): Readonly<Record<Clause, number>> {
  const report: Record<Clause, number> = { subject: 0, trait: 0, colour: 0 };
  for (const character of characters) {
    const missed = missedClauses(target, character);
    if (missed.length === 1) {
      const only = missed[0];
      if (only !== undefined) report[only] += 1;
    }
  }
  return report;
}

/* ------------------------------------------------------------------ aiming */

export interface AimConfig {
  /** Lens radius in scene units. Shrinking this is the difficulty dial that costs nothing. */
  readonly lensRadius: number;
  /**
   * How much turning covers the whole scene. Larger is LESS sensitive: the same wrist movement
   * covers a smaller fraction of the scene.
   *
   * 70 and 50 were chosen by looking at a desktop prototype and reported as "extremely sensitive"
   * the first time anybody held a phone. 115 and 85 are the first numbers from hardware rather
   * than from a screen, and they are still a wrist movement rather than a shoulder one - which
   * matters, because an arm held up at a television stops being fun well before a round ends.
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
    lensRadius: 280,
    yawSpanDeg: 115,
    pitchSpanDeg: 85,
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
  /**
   * How long this hunt has been running, accumulated from the frame deltas rather than read from
   * a clock - so the rules stay testable without pretending to be one, and so a paused renderer
   * does not quietly count time the person was not playing.
   *
   * Time to find is the better half of the score. It measures fluency - how fast the sentence was
   * parsed - where the clause counts measure which word was not known. Speed alone would reward
   * tapping fast and guessing, so both are kept and neither is reported without the other.
   */
  readonly elapsedMs: number;
  /** Stops once the target is found, so the panel that follows does not inflate the time. */
  readonly finished: boolean;
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
  #elapsedMs = 0;
  #finished = false;
  readonly #misses: Record<Clause, number> = { subject: 0, trait: 0, colour: 0 };
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

  /** Eases the lens toward the aim, and counts the hunt. `dtMs` is the renderer's frame delta. */
  tick(dtMs: number): void {
    const seconds = Math.max(0, dtMs) / 1000;
    if (!this.#finished) this.#elapsedMs += Math.max(0, dtMs);
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
      this.#finished = true;
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
      elapsedMs: this.#elapsedMs,
      finished: this.#finished,
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

/* ------------------------------------------------------------------ topics and languages */

/**
 * A topic is the three axes, named. Nothing else about the game changes between topics: the decoy
 * guarantee, the clause diagnosis and the lens all work on subject/trait/colour whatever those
 * happen to mean. Jobs is baker x apron x red; animals would be tiger x collar x red; actions
 * would be girl x running x red.
 *
 * What a topic does NOT carry is the drawing. A renderer needs one picture per subject and one per
 * trait - thirteen pictures for this topic, not the hundreds the crowd appears to contain, which
 * is the whole economy of generating the crowd from a grid, and what makes commissioning artwork
 * good enough for a child affordable.
 */
export interface Topic {
  readonly id: string;
  readonly subjects: readonly string[];
  readonly traits: readonly string[];
  readonly colours: readonly string[];
}

export const JOBS: Topic = {
  id: "jobs",
  subjects: ["baker", "butcher", "farmer", "fisher", "nurse", "painter", "sailor", "teacher"],
  traits: ["shirt", "scarf", "apron", "bag", "umbrella"],
  colours: ["red", "blue", "green", "yellow", "white", "black"],
};

const INVARIANT = (word: string): Adjective => ({ "*": word });

/**
 * English, which is a target language here and not a privileged one.
 *
 * The hint is the same sentence in whichever language the learner already knows, so there is no
 * separate gloss table any more - a learner who knows Kannada and is learning Hindi is served by
 * the same mechanism as one who knows English. That is also why the colour sits before the noun
 * here and after it in Spanish: word order is carried by the pattern, not by the code.
 */
export const ENGLISH: Lexicon = {
  code: "en-GB", name: "English",
  pattern: "{find} {subjectArticle} {subject} {with} {traitArticle} {colour} {trait}",
  find: "Find", with: INVARIANT("with"),
  subjects: {
    baker: { article: "the", word: "baker", gender: "m" },
    butcher: { article: "the", word: "butcher", gender: "m" },
    farmer: { article: "the", word: "farmer", gender: "m" },
    fisher: { article: "the", word: "fisherman", gender: "m" },
    nurse: { article: "the", word: "nurse", gender: "f" },
    painter: { article: "the", word: "painter", gender: "m" },
    sailor: { article: "the", word: "sailor", gender: "m" },
    teacher: { article: "the", word: "teacher", gender: "m" },
  },
  traits: {
    shirt: { article: "the", word: "shirt", gender: "n" },
    scarf: { article: "the", word: "scarf", gender: "n" },
    apron: { article: "the", word: "apron", gender: "n" },
    bag: { article: "the", word: "bag", gender: "n" },
    umbrella: { article: "the", word: "umbrella", gender: "n" },
  },
  colours: {
    red: INVARIANT("red"), blue: INVARIANT("blue"), green: INVARIANT("green"),
    yellow: INVARIANT("yellow"), white: INVARIANT("white"), black: INVARIANT("black"),
  },
  does: {
    baker: "A baker bakes bread.", butcher: "A butcher cuts meat.",
    farmer: "A farmer grows food.", fisher: "A fisherman catches fish.",
    nurse: "A nurse looks after people who are ill.", painter: "A painter paints pictures.",
    sailor: "A sailor sails a ship.", teacher: "A teacher teaches children.",
  },
  praise: "Well done!", again: "Again?",
};

/**
 * Hindi. The reason the pattern had to become data: the colour comes before the noun, the
 * postposition follows it, and the verb is last.
 *
 * Also the reason `inflected` and an agreeing `with` exist. An earlier version read
 * "kaala chhaata vaale", which is wrong twice over - the oblique needs "kaale chhaate", and
 * "vaale" becomes "vaali" for a woman. The masculine oblique nouns declare gender "m.obl" and the
 * colours answer to that key, so the agreement is data like everything else.
 *
 * NOT yet checked by a native speaker. The case marking is where this is least certain.
 */
export const HINDI: Lexicon = {
  code: "hi-IN", name: "हिन्दी",
  pattern: "{colour} {trait} {with} {subject} को {find}",
  find: "ढूंढो", with: { m: "वाले", f: "वाली", "m.obl": "वाले" },
  subjects: {
    baker: { article: "", word: "नानबाई", gender: "m" },
    butcher: { article: "", word: "कसाई", gender: "m" },
    farmer: { article: "", word: "किसान", gender: "m" },
    fisher: { article: "", word: "मछुआरा", inflected: "मछुआरे", gender: "m" },
    nurse: { article: "", word: "नर्स", gender: "f" },
    painter: { article: "", word: "चित्रकार", gender: "m" },
    sailor: { article: "", word: "नाविक", gender: "m" },
    teacher: { article: "", word: "शिक्षक", gender: "m" },
  },
  traits: {
    shirt: { article: "", word: "कमीज़", gender: "f" },
    scarf: { article: "", word: "स्कार्फ़", gender: "m.obl" },
    apron: { article: "", word: "एप्रन", gender: "m.obl" },
    bag: { article: "", word: "बैग", gender: "m.obl" },
    umbrella: { article: "", word: "छाता", inflected: "छाते", gender: "m.obl" },
  },
  colours: {
    red: INVARIANT("लाल"),
    blue: { m: "नीला", f: "नीली", "m.obl": "नीले" },
    green: { m: "हरा", f: "हरी", "m.obl": "हरे" },
    yellow: { m: "पीला", f: "पीली", "m.obl": "पीले" },
    white: INVARIANT("सफ़ेद"),
    black: { m: "काला", f: "काली", "m.obl": "काले" },
  },
  does: {
    baker: "नानबाई रोटी बनाता है।", butcher: "कसाई मांस काटता है।",
    farmer: "किसान खेती करता है।", fisher: "मछुआरा मछली पकड़ता है।",
    nurse: "नर्स मरीज़ों की देखभाल करती है।", painter: "चित्रकार तस्वीरें बनाता है।",
    sailor: "नाविक जहाज़ चलाता है।", teacher: "शिक्षक बच्चों को पढ़ाता है।",
  },
  praise: "शाबाश!", again: "फिर से?",
};

/**
 * Kannada, and the reason `inflected` is on the noun rather than in the pattern: the accusative is
 * a suffix glued to the word, so no amount of rearranging a template produces it.
 *
 * Kannada adjectives do not agree at all, which makes it the useful contrast in this set - three
 * languages that inflect the colour and one that never does, so a learner moving between them
 * meets the idea of agreement as something languages choose rather than something sentences have.
 *
 * NOT yet checked by a native speaker, and less certain than the Hindi.
 */
export const KANNADA: Lexicon = {
  code: "kn-IN", name: "ಕನ್ನಡ",
  pattern: "{colour} {trait} {with} {subject} {find}",
  find: "ಹುಡುಕು", with: INVARIANT("ಇರುವ"),
  subjects: {
    baker: { article: "", word: "ಬೇಕರ್", inflected: "ಬೇಕರನ್ನು", gender: "m" },
    butcher: { article: "", word: "ಕಟುಕ", inflected: "ಕಟುಕನನ್ನು", gender: "m" },
    farmer: { article: "", word: "ರೈತ", inflected: "ರೈತನನ್ನು", gender: "m" },
    fisher: { article: "", word: "ಮೀನುಗಾರ", inflected: "ಮೀನುಗಾರನನ್ನು", gender: "m" },
    nurse: { article: "", word: "ದಾದಿ", inflected: "ದಾದಿಯನ್ನು", gender: "f" },
    painter: { article: "", word: "ಚಿತ್ರಕಾರ", inflected: "ಚಿತ್ರಕಾರನನ್ನು", gender: "m" },
    sailor: { article: "", word: "ನಾವಿಕ", inflected: "ನಾವಿಕನನ್ನು", gender: "m" },
    teacher: { article: "", word: "ಶಿಕ್ಷಕ", inflected: "ಶಿಕ್ಷಕನನ್ನು", gender: "m" },
  },
  traits: {
    shirt: { article: "", word: "ಅಂಗಿ", gender: "n" },
    scarf: { article: "", word: "ಶಲ್ಯ", gender: "n" },
    apron: { article: "", word: "ಏಪ್ರನ್", gender: "n" },
    bag: { article: "", word: "ಚೀಲ", gender: "n" },
    umbrella: { article: "", word: "ಕೊಡೆ", gender: "n" },
  },
  colours: {
    red: INVARIANT("ಕೆಂಪು"), blue: INVARIANT("ನೀಲಿ"), green: INVARIANT("ಹಸಿರು"),
    yellow: INVARIANT("ಹಳದಿ"), white: INVARIANT("ಬಿಳಿ"), black: INVARIANT("ಕಪ್ಪು"),
  },
  does: {
    baker: "ಬೇಕರ್ ರೊಟ್ಟಿ ಮಾಡುತ್ತಾನೆ.", butcher: "ಕಟುಕ ಮಾಂಸ ಕತ್ತರಿಸುತ್ತಾನೆ.",
    farmer: "ರೈತ ಬೆಳೆ ಬೆಳೆಯುತ್ತಾನೆ.", fisher: "ಮೀನುಗಾರ ಮೀನು ಹಿಡಿಯುತ್ತಾನೆ.",
    nurse: "ದಾದಿ ರೋಗಿಗಳನ್ನು ನೋಡಿಕೊಳ್ಳುತ್ತಾಳೆ.", painter: "ಚಿತ್ರಕಾರ ಚಿತ್ರ ಬಿಡಿಸುತ್ತಾನೆ.",
    sailor: "ನಾವಿಕ ಹಡಗು ಓಡಿಸುತ್ತಾನೆ.", teacher: "ಶಿಕ್ಷಕ ಮಕ್ಕಳಿಗೆ ಕಲಿಸುತ್ತಾನೆ.",
  },
  praise: "ಭೇಷ್!", again: "ಇನ್ನೊಮ್ಮೆ?",
};

const ROMANCE = "{find} {subjectArticle} {subject} {with} {traitArticle} {trait} {colour}";

export const SPANISH: Lexicon = {
  code: "es-ES", name: "Espanol", pattern: ROMANCE, find: "Encuentra", with: INVARIANT("con"),
  subjects: {
    baker: { article: "al", word: "panadero", gender: "m" },
    butcher: { article: "al", word: "carnicero", gender: "m" },
    farmer: { article: "al", word: "granjero", gender: "m" },
    fisher: { article: "al", word: "pescador", gender: "m" },
    nurse: { article: "a la", word: "enfermera", gender: "f" },
    painter: { article: "al", word: "pintor", gender: "m" },
    sailor: { article: "al", word: "marinero", gender: "m" },
    teacher: { article: "al", word: "maestro", gender: "m" },
  },
  traits: {
    shirt: { article: "la", word: "camisa", gender: "f" },
    scarf: { article: "la", word: "bufanda", gender: "f" },
    apron: { article: "el", word: "delantal", gender: "m" },
    bag: { article: "la", word: "bolsa", gender: "f" },
    umbrella: { article: "el", word: "paraguas", gender: "m" },
  },
  colours: {
    red: { m: "rojo", f: "roja" }, blue: INVARIANT("azul"), green: INVARIANT("verde"),
    yellow: { m: "amarillo", f: "amarilla" }, white: { m: "blanco", f: "blanca" },
    black: { m: "negro", f: "negra" },
  },
  does: {
    baker: "El panadero hace pan.", butcher: "El carnicero corta la carne.",
    farmer: "El granjero cultiva la tierra.", fisher: "El pescador pesca en el mar.",
    nurse: "La enfermera cuida a los enfermos.", painter: "El pintor pinta cuadros.",
    sailor: "El marinero navega en un barco.", teacher: "El maestro ensena a los ninos.",
  },
  praise: "Muy bien!", again: "Otra vez?",
};

export const FRENCH: Lexicon = {
  code: "fr-FR", name: "Francais", pattern: ROMANCE, find: "Trouve", with: INVARIANT("avec"),
  subjects: {
    baker: { article: "le", word: "boulanger", gender: "m" },
    butcher: { article: "le", word: "boucher", gender: "m" },
    farmer: { article: "le", word: "fermier", gender: "m" },
    fisher: { article: "le", word: "pecheur", gender: "m" },
    // The article is folded into the word where the language elides it, rather than teaching the
    // template about apostrophes - which would be one language's spelling rule in everyone's code.
    nurse: { article: "", word: "l'infirmiere", gender: "f" },
    painter: { article: "le", word: "peintre", gender: "m" },
    sailor: { article: "le", word: "marin", gender: "m" },
    teacher: { article: "le", word: "maitre", gender: "m" },
  },
  traits: {
    shirt: { article: "la", word: "chemise", gender: "f" },
    scarf: { article: "", word: "l'echarpe", gender: "f" },
    apron: { article: "le", word: "tablier", gender: "m" },
    bag: { article: "le", word: "sac", gender: "m" },
    umbrella: { article: "le", word: "parapluie", gender: "m" },
  },
  colours: {
    red: INVARIANT("rouge"), blue: { m: "bleu", f: "bleue" }, green: { m: "vert", f: "verte" },
    yellow: INVARIANT("jaune"), white: { m: "blanc", f: "blanche" }, black: { m: "noir", f: "noire" },
  },
  does: {
    baker: "Le boulanger fait du pain.", butcher: "Le boucher coupe la viande.",
    farmer: "Le fermier cultive la terre.", fisher: "Le pecheur peche dans la mer.",
    nurse: "L'infirmiere soigne les malades.", painter: "Le peintre peint des tableaux.",
    sailor: "Le marin navigue sur un bateau.", teacher: "Le maitre enseigne aux enfants.",
  },
  praise: "Tres bien!", again: "Encore?",
};

/** English and the two Indian languages first: those are the ones a classroom here will use. */
export const LANGUAGES: readonly Lexicon[] = [ENGLISH, HINDI, KANNADA, SPANISH, FRENCH];
