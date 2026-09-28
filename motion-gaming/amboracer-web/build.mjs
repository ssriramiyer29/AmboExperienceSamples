/**
 * One self-contained file, because a site embedding this sample must not have to install anything.
 *
 * AEP is bundled in rather than loaded from a CDN: the packages are the platform's, they are
 * pinned by the tarballs in libs/, and a sample whose behaviour depends on what a CDN served that
 * day is not a reference for anything.
 */
import { build } from "esbuild";

await build({
  entryPoints: ["src/main.ts"],
  outfile: "dist/amboracer.js",
  bundle: true,
  format: "esm",
  target: "es2022",
  sourcemap: true,
  minify: process.argv.includes("--minify"),
  logLevel: "info",
});
