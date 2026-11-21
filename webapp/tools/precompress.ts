import { readdirSync, readFileSync, statSync, writeFileSync } from "node:fs";
import path from "node:path";
import { brotliCompressSync, constants, gzipSync } from "node:zlib";

import type { Plugin } from "vite";

/** Text formats that shrink. Images and fonts are compressed already. */
const COMPRESSIBLE = new Set([
	".js",
	".mjs",
	".css",
	".svg",
	".json",
	".webmanifest",
	".txt",
	".md",
	".xml",
]);

/** Below this size, the encoding headers cost about what compression saves. */
const MIN_BYTES = 1024;

/**
 * Writes a Brotli and a gzip copy beside each compressible file of the build. The server sends the copy that the
 * browser accepts (ADR 0100), so nothing is compressed per request. `index.html` is left out, because the server
 * renders it with the config record of its deployment, and so is the `.vite` metadata, which no browser reads.
 */
export function precompress(): Plugin {
	let directory = "";
	return {
		name: "precompress",
		apply: "build",
		configResolved(config) {
			directory = path.resolve(config.root, config.build.outDir);
		},
		closeBundle: {
			order: "post",
			sequential: true,
			handler() {
				for (const file of filesUnder(directory)) {
					if (!COMPRESSIBLE.has(path.extname(file)) || statSync(file).size < MIN_BYTES) {
						continue;
					}
					const content = readFileSync(file);
					writeFileSync(
						`${file}.br`,
						brotliCompressSync(content, {
							params: {
								[constants.BROTLI_PARAM_QUALITY]: constants.BROTLI_MAX_QUALITY,
								[constants.BROTLI_PARAM_SIZE_HINT]: content.length,
							},
						}),
					);
					writeFileSync(`${file}.gz`, gzipSync(content, { level: constants.Z_BEST_COMPRESSION }));
				}
			},
		},
	};
}

function filesUnder(directory: string): string[] {
	return readdirSync(directory, { recursive: true, withFileTypes: true })
		.filter(
			(entry) => entry.isFile() && !path.relative(directory, entry.parentPath).startsWith(".vite"),
		)
		.map((entry) => path.join(entry.parentPath, entry.name));
}
