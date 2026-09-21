/**
 * Read-only helpers for the change view the container derives from the checkout before precompute
 * runs (`work/change/`, written by pi-change.ts): the commits of the reviewed range and the files it
 * touches. The diff itself reaches a script already parsed, as its second argument.
 */

import { readFile } from "node:fs/promises";

import { isJsonObject, text } from "./practice-contract.ts";

export interface ChangeCommit {
	sha: string;
	/** The full commit message: subject, blank line, body. */
	message: string;
	author: string;
	authoredAt: string;
	committer: string;
	committedAt: string;
	parents: string[];
	/** What the commit touched, against its first parent. */
	files: ChangedFile[];
	/** The line of commits.json that carries this commit's `sha`, 1-based; 0 when unknown. */
	line: number;
}

export interface ChangedFile {
	/** git's status letter: A, M, D, R (renamed), C (copied), T (type changed). */
	status: string;
	path: string;
	oldPath?: string;
}

/** The file's text, or null when the directory or the file is absent. */
async function readChangeText(dir: string | undefined, name: string): Promise<string | null> {
	if (dir === undefined || dir === "") {
		return null;
	}
	try {
		return await readFile(`${dir}/${name}`, "utf8");
	} catch {
		return null;
	}
}

function parseJson(source: string | null): unknown {
	if (source === null) {
		return null;
	}
	try {
		return JSON.parse(source);
	} catch {
		return null;
	}
}

function changedFiles(value: unknown): ChangedFile[] {
	if (!Array.isArray(value)) {
		return [];
	}
	return value.filter(isJsonObject).map((file) => ({
		status: text(file.status),
		path: text(file.path),
		...(typeof file.oldPath === "string" ? { oldPath: file.oldPath } : {}),
	}));
}

/**
 * The commits from base to head, oldest first, as the server staged them in the context
 * (`inputs/context/commits.json`); empty when the record was not captured.
 */
export async function readCommits(contextDir: string | undefined): Promise<ChangeCommit[]> {
	const source = await readChangeText(contextDir, "commits.json");
	const parsed = parseJson(source);
	if (!isJsonObject(parsed) || !Array.isArray(parsed.commits)) {
		return [];
	}
	// The server writes one field per line, so a commit's `sha` line is the coordinate a citation of
	// commits.json names.
	const lines = (source ?? "").split("\n");
	return parsed.commits.filter(isJsonObject).map((commit) => {
		const sha = text(commit.sha);
		return {
			sha,
			message: text(commit.message),
			author: text(commit.author),
			authoredAt: text(commit.authoredAt),
			committer: text(commit.committer),
			committedAt: text(commit.committedAt),
			parents: Array.isArray(commit.parents)
				? commit.parents.filter((p) => typeof p === "string")
				: [],
			files: changedFiles(commit.files),
			line:
				sha === ""
					? 0
					: lines.findIndex((line) => new RegExp(`"sha"\\s*:\\s*"${sha}"`, "u").test(line)) + 1,
		};
	});
}

/** The files the change touches, with renames under both names; empty when no change view was derived. */
export async function readChangedFiles(changeDir: string | undefined): Promise<ChangedFile[]> {
	const parsed = parseJson(await readChangeText(changeDir, "files.json"));
	return isJsonObject(parsed) ? changedFiles(parsed.files) : [];
}
