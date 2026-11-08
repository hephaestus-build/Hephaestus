/**
 * What a practice script can import: the names in the toolkit's export map, and nothing else. An
 * export map alone stops a deep import by name, but a relative or an absolute path still reaches any
 * file. The refusal is the error line of a stored script from before this API.
 */
import { readFileSync } from "node:fs";
import { registerHooks } from "node:module";
import path from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

import { z } from "zod";

const manifestSchema = z.object({ exports: z.record(z.string(), z.string()) });
const INSTALL = path.resolve(import.meta.dirname, "../..");
const PACKAGE_NAME = "@hephaestus/practice-scripts";

function publicEntries(): ReadonlyMap<string, string> {
	const manifest: unknown = JSON.parse(readFileSync(path.join(INSTALL, "package.json"), "utf8"));
	const { exports: entries } = manifestSchema.parse(manifest);
	return new Map(
		Object.entries(entries).map(([subpath, file]) => [
			path.posix.join(PACKAGE_NAME, subpath),
			pathToFileURL(path.join(INSTALL, file)).href,
		]),
	);
}

export const isPracticeScriptPath = (file: string): boolean =>
	path.basename(path.dirname(file)) === "practices";

const isScript = (parentURL: string | undefined) =>
	parentURL?.startsWith("file:") === true && isPracticeScriptPath(fileURLToPath(parentURL));

/** Resolve stored scripts in `practices` through the export map; private toolkit fixtures stage elsewhere. */
export function guardScriptImports(): void {
	const entries = publicEntries();
	registerHooks({
		resolve(specifier, context, nextResolve) {
			const url = entries.get(specifier);
			if (url !== undefined) {
				return nextResolve(url, context);
			}
			if (!isScript(context.parentURL)) {
				return nextResolve(specifier, context);
			}
			throw new Error(
				`A practice script can import only ${[...entries.keys()].join(", ")}, not ${specifier}. Rewrite the script for this API.`,
			);
		},
	});
}
