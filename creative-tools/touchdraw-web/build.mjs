/**
 * One self-contained file, for the same reason AmboRacer is: a site embedding this sample must not
 * have to install anything, and AEP is bundled in rather than fetched from a CDN so the sample's
 * behaviour does not depend on what a CDN served that day.
 */
import { build } from "esbuild";

const BANNER = `/**
 * GENERATED FILE - DO NOT EDIT.
 *
 * Built from AmboExperienceSamples/creative-tools/touchdraw-web/src by build.mjs.
 * Edit the source there and run \`npm run build\`; changes made here are lost on the next build,
 * and a hand edit can leave calls to functions it removed.
 */`;

await build({
  entryPoints: ["src/main.ts"],
  outfile: "dist/touchdraw.js",
  banner: { js: BANNER },
  bundle: true,
  format: "esm",
  target: "es2022",
  sourcemap: true,
  minify: process.argv.includes("--minify"),
  logLevel: "info",
});
