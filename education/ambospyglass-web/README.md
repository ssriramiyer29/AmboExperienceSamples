# AmboSpyglass

A Find-Waldo in the language you are learning. The browser holds a crowd too dense to read; the
phone's facing moves a magnifying lens over it; a tap claims whoever is under the crosshair. The
sentence is in the language being learned, the picture is the same in every language.

**The sample for `motion.orientation` as a pointing instrument.** AmboRacer already steers with the
same capability, and the difference is the point: steering maps one axis to a *rate*, aiming maps
two axes to a *position* that stays put when the phone does. The second is the harder claim.

## Run it

```bash
npm install
npm run build
```

Then serve this folder beside a Gateway and open `index.html`. The page talks to the Gateway at its
own origin by default; `?gateway=` or `data-ambo-gateway` on the container element overrides it.

Scan the QR with the AmboKit Companion, press **Centre** once to anchor the lens, then sweep.

```bash
npm test          # the rules, without a phone or a browser
npm run typecheck
npm run check:art # delivered artwork against art/CONTRACT.md
```

## What is where

| | |
|---|---|
| `rules/hunt.ts` | The game, with no DOM and no clock. Crowd generation, the lens, the verdict, the five languages. |
| `src/main.ts` | Session wiring: capabilities, the palette, the prompt. |
| `src/render.ts` | Drawing. The crowd twice — tiny and whole, then magnified inside the lens. |
| `tests/hunt.test.ts` | 42 tests, run with `node --test`. |
| `art/CONTRACT.md` | The illustrator brief. Two deliveries, deliberately separated. |

## Three things worth knowing before reading the code

**The phone is not pointing at the screen, and cannot be.** Orientation reports which way the
device faces; nothing says where the screen is in the room. `recentre` pins "this facing" to "the
lens is here", and the mapping is absolute from that anchor — point the same way twice and you land
in the same place, which is what makes "I already searched there" mean anything in a search game.

**The distractors are the lesson.** The crowd is generated so that every clause of a prompt has
near-misses failing on that clause alone, so recognising one word cannot win. A wrong claim reports
*which* clause was lost, which turns the game loop into an assessment instrument for free. For a
painted scene the guarantee cannot be constructed, so `nearMissesFor` measures it instead and a
zero is a target that cannot be used.

**Word order is data, not code.** Hindi puts the colour before the noun and the verb last; Kannada
glues the accusative to the noun. Each lexicon carries its own sentence template, its own gender
keys and its own inflected forms, so adding a language touches no logic.

## Not done

- The Hindi and Kannada have **not been read by a native speaker**. The case marking is where they
  are least certain, and the Kannada less certain than the Hindi.
- The artwork is placeholder vectors. `art/CONTRACT.md` is the brief for the real thing.
- `package-lock.json` is absent — generate one with `npm install` on a machine that can reach the
  registry.
- The sweep gain, the follow time and the lens radius are chosen by looking at a prototype, not by
  measuring a handset. `motion.orientation` is one of the capabilities with no published envelope.
