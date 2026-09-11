import { readdirSync } from "node:fs";
import { join, relative, sep } from "node:path";

import { makeRe } from "minimatch";

/** Return matching regular files, including hidden paths, without following symlinks. */
export function globFilesSync(pattern: string, cwd: string): string[] {
	const matcher = makeRe(pattern, { dot: true });
	if (!matcher) return [];
	const files: string[] = [];
	const directories = [cwd];
	for (const directory of directories) {
		for (const entry of readdirSync(directory, { withFileTypes: true })) {
			const path = join(directory, entry.name);
			if (entry.isDirectory()) directories.push(path);
			else if (entry.isFile()) {
				const name = relative(cwd, path);
				if (matcher.test(name.split(sep).join("/"))) files.push(name);
			}
		}
	}
	return files.toSorted();
}
