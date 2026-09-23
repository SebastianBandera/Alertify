// Writes a gzip copy next to every compressible build file, so nginx serves it
// with gzip_static instead of compressing on each request.
import { readdirSync, readFileSync, statSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { constants, gzipSync } from 'node:zlib';

const COMPRESSIBLE = /\.(?:js|css|html|svg|json|txt)$/;
/* Below this size the gzip header and the extra lookup cost more than they save. */
const MIN_BYTES = 1024;

const root = process.argv[2];
if (!root) throw new Error('Usage: node precompress.mjs <directory>');

let files = 0;
let rawBytes = 0;
let gzipBytes = 0;

function precompress(directory) {
  for (const name of readdirSync(directory)) {
    const path = join(directory, name);
    if (statSync(path).isDirectory()) {
      precompress(path);
      continue;
    }
    if (!COMPRESSIBLE.test(name)) continue;
    const content = readFileSync(path);
    if (content.length < MIN_BYTES) continue;
    const compressed = gzipSync(content, { level: constants.Z_BEST_COMPRESSION });
    if (compressed.length >= content.length) continue;
    writeFileSync(`${path}.gz`, compressed);
    files++;
    rawBytes += content.length;
    gzipBytes += compressed.length;
  }
}

precompress(root);
console.log(`Precompressed ${files} files: ${Math.round(rawBytes / 1024)} kB -> ${Math.round(gzipBytes / 1024)} kB gzip.`);
