/**
 * Read-only helpers for the change view the container derives from the checkout before precompute
 * runs (`work/change/`, written by pi-change.ts): the commits of the reviewed range and the files it
 * touches. The diff itself reaches a script already parsed, as its second argument.
 */

import { readFile } from "node:fs/promises";

import { isJsonObject } from "./practice-contract.ts";

export interface ChangeCommit {
	sha: string;
	/** The full commit message: subject, blank line, body. */
	message: string;
	author: string;
	authoredAt: string;
	committer: string;
	committedAt: string;
	parents: string[];
}

export interface ChangedFile {
	/** git's status letter: A, M, D, R (renamed), C (copied), T (type changed). */
	status: string;
	path: string;
	oldPath?: string;
}

async function readChangeJson(changeDir: string | undefined, name: string): Promise<unknown> {
	if (!changeDir) return null;
	try {
		return JSON.parse(await readFile(`${changeDir}/${name}`, "utf8"));
	} catch {
		return null;
	}
}

function text(value: unknown): string {
	return typeof value === "string" ? value : "";
}

/** The commits from base to head, oldest first; empty when no change view was derived. */
export async function readCommits(changeDir: string | undefined): Promise<ChangeCommit[]> {
	const parsed = await readChangeJson(changeDir, "commits.json");
	if (!isJsonObject(parsed) || !Array.isArray(parsed.commits)) return [];
	return parsed.commits.filter(isJsonObject).map((commit) => ({
		sha: text(commit.sha),
		message: text(commit.message),
		author: text(commit.author),
		authoredAt: text(commit.authoredAt),
		committer: text(commit.committer),
		committedAt: text(commit.committedAt),
		parents: Array.isArray(commit.parents)
			? commit.parents.filter((p) => typeof p === "string")
			: [],
	}));
}

/** The files the change touches, with renames under both names; empty when no change view was derived. */
export async function readChangedFiles(changeDir: string | undefined): Promise<ChangedFile[]> {
	const parsed = await readChangeJson(changeDir, "files.json");
	if (!isJsonObject(parsed) || !Array.isArray(parsed.files)) return [];
	return parsed.files.filter(isJsonObject).map((file) => ({
		status: text(file.status),
		path: text(file.path),
		...(typeof file.oldPath === "string" ? { oldPath: file.oldPath } : {}),
	}));
}
