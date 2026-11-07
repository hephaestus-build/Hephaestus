// pi-change-view.ts — derives the change view before anything untrusted runs.
//
// The workspace initializer starts this once it has filled the workspace, with no credential in its environment. Only
// the image's code and the repository's data exist then, so Git's answers can be trusted here, and a diff, which no hash
// can prove, comes from nowhere that an untrusted command has touched. Later in a review, Git runs on the tools side.

import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import path from "node:path";

import {
	CHANGE_ROOT,
	CHECKOUT_COMMIT,
	checkedOutCommit,
	readChange,
	readDescription,
	writeChangeView,
} from "./pi-change.ts";
import { resolveTaskPaths } from "./pi-task-paths.ts";

export function deriveChangeView(root: string): void {
	const task: unknown = JSON.parse(readFileSync(`${root}/task.json`, "utf8"));
	const paths = resolveTaskPaths(root, task);
	const change = readChange(paths.contextRoot);
	if (change !== null) {
		writeChangeView(root, paths.repositoryRoot, change, readDescription(paths.contextRoot));
	}
	const commit = checkedOutCommit(paths.repositoryRoot);
	if (commit !== null) {
		mkdirSync(path.resolve(root, CHANGE_ROOT), { recursive: true });
		writeFileSync(path.resolve(root, CHECKOUT_COMMIT), commit);
	}
}

if (import.meta.main) {
	deriveChangeView("/workspace");
}
