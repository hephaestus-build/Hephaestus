// pi-pinned-object.ts — what a pinned commit holds at a path, read through an untrusted source.
//
// Git runs on the tools side, which the loop does not trust. A Git object is named by the hash of its bytes, so the loop
// walks from the commit id that the capture pinned: commit, each tree, then the blob, checking each object's hash before
// it reads the next id from it. A hostile source can refuse or stall, and that reads as unreadable. It cannot supply a
// byte that the pinned commit does not hold.
//
// A hash proves only that bytes match their id, so anyone can make a commit whose id is right. A commit id that a citation
// names therefore counts only when `PinnedHistory` reaches it from a commit the capture pinned, parent by parent.

import { createHash } from "node:crypto";

import { isCommitId, type PinnedBlob, safeRepositoryPath } from "./pi-change.ts";
import type { ToolClient } from "./tool-client.ts";
import { LIMITS } from "./tool-protocol.ts";

export type ObjectType = "commit" | "tree" | "blob";

/** Where the loop gets Git objects. Nothing that it answers is trusted. */
export interface ObjectSource {
	/** The content of the object, without its header; at most `limit` bytes, or null when it cannot answer. */
	read: (type: ObjectType, id: string, limit: number) => Promise<Buffer | null>;
	/** The object's size as the source claims it: a hint that only saves a read. Null when it cannot answer. */
	size: (id: string) => Promise<number | null>;
}

/** The most bytes of a commit or a tree that the walk reads: what one `exec` streams, over 100,000 tree entries. */
export const TREE_BYTES = LIMITS.execStreamBytes;

/** The most commits that the walk visits to find a cited one, across an attempt. */
export const HISTORY_COMMITS = 5000;

const REGULAR_MODES = new Set(["100644", "100755"]);
const DIRECTORY_MODE = "40000";

interface TreeEntry {
	mode: string;
	id: string;
}

/** How long the tools side may take to answer for one object. */
const OBJECT_TIMEOUT_MS = 30_000;

/** The Git objects of `repository`, from the tools side. A failed call answers null: it is not a claim about the object. */
export function toolObjects(tools: ToolClient, repository: string): ObjectSource {
	const git = async (args: string[], limit: number): Promise<Buffer | null> => {
		const chunks: Buffer[] = [];
		try {
			const exit = await tools.exec(
				{
					argv: ["git", "-C", repository, ...args],
					cwd: repository,
					timeoutMs: OBJECT_TIMEOUT_MS,
					maxOutputBytes: limit,
				},
				(chunk) => {
					chunks.push(chunk);
				},
			);
			return exit.code === 0 && !exit.truncated ? Buffer.concat(chunks) : null;
		} catch {
			return null;
		}
	};
	return {
		read: async (type, id, limit) => git(["cat-file", type, id], limit),
		size: async (id) => {
			const answer = await git(["cat-file", "-s", id], 32);
			const size = answer === null ? Number.NaN : Number(answer.toString("latin1").trim());
			return Number.isSafeInteger(size) && size >= 0 ? size : null;
		},
	};
}

/** The hash that names a Git object: SHA-1 for a 40-character id, SHA-256 for a 64-character one. */
function objectId(type: ObjectType, content: Buffer, idLength: number): string {
	return createHash(idLength === 64 ? "sha256" : "sha1")
		.update(`${type} ${content.length}\0`)
		.update(content)
		.digest("hex");
}

async function verified(
	source: ObjectSource,
	type: ObjectType,
	id: string,
	limit: number,
): Promise<Buffer | null> {
	const content = await source.read(type, id, limit);
	return content !== null && objectId(type, content, id.length) === id ? content : null;
}

/** The tree that a verified commit names: its first header line. */
function treeOfCommit(commit: Buffer, idLength: number): string | null {
	const match = /^tree (?<id>[0-9a-f]+)\n/u.exec(commit.toString("latin1"));
	const id = match?.groups?.id;
	return id?.length === idLength ? id : null;
}

