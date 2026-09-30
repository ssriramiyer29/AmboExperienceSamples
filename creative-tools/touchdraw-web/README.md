# TouchDraw (web)

Draw in a browser with a phone. The phone is both the pen and the palette.

## What this sample is for

AmboRacer shows one capability driving a game. TouchDraw shows **two capabilities composed onto one
surface**: `input.touch` is the paper and `input.controller` is the palette, and the Companion puts
them on a single AmboPad. Where a control button is, the button gets the contact; everywhere else the
touch surface does.

The palette is declared by the experience, not built into the Companion. The phone does not know
what "Undo" means - it is told where to put nine buttons and reports which one was pressed.

## The phone is not the whole canvas

The sheet is four padfuls: twice the window in each direction, and zoom changes how much of it the
pad covers - in both directions by the same factor, because the window's aspect has to keep matching
the pad's drawing area or the mapping stops being square. Zooming out stops at the whole sheet. So the pad is a window that moves,
which is why **draw and pan are separate modes** rather than two gestures. A finger drag is the
entire vocabulary of a touch surface, and there is no modifier key to spend, so it means one thing
at a time and the palette says which.

Strokes are stored in canvas coordinates rather than pad coordinates. Panning then moves the window
over the drawing instead of dragging the drawing along with it.

## Controls

| On the phone | On the keyboard | |
|---|---|---|
| Draw / Pan (right edge) | `d` / `p` | Which mode a drag is |
| Undo (right edge) | `u` | Removes the last finished stroke |
| Clr (right edge) | `c` | Clears the sheet |
| Blk Red Blu Grn (bottom) | `1`–`4` | Ink |
| Nib (bottom) | `w` | Cycles thin / medium / thick |
| Pinch with two fingers, in Pan mode | `-` / `+` | Zoom, about the centre of the window |

The keyboard is not a convenience. `input.controller` is declared **optional**, and that has to be
true rather than merely written down: a phone that refuses the palette must still leave a usable
sample.

## A known limitation, on purpose

`input.touch` normalises to its own bounds, so `0..1` is the pad rather than the phone's screen -
and an experience mapping those numbers onto a canvas needs the pad's aspect ratio or every circle
becomes an ellipse. `ready.aspectRatio` exists in the capability's schema for exactly this and **the
Companion does not emit it yet**, so `src/main.ts` assumes a wide landscape pad and says so where it
does it. The moment a Companion sends the real number, the geometry is rebuilt from it and the
assumption stops mattering.

Until then, expect strokes to be stretched if the phone's pad is not roughly 2:1.

## Build

```
npm install
npm run build      # dist/touchdraw.js, bundled, AEP included
npm run typecheck
```

Then serve the directory and open `index.html`. `?gateway=` or the `data-ambo-gateway` attribute
points it at a Gateway other than the page's own origin.
