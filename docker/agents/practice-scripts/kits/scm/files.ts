import { readdirSync } from "node:fs";
import nodePath from "node:path";

import { minimatch } from "minimatch";

// Hidden paths are reviewed code; exclude .git and symlinks. fs.globSync cannot include hidden paths.
export function globFilesSync(pattern: string, cwd: string): string[] {
	// minimatch matches nothing for an empty or comment pattern, so walking the tree would only read it.
	if (pattern === "" || pattern.startsWith("#")) {
		return [];
	}
	const files: string[] = [];
	const directories = [cwd];
	for (const directory of directories) {
		for (const entry of readdirSync(directory, { withFileTypes: true })) {
			const path = nodePath.join(directory, entry.name);
			if (entry.isDirectory()) {
				if (entry.name !== ".git") {
					directories.push(path);
				}
			} else if (entry.isFile()) {
				const name = nodePath.relative(cwd, path);
				if (minimatch(name.split(nodePath.sep).join("/"), pattern, { dot: true })) {
					files.push(name);
				}
			}
		}
	}
	return files.toSorted();
}