/** The parents that a verified commit names: the header ends at its first empty line. */
function parentsOfCommit(commit: Buffer, idLength: number): string[] {
	const header = commit.toString("latin1").split("\n\n", 1)[0] ?? "";
	return [...header.matchAll(/^parent (?<id>[0-9a-f]+)$/gmu)]
		.map((match) => match.groups?.id ?? "")
		.filter((id) => id.length === idLength);
}

/**
 * The commits reachable from the ones that the capture pinned. Each parent id comes from a commit whose hash the walk
 * checked, so a commit made up by the source is never reached. Past {@link HISTORY_COMMITS} a commit counts as not
 * reached: a citation of it then stays unverified, which is never a claim.
 */
export class PinnedHistory {
	readonly #source: ObjectSource;
	readonly #reached = new Set<string>();
	readonly #frontier: string[];

	constructor(source: ObjectSource, pinned: readonly string[]) {
		this.#source = source;
		this.#frontier = pinned.filter(isCommitId);
	}

	async contains(commit: string): Promise<boolean> {
		while (!this.#reached.has(commit)) {
			const next = this.#frontier.shift();
			if (next === undefined || this.#reached.size >= HISTORY_COMMITS) {
				return false;
			}
			if (!this.#reached.has(next)) {
				const content = await verified(this.#source, "commit", next, TREE_BYTES);
				if (content !== null) {
					this.#reached.add(next);
					this.#frontier.push(...parentsOfCommit(content, next.length));
				}
			}
		}
		return true;
	}
}

/** The entry `name` of a verified tree: each entry is `<mode> <name>\0<raw id>`. */
function entryOf(tree: Buffer, name: Buffer, idLength: number): TreeEntry | "malformed" | null {
	const rawLength = idLength / 2;
	let at = 0;
	while (at < tree.length) {
		const space = tree.indexOf(0x20, at);
		const end = space === -1 ? -1 : tree.indexOf(0, space);
		if (end === -1 || end + 1 + rawLength > tree.length) {
			return "malformed";
		}
		if (tree.subarray(space + 1, end).equals(name)) {
			return {
				mode: tree.toString("latin1", at, space),
				id: tree.toString("hex", end + 1, end + 1 + rawLength),
			};
		}
		at = end + 1 + rawLength;
	}
	return null;
}

async function readBlob(source: ObjectSource, id: string, limit: number): Promise<PinnedBlob> {
	const claimed = await source.size(id);
	if (claimed === null) {
		return { kind: "unreadable" };
	}
	if (claimed > limit) {
		return { kind: "tooLarge", size: claimed };
	}
	const bytes = await verified(source, "blob", id, limit);
	return bytes === null ? { kind: "unreadable" } : { kind: "regular", bytes };
}

/**
 * The regular file at `file` in `commit`, read whole only when it is within `limit`. A link, a submodule or a directory
 * is named as not a regular file; its target is never followed. `history` proves the commit; its hash proves the rest.
 */
export async function readPinnedObject(
	source: ObjectSource,
	history: PinnedHistory,
	commit: string,
	file: string,
	limit: number,
): Promise<PinnedBlob> {
	if (!isCommitId(commit) || !safeRepositoryPath(file)) {
		return { kind: "unsafe" };
	}
	if (!(await history.contains(commit))) {
		return { kind: "unreadable" };
	}
	const idLength = commit.length;
	const root = await verified(source, "commit", commit, TREE_BYTES);
	let tree = root === null ? null : treeOfCommit(root, idLength);
	const parts = file.split("/");
	for (const [position, part] of parts.entries()) {
		if (tree === null) {
			return { kind: "unreadable" };
		}
		const content = await verified(source, "tree", tree, TREE_BYTES);
		if (content === null) {
			return { kind: "unreadable" };
		}
		const entry = entryOf(content, Buffer.from(part, "utf8"), idLength);
		if (entry === "malformed") {
			return { kind: "unreadable" };
		}
		if (entry === null) {
			return { kind: "absent" };
		}
		if (position < parts.length - 1) {
			if (entry.mode !== DIRECTORY_MODE) {
				return { kind: "absent" };
			}
			tree = entry.id;
		} else if (REGULAR_MODES.has(entry.mode)) {
			return readBlob(source, entry.id, limit);
		} else {
			return { kind: "nonregular" };
		}
	}
	return { kind: "unreadable" };
}
