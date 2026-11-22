// The script-tag build: browser/observa.js, one minified file that puts the SDK on window.Observa.
// Committed, so a tag can be loaded straight from jsDelivr; CI checks it matches the source.
import { build } from 'esbuild';
import { readFileSync } from 'node:fs';

const { version } = JSON.parse(readFileSync(new URL('../package.json', import.meta.url), 'utf8'));
await build({
  entryPoints: [new URL('../src/browser.ts', import.meta.url).pathname],
  outfile: new URL('../browser/observa.js', import.meta.url).pathname,
  bundle: true,
  format: 'iife',
  globalName: 'Observa',
  minify: true,
  target: ['es2019'],
  legalComments: 'none',
  banner: { js: `/*! @observa/web ${version} · github.com/the1zozz/observa-sdks */` },
  logLevel: 'warning',
});
