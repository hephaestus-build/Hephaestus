/** Read captured commit metadata and the container-derived change file list. */

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
	/** Changed-line counts where the record carries them (the commit record does; the change view does not). */
	additions?: number;
	deletions?: number;
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
		...(typeof file.additions === "number" && typeof file.deletions === "number"
			? { additions: file.additions, deletions: file.deletions }
			: {}),
	}));
}

/**
 * The commits from base to head, oldest first, as the server staged them in the context
 * (`commits.json` in the task-declared context); empty when the record was not captured.
 */
export async function readCommits(contextDir: string | undefined): Promise<ChangeCommit[]> {
	return (await readCapturedCommits(contextDir)) ?? [];
}

/** Null means missing or malformed capture; an empty array is a captured empty range. */
export async function readCapturedCommits(
	contextDir: string | undefined,
): Promise<ChangeCommit[] | null> {
	const source = await readChangeText(contextDir, "commits.json");
	const parsed = parseJson(source);
	if (
		!isJsonObject(parsed) ||
		!Array.isArray(parsed.commits) ||
		!parsed.commits.every(isJsonObject)
	) {
		return null;
	}
	const lines = (source ?? "").split("\n");
	return parsed.commits.map((commit) => {
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
