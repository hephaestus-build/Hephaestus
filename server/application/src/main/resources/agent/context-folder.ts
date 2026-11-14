// context-folder.ts — a conversation turn's context folder, in the turn's own sandbox: the turn's documents and the
// repositories it loads, which Pi's own `read` and `bash` open there. The template is the boundary (ADR 0079 §
// Sandbox), so the tools run as children of the loop. docs/contributor/unified-pi-runtime.mdx § Context folder.

import { spawn } from "node:child_process";
import { createHash, randomUUID } from "node:crypto";
import { chmod, link, lstat, mkdir, readdir, rm, writeFile } from "node:fs/promises";
import path from "node:path";

import type { SandboxLoadAnswer, SandboxRefusal } from "./heph-host-protocol.ts";

/** An archive that the server streams: its bytes, its size and its SHA-256. */
export interface Archive {
	bytes: AsyncIterable<Buffer>;
	size: number;
	sha256: string;
}

/** A load the server prepared: what the model reads about it, the command to summarize it, and its archive. */
export interface LoadedArchive {
	ok: true;
	path: string;
	resolved: { ref: string; sha: string; cite: string };
	note: string;
	summary: string[];
	archive: Archive;
	/** Releases an archive that is never extracted. */
	cancel: () => Promise<void>;
}

/** The server's side of a turn's folder: the turn's documents, and each load. */
export interface FolderServer {
	documents: (signal: AbortSignal) => Promise<Buffer>;
	document: (path: string, signal: AbortSignal) => Promise<Buffer | null>;
	load: (path: string, signal: AbortSignal) => Promise<LoadedArchive | SandboxRefusal>;
}

/** How long one extraction may take. */
const EXTRACT_MS = 120_000;

/** How long a loader's summary may take, and how much of it the note keeps. */
const SUMMARY_MS = 10_000;
const SUMMARY_BYTES = 8 * 1024;

function refusal(error: string, detail: string): SandboxRefusal {
	return { ok: false, error, detail };
}

/**
 * One turn's context folder. The first tool that needs it empties what an earlier turn left and extracts this turn's
 * documents, so no turn reads what another turn loaded. Loads run one at a time, so a second load of a repository
 * knows whether the first one made it.
 */
export class ContextFolder {
	readonly root: string;
	readonly #server: FolderServer;
	#ready: Promise<SandboxRefusal | undefined> | undefined;
	#loaded = new Set<string>();
	#loading: Promise<undefined> = Promise.resolve(undefined);

	constructor(root: string, server: FolderServer) {
		this.root = root;
		this.#server = server;
	}

	/** Prepares the folder once for the turn; a refusal is the answer of this call only, and the next call tries again. */
	async prepare(signal: AbortSignal): Promise<SandboxRefusal | undefined> {
		this.#ready ??= this.#prepare(signal);
		const ready = this.#ready;
		try {
			const answer = await ready;
			if (answer !== undefined && this.#ready === ready) {
				this.#ready = undefined;
			}
			return answer;
		} catch (error) {
			if (this.#ready === ready) {
				this.#ready = undefined;
			}
			throw error;
		}
	}

	async #prepare(signal: AbortSignal): Promise<SandboxRefusal | undefined> {
		await mkdir(this.root, { recursive: true });
		const entries = await readdir(this.root);
		await Promise.all(
			entries.map(async (entry) => {
				const target = path.join(this.root, entry);
				// A tree made read-only must be writable again before it can go.
				await writable(target);
				await rm(target, { recursive: true, force: true });
			}),
		);
		this.#loaded = new Set();
		let documents: Buffer;
		try {
			documents = await this.#server.documents(signal);
		} catch (error) {
			if (signal.aborted) {
				throw error;
			}
			return refusal("UNAVAILABLE", "The context folder could not be prepared. Try again once.");
		}
		const extracted = await extract(archiveOf(documents), this.root, signal);
		return extracted
			? undefined
			: refusal("UNAVAILABLE", "The context folder could not be prepared. Try again once.");
	}

	/** Loads an indexed item document once. Linking a finished file prevents a parallel read of partial bytes. */
	async document(requested: string, signal: AbortSignal): Promise<void> {
		const absolute = path.resolve(this.root, requested);
		const relative = path.relative(this.root, absolute);
		if (
			!relative.startsWith(`inputs${path.sep}context${path.sep}`) ||
			(await lstat(absolute).catch(() => undefined))
		) {
			return;
		}
		const bytes = await this.#server.document(relative.split(path.sep).join("/"), signal);
		if (bytes === null) {
			return;
		}
		const directory = path.dirname(absolute);
		await mkdir(directory, { recursive: true });
		const temporary = path.join(directory, `.${randomUUID()}.json`);
		try {
			await writeFile(temporary, bytes, { mode: 0o444 });
			await link(temporary, absolute).catch((error: unknown) => {
				if (!(error instanceof Error) || !("code" in error) || error.code !== "EEXIST") {
					throw error;
				}
			});
		} finally {
			await rm(temporary, { force: true });
		}
	}

	/** `load_context`: the server prepares the item; it is extracted under the folder and summarized there. */
	async load(request: { path: string }, signal: AbortSignal): Promise<SandboxLoadAnswer> {
		const previous = this.#loading;
		const done = Promise.withResolvers<undefined>();
		this.#loading = done.promise;
		try {
			await previous;
			return await this.#loadOne(request, signal);
		} finally {
			done.resolve(undefined);
		}
	}

	async #loadOne(request: { path: string }, signal: AbortSignal): Promise<SandboxLoadAnswer> {
		const ready = await this.prepare(signal);
		if (ready !== undefined) {
			return ready;
		}
		// The server refuses a load that the turn may not make before it sends anything.
		const loaded = await this.#server.load(request.path, signal);
		if (!loaded.ok) {
			return loaded;
		}
		const dir = path.join(this.root, loaded.path);
		if (!within(this.root, dir)) {
			await loaded.cancel();
			return refusal("INVALID_ARGUMENT", `${request.path} is not a path in the context folder.`);
		}
		if (this.#loaded.has(dir)) {
			await writable(dir);
			await rm(dir, { recursive: true, force: true });
		}
		await mkdir(dir, { recursive: true });
		if (!(await extract(loaded.archive, dir, signal))) {
			signal.throwIfAborted();
			return refusal("UNAVAILABLE", "The context folder could not be prepared. Try again once.");
		}
		await readOnly(dir);
		this.#loaded.add(dir);
		const summary = await summarize(dir, loaded.summary, signal);
		return {
			ok: true,
			path: loaded.path,
			resolved: loaded.resolved,
			note: summary === "" ? loaded.note : `${loaded.note}\n${summary}`,
		};
	}
}

