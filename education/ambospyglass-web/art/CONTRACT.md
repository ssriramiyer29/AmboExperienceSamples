# AmboSpyglass art contract

What to draw, how it must be built, and how we check it before it goes in.

There are **two deliveries in this document and they are not the same job.** Part One is the
sample — three painted scenes, which is what gets commissioned now. Part Two is the composable
actor set the product needs later. An illustrator quoting for one should not be shown the other.

---

# Part One — the sample: three painted scenes

A sample exists to demonstrate one idea. Here the idea is that **the same picture teaches any
language**: a child sees one scene and plays it in English, Hindi, Kannada, Spanish or French, and
only the words change. Three scenes is enough to show that; a fourth demonstrates nothing new.

| | |
|---|---|
| Scenes | 3 |
| Format | One illustration each, 16:9, 3840 × 2160 or larger |
| Plus | One annotation file per scene, described below |

Suggested three, chosen so the vocabulary barely overlaps: **a market or high street** (people,
jobs, clothes), **a safari or farm** (animals, describing words), **a beach** (objects, colours).

## What makes a scene teach rather than just look good

A painted scene contains whatever was painted. That is fine for the picture and fatal for the
lesson unless one thing is deliberate.

A prompt names up to three things — who, what they have, and its colour. The scene has to contain
**near-misses**: somebody who matches on two of the three and fails on the third. Without them,
spotting the only monkey wins without reading anything, the colour never mattered, and the game is
a search puzzle rather than a language lesson.

So, for **each** of the three or four intended targets in a scene:

- someone else with the **same item in the same colour** but a different job,
- someone else with the **same job and colour** but a different item,
- someone else with the **same job and item** in a different colour.

That is nine extra figures per target, and they are the difference between a picture and a lesson.
They should not look planted — a market is full of people and a beach is full of things, so this is
a composition note, not an addition.

`nearMissesFor()` in `rules/hunt.ts` measures this on the delivered annotation and reports a count
per clause. **A zero is a target that cannot be used.**

## The annotation

One JSON file per scene, delivered with it. Coordinates are in the painting's own pixels, measured
to the centre of the thing.

```json
{
  "id": "market",
  "name": "The market",
  "image": "scenes/market.png",
  "width": 3840,
  "height": 2160,
  "findables": [
    { "x": 820,  "y": 1240, "subject": "baker",   "trait": "apron", "colour": "white" },
    { "x": 1390, "y": 1180, "subject": "butcher", "trait": "apron", "colour": "white" },
    { "x": 2050, "y": 1310, "subject": "baker",   "trait": "scarf", "colour": "white" }
  ]
}
```

Every `subject`, `trait` and `colour` must be an id the topic declares — the five languages supply
the words for those ids, and an id nobody has a word for is a sentence with a hole in it.

Only list what a prompt could name. Background crowd, scenery and atmosphere stay out of the file:
anything listed becomes claimable, and an unlisted figure is just part of the painting.

## A cut-out per findable, for the prompt

The prompt shows the target's **picture beside its word**, in the language being learned, and says
it aloud. So every findable id also needs a small standalone image:

- `art/cutouts/subjects/<id>.png` and `art/cutouts/traits/<id>.png`
- 256 × 256, transparent background, the thing alone and centred, no scenery behind it
- Cropped from the scene art so the picture in the prompt is recognisably the thing in the painting

This is what lets a child who cannot yet read the word still play, and it is how the word and the
picture get learned together rather than separately.

## Drawing rules for a painted scene

1. **16:9**, 3840 × 2160 or larger. PNG or SVG.
2. **No text in the artwork.** The same scene is labelled in five languages; a painted sign that
   says BAKERY works in one of them. Signs may carry pictures, never words.
3. **Findable things must be unambiguous on their own.** See the acceptance test at the end — it is
   the only one that matters.
4. **Findable things must read at two sizes.** The whole scene is on screen at once and the lens
   magnifies about four times. In the far view a figure may be only ~15 px tall and is *meant* to be
   unreadable — that illegibility is the game. But nothing should need more than the lens gives.
5. **Keep findables off the edges.** The crosshair cannot reach within about 6% of any edge, so
   anything there can never be claimed. Treat the outer 6% as scenery only.
6. **Spread them out.** Two findables closer together than about 4% of the width cannot be told
   apart by the crosshair, and the claim goes to whichever is nearer by a few pixels.
7. **One light direction** across the whole scene.

---

# Part Two — the product: a composable actor set

Not commissioned yet. Recorded here because it is what makes the game endless rather than three
fixed puzzles, and because it changes what a brief must ask for.

Instead of painting every figure, paint **one background per topic** that contains nothing findable,
plus **thirteen actors** — eight subjects and five traits — which the generator places and
recombines into a different scene every round.

| | Count |
|---|---|
| Subjects | 8 |
| Traits | 5 |
| **Drawings** | **13** |
| Distinct characters they produce | 240 |

That only works if the trait's colourable part is **tinted at runtime**. The moment a trait arrives
with its colour baked in, 13 drawings becomes 240 renders and a sixth colour means redrawing
everything. This failure is invisible by inspection — the picture looks perfectly fine on its own
and only fails when the game asks for it in red — so `tools/check_art.mjs` checks it mechanically.

The cast: `baker`, `butcher`, `farmer`, `fisher`, `nurse`, `painter`, `sailor`, `teacher`.
The items: `shirt`, `scarf`, `apron`, `bag`, `umbrella`.

**Rules, all negative-tested by `tools/check_art.mjs --self-test`:**

1. One SVG per id, in `art/subjects/` and `art/traits/`.
2. Every file uses `viewBox="0 0 200 300"`, so a trait stacks on a subject with no transform.
3. Every trait has exactly one `<g id="tint">`, and its shapes use `fill="currentColor"`.
4. No subject contains `id="tint"` — colour belongs to the item, never the person.
5. A trait must not cover the head (`y < 90`) or the corner beyond `x = 132, y = 176`, where the
   tool is held. Sleeves over the upper arms are fine — a red shirt has red sleeves.
6. No text anywhere, for the reason in Part One.
7. No embedded raster, nothing fetched from off the page, no fonts, no stylesheets.
8. Flat fills. No gradients, filters, masks or patterns.
9. Under 40 KB per file.
10. Outline weight 4, round joins. The lens magnifies four times and a hairline becomes a smear.
11. Every subject carries a ground shadow in its own `<g id="shadow">`, or it floats.
12. Light from the upper left, matching the background and every other actor.

`art/subjects/baker.svg` and `art/traits/shirt.svg` ship as conformant references. Stacked and
tinted they render as six distinguishable bakers from two files, which is the claim this part rests
on.

---

# Acceptance

Mechanical first. For Part One, the annotation loads and `nearMissesFor` reports non-zero on every
clause of every intended target. For Part Two, `node tools/check_art.mjs --complete` passes.

Then the test that actually decides it, because everything above serves it:

> Show each findable thing **alone, cropped out, with no label**, to three children in the target
> age group and ask "what is this?"
>
> If they do not all say the same word, it has failed — however good it looks.

A picture that needs its caption to be understood cannot teach the caption, which is the only thing
this game does.
