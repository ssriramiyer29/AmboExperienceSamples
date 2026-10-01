/**
 * The rules, exercised without a phone, a browser or a Gateway.
 *
 * Run with `npm test`. These are the checks that would otherwise be made by holding a handset and
 * squinting - and three of them cover failures that look like "the sensor is bad" from the sofa:
 * the seam at +/-180 degrees, frame-rate-dependent following, and judging a claim against where
 * the phone is pointing rather than against what the person can see.
 *
 * `buildScene`'s guarantees are tested across many seeds rather than one. A planting bug that
 * only shows on an unlucky shuffle is exactly the kind that reaches a classroom.
 */
import { test } from "node:test";
import assert from "node:assert/strict";

import {
  CLAUSES, Hunt, SPANISH, aimConfig, angleDelta, buildScene, glossText, missedClauses,
  promptText, reachableMargin, sceneConfig, seededRandom, type Clause, type Combination,
} from "../rules/hunt.ts";

const SEEDS = [1, 2, 7, 42, 1337, 20261001, 0xdeadbeef];

function combinationOf(c: Combination): Combination {
  return { role: c.role, accessory: c.accessory, colour: c.colour };
}

function same(a: Combination, b: Combination): boolean {
  return a.role === b.role && a.accessory === b.accessory && a.colour === b.colour;
}

test("the target combination appears exactly once, on every seed", () => {
  for (const seed of SEEDS) {
    const scene = buildScene(sceneConfig(), seededRandom(seed));
    const matches = scene.characters.filter((c) => same(c, scene.target));
    assert.equal(matches.length, 1, `seed ${seed} produced ${matches.length} characters matching the prompt`);
    assert.equal(matches[0]?.id, scene.target.id);
  }
});

test("every clause has its near-misses, on every seed", () => {
  const config = sceneConfig();
  for (const seed of SEEDS) {
    const scene = buildScene(config, seededRandom(seed));
    for (const clause of CLAUSES) {
      const nearMisses = scene.characters.filter((c) => {
        const missed = missedClauses(scene.target, c);
        return missed.length === 1 && missed[0] === clause;
      });
      assert.ok(
        nearMisses.length >= config.decoysPerClause,
        `seed ${seed}: clause ${clause} has ${nearMisses.length} near-misses, wanted ${config.decoysPerClause}`,
      );
    }
  }
});

test("a scene too small to hold what the prompt needs fails loudly", () => {
  // Negative test for the guard, per this repository's rule that an unexercised check is untested.
  assert.throws(
    () => buildScene(sceneConfig({ width: 1200, height: 800, cellSize: 180, decoysPerClause: 3 })),
    /cannot carry/,
  );
});

test("a margin that swallows the scene fails loudly", () => {
  assert.throws(() => buildScene(sceneConfig({ width: 400, height: 400, margin: 200 })), /leaves no room/);
});

test("the scene margin is wide enough for the lens that will search it", () => {
  const scene = sceneConfig();
  const aim = aimConfig();
  assert.ok(
    scene.margin >= reachableMargin(aim),
    `scene margin ${scene.margin} is inside the lens radius ${aim.lensRadius}: edge characters unclaimable`,
  );
});

test("every single character can be claimed by aiming at it", () => {
  // The test that found the real defect, stated the only way that is honest: not "within reach of
  // the crosshair" - a first attempt said that, and it passed with the bug still in - but "wins the
  // claim". The crowd is dense enough that several characters are always within reach, so the one
  // that counts is the NEAREST. A crosshair clamped away from an edge character leaves a neighbour
  // nearer, and the prompt naming that character is then unwinnable however well it is understood.
  for (const seed of [1, 20261001]) {
    const scene = buildScene(sceneConfig(), seededRandom(seed));
    for (const c of scene.characters) {
      const hunt = new Hunt(scene, aimConfig({ followSeconds: 0 }));
      hunt.nudge((c.x - hunt.snapshot().lens.x) / scene.width, (c.y - hunt.snapshot().lens.y) / scene.height);
      hunt.tick(16);
      const verdict = hunt.claim();
      const claimed = verdict.kind === "empty" ? null : verdict.character.id;
      assert.equal(
        claimed, c.id,
        `seed ${seed}: aiming at character ${c.id} at ${c.x.toFixed(0)},${c.y.toFixed(0)} claimed ${claimed} instead`,
      );
    }
  }
});

