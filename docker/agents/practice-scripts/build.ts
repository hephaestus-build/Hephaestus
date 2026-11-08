import { spawnSync } from "node:child_process";
import { readFile, rm, writeFile } from "node:fs/promises";
import path from "node:path";

import { isJsonObject } from "./lib/core/json.ts";

const output = path.join(import.meta.dirname, "dist");
await rm(output, { recursive: true, force: true });
const compiled = spawnSync(
	process.execPath,
	[
		path.resolve("node_modules/typescript/bin/tsc"),
		"-p",
		path.join(import.meta.dirname, "tsconfig.build.json"),
		"--outDir",
		output,
	],
	{ stdio: "inherit" },
);
if (compiled.error !== undefined) {
	throw compiled.error;
}
if (compiled.status !== 0) {
	throw new Error("The practice toolkit did not compile");
}

const manifest: unknown = JSON.parse(
	await readFile(path.join(import.meta.dirname, "package.json"), "utf8"),
);
if (!isJsonObject(manifest) || !isJsonObject(manifest.exports)) {
	throw new Error("Invalid toolkit export map");
}
const entries = Object.entries(manifest.exports).map(([name, file]) => {
	if (typeof file !== "string" || !file.endsWith(".ts")) {
		throw new Error("Expected a TypeScript toolkit export");
	}
	return [name, `${file.slice(0, -3)}.js`] as const;
});
await writeFile(
	path.join(output, "package.json"),
	`${JSON.stringify({ ...manifest, exports: Object.fromEntries(entries) }, null, 2)}\n`,
);
