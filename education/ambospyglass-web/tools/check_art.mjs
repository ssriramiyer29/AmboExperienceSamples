#!/usr/bin/env node
/**
 * Checks delivered artwork against art/CONTRACT.md.
 *
 * It exists because the expensive failure here is silent: a trait whose colour is baked in instead
 * of tinted looks perfectly fine on its own and only fails when the game asks for it in red, by
 * which time the invoice is paid. Every rule below is one that cannot be seen by looking at the
 * picture.
 *
 *   node tools/check_art.mjs              report, and allow assets not yet delivered
 *   node tools/check_art.mjs --complete   also require all thirteen
 *   node tools/check_art.mjs --self-test  prove the checks fail on art that breaks them
 *
 * Deliberately regex-based rather than built on an XML parser: this is a gate on a handful of small
 * files with a known shape, and a dependency in a sample is a dependency a licensee inherits.
 * The cost of that choice is stated where it bites - see `geometryOf`, which reads the shapes it
 * can and says how many it could not.
 */
import { readFileSync, existsSync, readdirSync } from "node:fs";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");

const SUBJECTS = ["baker", "butcher", "farmer", "fisher", "nurse", "painter", "sailor", "teacher"];
const TRAITS = ["shirt", "scarf", "apron", "bag", "umbrella"];

const VIEW_BOX = "0 0 200 300";
const MAX_BYTES = 40 * 1024;
/**
 * The subject owns the head and the working hand; a trait covering either hides the role.
 *
 * The hand is a REGION, not a half-plane. The first version of this banned everything right of
 * x=130 and promptly rejected the reference shirt, whose sleeve covers the upper arm - which a
 * shirt obviously must. What has to stay clear is where the tool is held: low and to the right.
 */
const HEAD_BELOW = 90;
const HAND_LEFT = 132;
const HAND_TOP = 176;

const problems = [];
const notes = [];
function fail(file, message) { problems.push(`${file}: ${message}`); }

/**
 * The coordinates this can actually see: explicit rect/circle/ellipse/line attributes.
 *
 * Path data is NOT read. Parsing `d` properly means implementing arcs, relative commands and
 * implicit repeats, which is a real parser and would be wrong in a way nobody noticed. So the
 * count of unread paths is reported instead of being quietly treated as zero - an unchecked file
 * that reports "0 problems" is the failure mode this whole tool exists to avoid.
 */
function geometryOf(svg) {
  const points = [];
  const number = (text, name) => {
    const found = new RegExp(`${name}\\s*=\\s*"(-?[\\d.]+)"`).exec(text);
    return found === null ? null : Number(found[1]);
  };
  for (const tag of svg.match(/<(rect|circle|ellipse|line)\b[^>]*>/g) ?? []) {
    const x = number(tag, "x") ?? number(tag, "cx") ?? number(tag, "x1");
    const y = number(tag, "y") ?? number(tag, "cy") ?? number(tag, "y1");
    const width = number(tag, "width") ?? number(tag, "r") ?? number(tag, "rx") ?? 0;
    const height = number(tag, "height") ?? number(tag, "r") ?? number(tag, "ry") ?? 0;
    if (x === null || y === null) continue;
    points.push({ left: x, top: y, right: x + width, bottom: y + height });
  }
  return { points, unreadPaths: (svg.match(/<path\b/g) ?? []).length };
}

function checkCommon(name, svg, bytes) {
  if (bytes > MAX_BYTES) fail(name, `${Math.round(bytes / 1024)} KB, over the 40 KB limit`);
  const viewBox = /viewBox\s*=\s*"([^"]+)"/.exec(svg);
  if (viewBox === null) fail(name, "no viewBox");
  else if (viewBox[1].trim().replace(/,/g, " ").replace(/\s+/g, " ") !== VIEW_BOX) {
    fail(name, `viewBox is "${viewBox[1]}", must be "${VIEW_BOX}" so subject and trait stack`);
  }
  if (/<text\b|<tspan\b/.test(svg)) fail(name, "contains text - the same drawing is labelled in five languages");
  if (/<image\b/.test(svg)) fail(name, "embeds a raster image");
  if (/href\s*=\s*"\s*(?:https?:)?\/\//i.test(svg)) fail(name, "references something off the page");
  if (/@font-face|<link\b/.test(svg)) fail(name, "pulls in a font or stylesheet");
  for (const banned of ["linearGradient", "radialGradient", "filter", "mask", "clipPath", "pattern"]) {
    if (new RegExp(`<${banned}\\b`).test(svg)) fail(name, `uses <${banned}> - flat fills only`);
  }
}