function within(root: string, absolute: string): boolean {
	const relative = path.relative(root, absolute);
	return relative !== "" && !relative.startsWith("..") && !path.isAbsolute(relative);
}

/**
 * Writes the archive to a file beside `dir`, checks its size and SHA-256, and extracts it into `dir`. GNU tar and
 * bsdtar both strip leading slashes, skip members with `..`, and never write through a symlink that the archive made.
 */
async function extract(archive: Archive, dir: string, signal: AbortSignal): Promise<boolean> {
	const file = path.join(path.dirname(dir), `.archive-${randomUUID()}.tar`);
	const hash = createHash("sha256");
	let size = 0;
	async function* checkedBytes() {
		for await (const chunk of archive.bytes) {
			signal.throwIfAborted();
			size += chunk.length;
			if (size > archive.size) {
				break;
			}
			hash.update(chunk);
			yield chunk;
		}
	}
	try {
		await writeFile(file, checkedBytes(), { mode: 0o600, signal });
		if (size !== archive.size || hash.digest("hex") !== archive.sha256) {
			return false;
		}
		const code = await run(
			"tar",
			["-x", "--no-same-owner", "--no-same-permissions", "-f", file, "-C", dir],
			dir,
			EXTRACT_MS,
			signal,
		);
		return code === 0;
	} finally {
		await rm(file, { force: true });
	}
}

/** A program's exit code, or null when it did not start or ran past `timeoutMs`; its output goes to `onOutput`. */
async function run(
	file: string,
	args: readonly string[],
	cwd: string,
	timeoutMs: number,
	signal: AbortSignal,
	onOutput?: (chunk: Buffer) => void,
): Promise<number | null> {
	const child = spawn(file, args, {
		cwd,
		stdio: ["ignore", onOutput === undefined ? "ignore" : "pipe", "ignore"],
		signal,
		timeout: timeoutMs,
		killSignal: "SIGKILL",
	});
	if (onOutput !== undefined) {
		child.stdout?.on("data", onOutput);
	}
	const closed = Promise.withResolvers<number | null>();
	child.once("error", () => closed.resolve(null));
	// A program that the timeout or the signal ended has no exit code of its own.
	child.once("close", (code, ended) => closed.resolve(ended === null ? code : null));
	return closed.promise;
}

/** A loader's summary of a loaded item, run in its directory; empty when there is none or it fails. */
async function summarize(cwd: string, argv: string[], signal: AbortSignal): Promise<string> {
	const [file, ...args] = argv;
	if (file === undefined) {
		return "";
	}
	const chunks: Buffer[] = [];
	let size = 0;
	const code = await run(file, args, cwd, SUMMARY_MS, signal, (chunk) => {
		if (size < SUMMARY_BYTES) {
			chunks.push(chunk);
			size += chunk.length;
		}
	});
	return code === null
		? ""
		: Buffer.concat(chunks).subarray(0, SUMMARY_BYTES).toString("utf8").trim();
}

/** Makes a loaded tree read-only, so a command does not change it by accident; its owner can still undo that. */
async function readOnly(dir: string): Promise<void> {
	for (const entry of await readdir(dir, { recursive: true })) {
		const absolute = path.join(dir, entry);
		const stat = await lstat(absolute);
		if (stat.isSymbolicLink()) {
			continue;
		}
		// oxlint-disable-next-line no-bitwise -- a file mode is a bit set
		await chmod(absolute, stat.isDirectory() || (stat.mode & 0o100) !== 0 ? 0o555 : 0o444);
	}
	await chmod(dir, 0o555);
}

/** Makes a tree that `readOnly` made writable again, so it can be removed; a missing tree is no error. */
async function writable(target: string): Promise<void> {
	const stat = await lstat(target).catch(() => undefined);
	if (stat === undefined || stat.isSymbolicLink()) {
		return;
	}
	if (stat.isDirectory()) {
		await chmod(target, 0o755);
		for (const entry of await readdir(target, { recursive: true })) {
			const absolute = path.join(target, entry);
			const inner = await lstat(absolute);
			if (inner.isDirectory()) {
				await chmod(absolute, 0o755);
			}
		}
	}
}

function archiveOf(bytes: Buffer): Archive {
	async function* chunks() {
		for (let at = 0; at < bytes.length; at += 512 * 1024) {
			yield bytes.subarray(at, at + 512 * 1024);
		}
	}
	return {
		bytes: chunks(),
		size: bytes.length,
		sha256: createHash("sha256").update(bytes).digest("hex"),
	};
}
