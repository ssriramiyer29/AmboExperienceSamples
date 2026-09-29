/**
 * One self-contained file, because a site embedding this sample must not have to install anything.
 *
 * AEP is bundled in rather than loaded from a CDN: the packages are the platform's, they are
 * pinned by the tarballs in libs/, and a sample whose behaviour depends on what a CDN served that
 * day is not a reference for anything.
 */
import { build } from "esbuild";

/**
 * Said at the top of the artifact, not only in a README.
 *
 * The built bundle is copied into a different repository, where it is the only AmboRacer file
 * present and looks exactly like source. It was edited there once, directly - which deleted two
 * functions while leaving four calls to them, shipping a ReferenceError that fired the moment a
 * phone paired. A note in AMBORACER.md did not prevent that, because nothing made anyone open it.
 * This banner is in front of whoever opens the file to edit it.
 */
const BANNER = `/**
 * GENERATED FILE - DO NOT EDIT.
 *
 * Built from AmboExperienceSamples/motion-gaming/amboracer-web/src by build.mjs.
 * Edit the source there and run \`npm run build\`; changes made here are lost on the next build,
 * and a hand edit can leave calls to functions it removed.
 */`;

await build({
  entryPoints: ["src/main.ts"],
  outfile: "dist/amboracer.js",
  banner: { js: BANNER },
  bundle: true,
  format: "esm",
  target: "es2022",
  sourcemap: true,
  minify: process.argv.includes("--minify"),
  logLevel: "info",
});