function checkSubject(name, svg) {
  if (/id\s*=\s*"tint"/.test(svg)) {
    fail(name, 'has id="tint" - colour belongs to the item, never the person');
  }
}

function checkTrait(name, svg) {
  const tints = svg.match(/<g[^>]*\bid\s*=\s*"tint"/g) ?? [];
  if (tints.length === 0) {
    fail(name, 'no <g id="tint"> - nothing to recolour, so this trait can only ever be one colour');
    return;
  }
  if (tints.length > 1) fail(name, `${tints.length} groups with id="tint", must be exactly one`);

  // Everything inside the tint group has to defer its colour, or tinting silently does nothing.
  const open = svg.indexOf(tints[0]);
  const body = svg.slice(open, svg.indexOf("</g>", open));
  const hardFills = body.match(/(?:fill|stroke)\s*=\s*"(?!currentColor|none)[^"]+"/g) ?? [];
  for (const hard of hardFills) {
    fail(name, `inside id="tint": ${hard} - must be currentColor, or the colour is baked in`);
  }
  if (!/currentColor/.test(body)) fail(name, 'id="tint" has no currentColor shape at all');

  const { points, unreadPaths } = geometryOf(body);
  for (const box of points) {
    if (box.bottom < HEAD_BELOW) fail(name, `covers the head (y ${box.top}-${box.bottom}, must clear ${HEAD_BELOW})`);
    if (box.right > HAND_LEFT && box.bottom > HAND_TOP && box.left > 100) {
      fail(name, `covers the working hand (reaches ${box.right},${box.bottom}; that corner past ${HAND_LEFT},${HAND_TOP} is the tool)`);
    }
  }
  if (unreadPaths > 0) {
    notes.push(`${name}: ${unreadPaths} path(s) not geometry-checked - verify by eye that they clear the head and the hand`);
  }
}

function inspect(kind, ids) {
  const folder = join(ROOT, "art", kind === "subject" ? "subjects" : "traits");
  const missing = [];
  let seen = 0;
  for (const id of ids) {
    const path = join(folder, `${id}.svg`);
    if (!existsSync(path)) { missing.push(id); continue; }
    const svg = readFileSync(path, "utf8");
    const name = `art/${kind === "subject" ? "subjects" : "traits"}/${id}.svg`;
    checkCommon(name, svg, Buffer.byteLength(svg));
    if (kind === "subject") checkSubject(name, svg); else checkTrait(name, svg);
    seen += 1;
  }
  // A file nobody asked for is usually a rename that half happened.
  const extra = existsSync(folder)
    ? readdirSync(folder).filter((f) => f.endsWith(".svg") && !ids.includes(f.replace(/\.svg$/, "")))
    : [];
  for (const file of extra) fail(`art/${kind}s/${file}`, "not one of the ids the topic declares");
  return { seen, missing };
}