test("the adjective agrees with the accessory, not the person", () => {
  // camisa is feminine, sombrero masculine. The same colour, the same role, two endings - which is
  // the one thing every prompt in this game is built to teach.
  const feminine = promptText(SPANISH, { role: "baker", accessory: "shirt", colour: "red" });
  const masculine = promptText(SPANISH, { role: "baker", accessory: "umbrella", colour: "red" });
  assert.match(feminine, /la camisa roja$/);
  assert.match(masculine, /el paraguas rojo$/);
  assert.match(feminine, /^Encuentra al panadero con /);
});

test("an invariant colour keeps one form in both genders", () => {
  assert.match(promptText(SPANISH, { role: "nurse", accessory: "shirt", colour: "blue" }), /la camisa azul$/);
  assert.match(promptText(SPANISH, { role: "nurse", accessory: "umbrella", colour: "blue" }), /el paraguas azul$/);
});

test("a missing word names the ids rather than printing a hole", () => {
  const text = promptText(SPANISH, { role: "astronaut", accessory: "umbrella", colour: "red" });
  assert.match(text, /\[no words for astronaut\//);
});

test("the gloss reads as English in the prompt's order", () => {
  assert.equal(glossText(SPANISH, { role: "baker", accessory: "umbrella", colour: "red" }), "the baker with the red umbrella");
});

test("angleDelta takes the short way round the seam", () => {
  assert.equal(angleDelta(170, -170), 20);
  assert.equal(angleDelta(-170, 170), -20);
  assert.equal(angleDelta(0, 90), 90);
  assert.equal(angleDelta(0, -90), -90);
  // 180 is the tie. Either sign is defensible; what matters is that it is bounded, not that it is
  // positive - an unbounded answer here is what makes a lens teleport.
  assert.ok(Math.abs(angleDelta(0, 180)) === 180);
});

test("sweeping across the seam moves the lens smoothly, not in a jump", () => {
  const scene = buildScene();
  const hunt = new Hunt(scene, aimConfig({ followSeconds: 0 }));
  hunt.orient(175, 0);          // anchors here
  hunt.tick(16);
  const before = hunt.snapshot().lens.x;
  hunt.orient(-175, 0);         // ten degrees further round, across the seam
  hunt.tick(16);
  const after = hunt.snapshot().lens.x;
  const tenDegrees = (10 / aimConfig().yawSpanDeg) * scene.width;
  assert.ok(
    Math.abs(after - before - tenDegrees) < 1,
    `expected ~${tenDegrees.toFixed(0)} units of movement, got ${(after - before).toFixed(0)}`,
  );
});

test("the mapping is absolute: the same facing lands in the same place", () => {
  const hunt = new Hunt(buildScene(), aimConfig({ followSeconds: 0 }));
  hunt.orient(0, 0);
  hunt.orient(12, -6);
  hunt.tick(16);
  const first = hunt.snapshot().lens;
  hunt.orient(-20, 15);
  hunt.tick(16);
  hunt.orient(12, -6);
  hunt.tick(16);
  const second = hunt.snapshot().lens;
  assert.ok(Math.abs(first.x - second.x) < 0.001, `x drifted by ${first.x - second.x}`);
  assert.ok(Math.abs(first.y - second.y) < 0.001, `y drifted by ${first.y - second.y}`);
});

test("pitching up moves the lens up the scene", () => {
  const hunt = new Hunt(buildScene(), aimConfig({ followSeconds: 0 }));
  hunt.orient(0, 0);
  hunt.tick(16);
  const level = hunt.snapshot().lens.y;
  hunt.orient(0, 15);
  hunt.tick(16);
  assert.ok(hunt.snapshot().lens.y < level, "pitch up should decrease y with invertPitch on");
});

test("recentre pins the current facing without moving the lens", () => {
  const hunt = new Hunt(buildScene(), aimConfig({ followSeconds: 0 }));
  hunt.orient(0, 0);
  hunt.orient(20, 0);
  hunt.tick(16);
  const before = hunt.snapshot().lens;
  hunt.recentre();
  hunt.orient(20, 0);
  hunt.tick(16);
  const after = hunt.snapshot().lens;
  assert.ok(Math.abs(before.x - after.x) < 0.001, "recentre moved the lens");
  // And from there, turning back toward the old home must move the lens, not snap it back.
  hunt.orient(10, 0);
  hunt.tick(16);
  assert.ok(hunt.snapshot().lens.x < after.x, "turning back should pan back");
});

test("repeated recentres while holding still do not creep", () => {
  const hunt = new Hunt(buildScene(), aimConfig({ followSeconds: 0 }));
  hunt.orient(0, 0);
  hunt.orient(15, -10);
  hunt.tick(16);
  const settled = hunt.snapshot().lens.x;
  for (let n = 0; n < 20; n += 1) { hunt.recentre(); hunt.orient(15, -10); hunt.tick(16); }
  assert.ok(Math.abs(hunt.snapshot().lens.x - settled) < 0.001, "recentre crept");
});

test("pitch beyond the limit stops contributing instead of slewing", () => {
  const config = aimConfig({ followSeconds: 0, pitchLimitDeg: 55 });
  const hunt = new Hunt(buildScene(), config);
  hunt.orient(0, 0);
  hunt.orient(0, 55);
  hunt.tick(16);
  const atLimit = hunt.snapshot().lens.y;
  hunt.orient(0, 85);
  hunt.tick(16);
  assert.equal(hunt.snapshot().lens.y, atLimit);
});

test("following is frame-rate independent", () => {
  const scene = buildScene();
  const slow = new Hunt(scene, aimConfig());
  const fast = new Hunt(scene, aimConfig());
  for (const hunt of [slow, fast]) { hunt.orient(0, 0); hunt.orient(20, 0); }
  slow.tick(100);
  for (let n = 0; n < 10; n += 1) fast.tick(10);
  const difference = Math.abs(slow.snapshot().lens.x - fast.snapshot().lens.x);
  assert.ok(difference < 1, `one 100ms frame and ten 10ms frames differed by ${difference.toFixed(2)} units`);
});

test("the lens lags the aim and then catches up", () => {
  const hunt = new Hunt(buildScene(), aimConfig());
  hunt.orient(0, 0);
  hunt.orient(25, 0);
  hunt.tick(16);
  const lagging = hunt.snapshot();
  assert.ok(lagging.lens.x < lagging.aim.x, "lens should trail the aim on the first frame");
  for (let n = 0; n < 200; n += 1) hunt.tick(16);
  const settled = hunt.snapshot();
  assert.ok(Math.abs(settled.lens.x - settled.aim.x) < 0.5, "lens never caught up");
});

test("the lens stays within the scene however far the phone turns", () => {
  const scene = buildScene();
  const hunt = new Hunt(scene, aimConfig({ followSeconds: 0 }));
  hunt.orient(0, 0);
  for (const yaw of [179, -179, 90, -90, 45]) {
    hunt.orient(yaw, 0);
    hunt.tick(16);
    const { lens } = hunt.snapshot();
    assert.ok(lens.x >= lens.radius - 0.001 && lens.x <= scene.width - lens.radius + 0.001, `x ${lens.x} escaped`);
    assert.ok(lens.y >= lens.radius - 0.001 && lens.y <= scene.height - lens.radius + 0.001, `y ${lens.y} escaped`);
  }
});

test("claiming the target is correct and scores once", () => {
  const scene = buildScene();
  const hunt = new Hunt(scene, aimConfig({ followSeconds: 0 }));
  hunt.nudge((scene.target.x - scene.width / 2) / scene.width, (scene.target.y - scene.height / 2) / scene.height);
  hunt.tick(16);
  const verdict = hunt.claim();
  assert.equal(verdict.kind, "correct");
  const snapshot = hunt.snapshot();
  assert.equal(snapshot.found, 1);
  assert.equal(snapshot.attempts, 1);
  assert.deepEqual(snapshot.misses, { role: 0, accessory: 0, colour: 0 });
});

test("a one-clause-off claim reports exactly which clause was lost", () => {
  const scene = buildScene();
  // The planted near-misses are the whole point of the generator, so the diagnosis is tested
  // against one of them rather than against a character invented for the test.
  for (const clause of CLAUSES) {
    const decoy = scene.characters.find((c) => {
      const missed = missedClauses(scene.target, c);
      return missed.length === 1 && missed[0] === clause;
    });
    assert.ok(decoy !== undefined, `no near-miss for ${clause}`);
    const hunt = new Hunt(scene, aimConfig({ followSeconds: 0 }));
    hunt.nudge((decoy.x - scene.width / 2) / scene.width, (decoy.y - scene.height / 2) / scene.height);
    hunt.tick(16);
    const verdict = hunt.claim();
    assert.equal(verdict.kind, "wrong");
    assert.deepEqual(verdict.kind === "wrong" ? verdict.missed : [], [clause]);
    const counted: Record<Clause, number> = { role: 0, accessory: 0, colour: 0 };
    counted[clause] = 1;
    assert.deepEqual(hunt.snapshot().misses, counted);
  }
});

test("claiming empty market is not an attempt", () => {
  // A scene one character wide, so everywhere except that character is empty market.
  const scene = buildScene(sceneConfig({ width: 4680, height: 2700, decoysPerClause: 1 }));
  const hunt = new Hunt(scene, aimConfig({ followSeconds: 0, claimRadii: 0.02, lensRadius: 10 }));
  hunt.nudge(-0.49, -0.49);
  hunt.tick(16);
  const verdict = hunt.claim();
  if (verdict.kind === "empty") {
    assert.equal(hunt.snapshot().attempts, 0);
    assert.equal(hunt.snapshot().lastVerdict?.kind, "empty");
  } else {
    // Landing on somebody at the corner is legitimate; what must not happen is an unscored claim
    // being counted, so assert the other branch instead of skipping.
    assert.equal(hunt.snapshot().attempts, 1);
  }
});

test("the claim is judged against the lens, not against where the phone points", () => {
  const scene = buildScene();
  const hunt = new Hunt(scene, aimConfig());
  // Aim straight at the target but tick almost not at all, so the lens is still near the centre.
  hunt.nudge((scene.target.x - scene.width / 2) / scene.width, (scene.target.y - scene.height / 2) / scene.height);
  hunt.tick(1);
  const snapshot = hunt.snapshot();
  const lensToTarget = Math.hypot(scene.target.x - snapshot.lens.x, scene.target.y - snapshot.lens.y);
  if (lensToTarget > snapshot.lens.radius * aimConfig().claimRadii) {
    const verdict = hunt.claim();
    assert.notEqual(
      verdict.kind === "correct" ? verdict.character.id : -1,
      scene.target.id,
      "a target still off-screen was credited because the aim had arrived",
    );
  }
});

test("inLens returns only what is under the lens", () => {
  const scene = buildScene();
  const hunt = new Hunt(scene, aimConfig({ followSeconds: 0 }));
  hunt.orient(0, 0);
  hunt.tick(16);
  const { lens } = hunt.snapshot();
  for (const c of hunt.inLens()) {
    assert.ok(Math.hypot(c.x - lens.x, c.y - lens.y) <= lens.radius + 0.001);
  }
  const expected = scene.characters.filter((c) => Math.hypot(c.x - lens.x, c.y - lens.y) <= lens.radius);
  assert.equal(hunt.inLens().length, expected.length);
});

test("orientation accuracy is reported, not acted on", () => {
  const hunt = new Hunt(buildScene(), aimConfig({ followSeconds: 0 }));
  hunt.orient(0, 0, "high");
  assert.equal(hunt.snapshot().trusted, true);
  hunt.orient(10, 0, "unreliable");
  hunt.tick(16);
  const snapshot = hunt.snapshot();
  assert.equal(snapshot.trusted, false);
  // The lens must still have moved: a frozen lens looks like a broken app, and the person cannot
  // recentre their way out of a game that has stopped responding.
  assert.notEqual(snapshot.lens.x, hunt.scene.width / 2);
});

test("aiming is false until the first orientation frame", () => {
  const hunt = new Hunt(buildScene());
  assert.equal(hunt.snapshot().aiming, false);
  hunt.orient(0, 0);
  assert.equal(hunt.snapshot().aiming, true);
});

test("the scene is reproducible from its seed and different between seeds", () => {
  const a = buildScene(sceneConfig(), seededRandom(99));
  const b = buildScene(sceneConfig(), seededRandom(99));
  const c = buildScene(sceneConfig(), seededRandom(100));
  assert.deepEqual(combinationOf(a.target), combinationOf(b.target));
  assert.deepEqual(a.characters.map((x) => x.id + x.role), b.characters.map((x) => x.id + x.role));
  assert.notDeepEqual(
    a.characters.map((x) => x.role + x.accessory + x.colour),
    c.characters.map((x) => x.role + x.accessory + x.colour),
  );
});
