// Builds the two files Figma loads: dist/code.js (the main thread) and dist/ui.html (the panel, with its script
// inlined, because Figma loads the panel from a string and cannot fetch a second file).
import { mkdir, readFile, writeFile } from "node:fs/promises";
import path from "node:path";

import { build } from "vite";

const root = import.meta.dirname;
const dist = path.join(root, "dist");
// Figma's main-thread sandbox runs ES2017 reliably; the panel is a browser frame, held to the same target for one rule.
const target = "es2017";

async function bundle(entry: string, name: string): Promise<string> {
	const result = await build({
		configFile: false,
		root,
		logLevel: "warn",
		build: {
			write: false,
			target,
			minify: false,
			lib: { entry, formats: ["iife"], name, fileName: () => `${name}.js` },
		},
	});
	if (!Array.isArray(result) && !("output" in result)) {
		throw new Error(`Building ${entry} started a watcher instead of writing a bundle.`);
	}
	const outputs = Array.isArray(result) ? result : [result];
	const chunk = outputs.flatMap((output) => output.output).find((file) => file.type === "chunk");
	if (chunk?.type !== "chunk") {
		throw new Error(`Building ${entry} produced no script.`);
	}
	return chunk.code;
}

const scriptTag = '<script type="module" src="./ui.ts"></script>';
const template = await readFile(path.join(root, "src", "ui.html"), "utf8");
if (!template.includes(scriptTag)) {
	throw new Error(`src/ui.html must load its script with ${scriptTag}.`);
}
const [code, ui] = await Promise.all([
	bundle("src/code.ts", "hephaestusPlugin"),
	bundle("src/ui.ts", "hephaestusPanel"),
]);
await mkdir(dist, { recursive: true });
await writeFile(path.join(dist, "code.js"), code);
// A function replacement, so a `$` in the bundle is never read as a replacement pattern.
await writeFile(
	path.join(dist, "ui.html"),
	template.replace(scriptTag, () => `<script>${ui}</script>`),
);
