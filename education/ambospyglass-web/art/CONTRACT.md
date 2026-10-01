# AmboSpyglass art contract

What to draw, how it must be built, and how we check it before it goes in.

This document is the brief. Everything in **Rules** is checked mechanically by
`tools/check_art.mjs`; everything in **Direction** is judged by the acceptance test at the end.

---

## Why thirteen drawings and not two hundred

The crowd on screen is a few hundred characters, and every one of them is a **subject** picture and
a **trait** picture stacked on top of each other, with the trait recoloured. Eight subjects and five
traits recombine into 240 visibly different people.

That only works if the trait's colourable part can be **tinted at runtime**. The moment a trait
arrives as a flat picture with its colour baked in, the count goes from 13 drawings to 240 renders
per topic, and adding a sixth colour means redrawing everything. The tint layer is therefore the
single most important thing in this document.

| | Count |
|---|---|
| Subjects | 8 |
| Traits | 5 |
| **Drawings needed** | **13** |
| Distinct characters they produce | 240 |

---

## The cast

**Subjects** — `art/subjects/<id>.svg`

| id | Who | Must be readable from |
|---|---|---|
| `baker` | Baker | Tall white hat, rolling pin or loaves |
| `butcher` | Butcher | Striped apron, cleaver |
| `farmer` | Farmer | Wide-brimmed hat, pitchfork or crop |
| `fisher` | Fisherman | Oilskin hat, rod, net or fish |
| `nurse` | Nurse | Clinical uniform, cross, clipboard |
| `painter` | Painter (pictures, not walls) | Beret, palette and brush |
| `sailor` | Sailor | Flat white cap with band, anchor or rope |
| `teacher` | Teacher | Books, glasses, pointer or blackboard |

**Traits** — `art/traits/<id>.svg`

| id | What | Where it sits |
|---|---|---|
| `shirt` | Shirt over the torso | Torso |
| `scarf` | Scarf around the neck, one tail hanging | Neck and upper chest |
| `apron` | Apron over the front, neck strap visible | Chest to knees |
| `bag` | Satchel at the hip with a strap across the body | Left hip, strap over the right shoulder |
| `umbrella` | Open umbrella held up and to one side | Left of the figure, above shoulder height |

---

## Rules

These are checked. A delivery that breaks one is rejected by the tool, not by an opinion.

1. **One SVG per id**, named exactly as the tables above, in `art/subjects/` and `art/traits/`.

2. **Shared canvas.** Every file, subject and trait alike, uses `viewBox="0 0 200 300"`. The two are
   stacked without any transform, so a trait must be drawn in the same space as the body it sits on:
   feet at `y=300`, centre of the body at `x=100`, top of the head around `y=40`.

3. **Every trait has exactly one `<g id="tint">`**, and everything inside it is the colourable part.
   Shapes inside it must use `fill="currentColor"` and, if stroked, `stroke="currentColor"`. The
   renderer sets the CSS `color` property and the tint follows. Parts that are *not* coloured — an
   umbrella's handle and ribs, a bag's buckle, the outline — stay outside that group with their own
   fills.

4. **No subject contains `id="tint"`.** The colour in a prompt belongs to the item, never the person.

5. **The subject owns the head and the working hand.** A trait must not cover headwear, face or the
   tool, because the role is read from those and a covered role makes the prompt unanswerable. In
   the shared canvas that means keeping clear of `y < 90` (head and hat) and of the corner beyond
   `x = 132, y = 176`, where the tool is held. Sleeves over the upper arms are fine and expected —
   a red shirt has red sleeves.

6. **No text anywhere in the artwork.** The same drawing is labelled in English, Hindi, Kannada,
   Spanish and French. A word baked into a picture is a picture that only works in one of them.

7. **No embedded raster and nothing fetched.** No `<image>`, no external `href`, no `@font-face`, no
   linked stylesheet. The sample is one self-contained page and these are part of it.

8. **Flat fills.** Solid colour and strokes. Gradients, filters, blurs and masks are rejected: they
   multiply cost at this count, and `filter` in particular is slow with several hundred on screen.

9. **Under 40 KB per file**, optimised, with editor metadata stripped.

10. **Outline weight 4** in canvas units, round joins. The lens magnifies about four times, and a
    hairline magnified is a grey smear.

---

## Direction

- **Cartoon is fine; vague is not.** The test is not realism, it is whether a six-year-old names it
  without being told. Push the one or two props that carry the job and drop the rest.
- **Readable at two sizes and nothing between.** Inside the lens a figure is around 120 px tall and
  every prop must read. Outside the lens it is about 15 px, and the renderer draws a plain silhouette
  with the trait's colour on it — *that* far view is generated, not drawn, so do not supply one.
  Illegibility at a distance is the game, not a defect.
- **Bodies stay neutral.** Skin, clothing and hair should not carry meaning, because the prompt can
  only name three things and anything else that looks distinguishing is a false lead. Vary posture
  slightly, not palette.
- **These six colours**, and the tint must stay distinguishable in all of them against a dark
  background: red `#d64545`, blue `#2f7fd6`, green `#2fae66`, yellow `#e8c33a`, white `#fbfdff`,
  black `#333f4d`.
- **It is used to teach Hindi and Kannada.** A uniform that reads as a fireman only in one country
  is a uniform that fails half the audience. Prefer what is recognisable across the markets the
  language list implies.

---

## Acceptance

Mechanical first: `node tools/check_art.mjs` must pass on the whole delivery.

Then the test that actually matters, because everything above is in service of it:

> Show each picture **alone, with no label**, to three children in the target age group and ask
> "what is this?"
>
> If they do not all say the same word, the drawing has failed — however good it looks.

A picture that needs its caption to be understood cannot teach the caption, which is the only thing
this game does.
