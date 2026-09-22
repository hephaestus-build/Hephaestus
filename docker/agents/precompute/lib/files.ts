import { globSync } from "node:fs";
import path from "node:path";

/** Return matching files in stable order. */
export function globFilesSync(pattern: string, cwd: string): string[] {
	return globSync(pattern, { cwd, withFileTypes: true })
		.filter((entry) => entry.isFile())
		.map((entry) => path.relative(cwd, path.join(entry.parentPath, entry.name)))
		.toSorted();
}