function selfTest() {
  // Every check negative-tested, which this repository requires: a check that has only ever passed
  // is a check nobody has seen work.
  const cases = [
    ["baked-in colour", "trait", '<svg viewBox="0 0 200 300"><g id="tint"><rect x="60" y="130" width="80" height="60" fill="#d64545"/></g></svg>', /baked in/],
    ["no tint group", "trait", '<svg viewBox="0 0 200 300"><rect x="60" y="130" width="80" height="60" fill="currentColor"/></svg>', /nothing to recolour/],
    ["two tint groups", "trait", '<svg viewBox="0 0 200 300"><g id="tint"><rect x="60" y="130" width="8" height="6" fill="currentColor"/></g><g id="tint"><rect x="60" y="130" width="8" height="6" fill="currentColor"/></g></svg>', /must be exactly one/],
    ["covers the head", "trait", '<svg viewBox="0 0 200 300"><g id="tint"><rect x="70" y="40" width="60" height="40" fill="currentColor"/></g></svg>', /covers the head/],
    ["covers the hand", "trait", '<svg viewBox="0 0 200 300"><g id="tint"><rect x="150" y="190" width="40" height="40" fill="currentColor"/></g></svg>', /working hand/],
    ["a sleeve, which is not the hand", "trait", '<svg viewBox="0 0 200 300"><g id="tint"><rect x="138" y="142" width="18" height="34" fill="currentColor"/></g></svg>', null],
    ["wrong viewBox", "trait", '<svg viewBox="0 0 100 100"><g id="tint"><rect x="10" y="10" width="8" height="8" fill="currentColor"/></g></svg>', /must be "0 0 200 300"/],
    ["text in the art", "subject", '<svg viewBox="0 0 200 300"><text x="10" y="10">baker</text></svg>', /contains text/],
    ["tinted subject", "subject", '<svg viewBox="0 0 200 300"><g id="tint"><rect x="1" y="1" width="1" height="1" fill="currentColor"/></g></svg>', /never the person/],
    ["a gradient", "subject", '<svg viewBox="0 0 200 300"><linearGradient id="g"/></svg>', /flat fills only/],
    ["an external image", "subject", '<svg viewBox="0 0 200 300"><image href="https://example.com/x.png"/></svg>', /raster|off the page/],
  ];
  let bad = 0;
  for (const [label, kind, svg, expected] of cases) {
    problems.length = 0;
    checkCommon(label, svg, Buffer.byteLength(svg));
    if (kind === "subject") checkSubject(label, svg); else checkTrait(label, svg);
    // A null expectation means the opposite case: legitimate art that must NOT be rejected. Both
    // directions belong here, because a rule that is too strict costs an illustrator a redraw.
    const ok = expected === null ? problems.length === 0 : problems.some((p) => expected.test(p));
    console.log(`${ok ? "ok  " : "FAIL"}  ${label} is ${expected === null ? "accepted" : "rejected"}`);
    if (!ok) { bad += 1; console.log(`      got: ${problems.join(" | ") || "(nothing)"}`); }
  }
  // And the mirror: conformant art must pass, or the gate is just a wall.
  problems.length = 0;
  const good = '<svg viewBox="0 0 200 300"><g id="tint"><rect x="62" y="128" width="76" height="72" fill="currentColor"/></g><rect x="66" y="196" width="8" height="10" fill="#6b5848"/></svg>';
  checkCommon("a conformant trait", good, Buffer.byteLength(good));
  checkTrait("a conformant trait", good);
  const clean = problems.length === 0;
  console.log(`${clean ? "ok  " : "FAIL"}  a conformant trait is accepted`);
  if (!clean) { bad += 1; console.log(`      got: ${problems.join(" | ")}`); }
  console.log(bad === 0 ? "\nevery check is negative-tested." : `\n${bad} check(s) did not work.`);
  return bad === 0 ? 0 : 1;
}

if (process.argv.includes("--self-test")) process.exit(selfTest());

const subjects = inspect("subject", SUBJECTS);
const traits = inspect("trait", TRAITS);
const delivered = subjects.seen + traits.seen;
const complete = process.argv.includes("--complete");

for (const note of notes) console.log(`note  ${note}`);
for (const problem of problems) console.log(`FAIL  ${problem}`);

const missing = [...subjects.missing.map((i) => `subjects/${i}`), ...traits.missing.map((i) => `traits/${i}`)];
if (missing.length > 0) {
  console.log(`${complete ? "FAIL  " : "todo  "}${missing.length} not yet delivered: ${missing.join(", ")}`);
}

console.log(`\n${delivered} of ${SUBJECTS.length + TRAITS.length} drawings checked, ${problems.length} problem(s).`);
process.exit(problems.length > 0 || (complete && missing.length > 0) ? 1 : 0);
