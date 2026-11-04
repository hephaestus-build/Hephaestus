import { readFileSync } from "node:fs";
import path from "node:path";

import { parseAllDocuments } from "yaml";

import { isRecord } from "./json.ts";

/** The resolved package versions of a lockfile's last document; pnpm 12 writes its own pin first. */
function lockedSnapshots(file: string): Set<string> {
	const lockfile: unknown = parseAllDocuments(readFileSync(file, "utf8")).at(-1)?.toJS();
	return new Set(
		isRecord(lockfile) && isRecord(lockfile.snapshots) ? Object.keys(lockfile.snapshots) : [],
	);
}

/**
 * How many locked package versions the install in `root` does not hold. pnpm keeps a copy of the
 * lockfile that it installed from; an install from an older lockfile builds a different bundle.
 */
export function staleInstall(root: string): number {
	const installed = lockedSnapshots(path.join(root, "node_modules", ".pnpm", "lock.yaml"));
	return [...lockedSnapshots(path.join(root, "pnpm-lock.yaml"))].filter(
		(key) => !installed.has(key),
	).length;
}
