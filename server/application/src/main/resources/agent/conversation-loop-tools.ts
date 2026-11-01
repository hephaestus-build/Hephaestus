import path from "node:path/posix";

import {
	createReadTool,
	DEFAULT_MAX_BYTES,
	DEFAULT_MAX_LINES,
	truncateTail,
} from "@earendil-works/pi-coding-agent";

import type {
	SandboxExecAnswer,
	SandboxExecRequest,
	SandboxLoadAnswer,
	SandboxLoadRequest,
	SandboxReadAnswer,
	SandboxReadRequest,
} from "./conversation-loop-protocol.ts";
import type { PutArchive, ToolClient } from "./tool-client.ts";

type Tools = Pick<ToolClient, "put" | "read" | "exec" | "close">;

export interface ToolsArchive {
	archive: PutArchive;
	directory: string;
	result: SandboxLoadAnswer;
	summary: string[];
}

/** The context the worker built a document or Job from; a change revokes everything read before. */
export interface Generated {
	generation: string;
}

export interface ConversationToolGateway {
	/** Returns a captured document only. No tools Job starts on this path. */
	document: (path: string, signal: AbortSignal) => Promise<(Generated & { text: string }) | null>;
	/** Acquires one Job and verifies its hello nonce before it returns a usable connection. */
	connect: (signal: AbortSignal) => Promise<Generated & { client: Tools }>;
	documents: (signal: AbortSignal) => Promise<Generated & { archive: PutArchive }>;
	load: (path: string, signal: AbortSignal) => Promise<ToolsArchive | SandboxLoadAnswer>;
}

const ROOT = "/tmp/tools/workspace/context";
const MAX_DOCUMENT_BYTES = 8 * 1024 * 1024;

function refusal(error: string, detail: string) {
	return { ok: false, error, detail } as const;
}

/** Model paths stay relative to the context root. A traversal can reach neither another attempt nor a local file. */
export function contextPath(requested: string): string | null {
	const value = requested.trim();
	if (value.includes("\0") || value.startsWith("~")) {
		return null;
	}
	let relative = value;
	if (value === "/workspace") {
		relative = ".";
	} else if (value.startsWith("/workspace/")) {
		relative = value.slice(11);
	}
	if (relative.startsWith("/")) {
		return null;
	}
	const normalized = path.normalize(relative);
	return normalized === ".." || normalized.startsWith("../") ? null : normalized;
}

/** Each instance belongs to one attempt. Its documents never enter the loop's filesystem. */
export function conversationTools(gateway: ConversationToolGateway) {
	let connection: Promise<Tools> | undefined;
	let generation: string | undefined;
	let closed = false;
	// Read through a call: the attempt can end while a Job starts, which narrowing across an await would hide.
	const isClosed = () => closed;
	let loads = 0;

	/** Whether `seen` is the context this attempt read so far; a new one drops the memory and the Job. */
	async function current(seen: string): Promise<boolean> {
		if (generation === undefined) {
			generation = seen;
			return true;
		}
		if (generation === seen) {
			return true;
		}
		await reset();
		generation = seen;
		return false;
	}

	async function reset() {
		const old = connection;
		connection = undefined;
		await old?.then(
			(client) => client.close(),
			() => undefined,
		);
	}

	const CHANGED = refusal(
		"CONTEXT_CHANGED",
		"The context folder changed. Read it again; repositories must be loaded again.",
	);

	async function tools(signal: AbortSignal): Promise<Tools> {
		signal.throwIfAborted();
		if (closed) {
			throw new Error("The conversation attempt ended");
		}
		connection ??= (async () => {
			const { client, generation: built } = await gateway.connect(signal);
			try {
				if (isClosed()) {
					throw new Error("The conversation attempt ended");
				}
				const folder = await gateway.documents(signal);
				if (folder.generation !== built) {
					throw new Error("The context changed while the Job started");
				}
				await client.put(ROOT, folder.archive);
				if (isClosed()) {
					throw new Error("The conversation attempt ended");
				}
				if (!(await current(built))) {
					throw new Error("The context changed while the Job started");
				}
				return client;
			} catch (error) {
				client.close();
				throw error;
			}
		})();
		const opening = connection;
		void forgetOnFailure(opening);
		return opening;
	}

	/** A Job that failed to open is not kept: the next call asks for a new one. */
	async function forgetOnFailure(opening: Promise<Tools>) {
		try {
			await opening;
		} catch {
			if (connection === opening) {
				connection = undefined;
			}
		}
	}

	return {
		async read(request: SandboxReadRequest, signal: AbortSignal): Promise<SandboxReadAnswer> {
			signal.throwIfAborted();
			const relative = contextPath(request.path);
			if (relative === null) {
				return refusal("NOT_FOUND", "This path is outside the context folder.");
			}
			const document =
				relative === "INDEX.md" ||
				relative === "FIELDS.md" ||
				relative.startsWith("inputs/context/");
			if (document) {
				// Each read asks the gateway, which answers from the context the turn may read now: a document read before a
				// context change is never served again from memory.
				const found = await gateway.document(relative, signal);
				if (found === null) {
					return refusal("NOT_FOUND", "This context document is unavailable.");
				}
				if (!(await current(found.generation))) {
					return CHANGED;
				}
				const bytes = Buffer.from(found.text, "utf8");
				if (bytes.length > MAX_DOCUMENT_BYTES) {
					return refusal("TOO_LARGE", "The context document exceeds this turn's byte bound.");
				}

				try {
					const page = await memoryRead(relative, bytes).execute("memory-read", request, signal);
					return {
						ok: true,
						text: page.content
							.flatMap((part) => (part.type === "text" ? [part.text] : []))
							.join("\n"),
					};
				} catch {
					return refusal("INVALID_PAGE", "This page is outside the context document.");
				}
			}
			// A repository exists only after load_context. Reading it cannot allocate a tools Job.
			if (connection === undefined) {
				return refusal("NOT_FOUND", "Load the repository with load_context before reading it.");
			}
			const client = await connection;
			const page = await client.read(
				path.join(ROOT, relative),
				request.offset ?? 1,
				Math.min(request.limit ?? DEFAULT_MAX_LINES, DEFAULT_MAX_LINES),
			);
			return {
				ok: true,
				text:
					page.text +
					(page.nextOffset <= page.totalLines
						? `\n\n[Use offset=${page.nextOffset} to continue.]`
						: ""),
			};
		},
		async exec(request: SandboxExecRequest, signal: AbortSignal): Promise<SandboxExecAnswer> {
			return dropOnFailure(signal, async () => exec(request, signal));
		},
		async load(request: SandboxLoadRequest, signal: AbortSignal): Promise<SandboxLoadAnswer> {
			return dropOnFailure(signal, async () => load(request, signal));
		},
		/** A context change invalidates both the memory and the old Job connection. */
		reset,
		async close() {
			closed = true;
			await connection?.then(
				(client) => client.close(),
				() => undefined,
			);
		},
	};

	/** A Job that failed, for example because the worker discarded it, is not used again. */
	async function dropOnFailure<T>(signal: AbortSignal, call: () => Promise<T>): Promise<T> {
		try {
			return await call();
		} catch (error) {
			if (!signal.aborted) {
				await reset();
			}
			throw error;
		}
	}

	async function exec(
		request: SandboxExecRequest,
		signal: AbortSignal,
	): Promise<SandboxExecAnswer> {
		const client = await tools(signal);
		// Keep only a tail. The tools program owns the complete spill file and bounds the stream.
		let tail = Buffer.alloc(0);
		const exit = await client.exec(
			{
				command: request.command,
				cwd: ROOT,
				timeoutMs: Math.min(request.timeoutMs ?? 60_000, 60_000),
			},
			(chunk) => {
				tail = Buffer.concat([tail, chunk]).subarray(-DEFAULT_MAX_BYTES - 4);
			},
			signal,
		);
		const output = truncateTail(tail.toString("utf8"));
		return {
			ok: true,
			exitCode: exit.code,
			output: output.content,
			truncated: exit.truncated || output.truncated,
			timedOut: exit.timedOut,
			fullOutputPath: exit.fullOutputPath || null,
		};
	}

	async function load(
		request: SandboxLoadRequest,
		signal: AbortSignal,
	): Promise<SandboxLoadAnswer> {
		loads += 1;
		if (loads > 5) {
			return refusal("LOAD_LIMIT", "This turn has reached its repository load limit.");
		}
		const client = await tools(signal);
		const prepared = await gateway.load(request.path, signal);
		if (!("archive" in prepared)) {
			return prepared;
		}
		const relative = contextPath(prepared.directory);
		if (relative === null || !relative.startsWith("repos/")) {
			throw new Error("The gateway named an invalid repository path");
		}
		await client.put(path.join(ROOT, relative), prepared.archive);
		if (prepared.result.ok && prepared.summary.length > 0) {
			let summary = Buffer.alloc(0);
			await client.exec(
				{
					argv: prepared.summary,
					cwd: path.join(ROOT, relative),
					timeoutMs: 30_000,
					maxOutputBytes: DEFAULT_MAX_BYTES,
				},
				(chunk) => {
					summary = Buffer.concat([summary, chunk]).subarray(0, DEFAULT_MAX_BYTES);
				},
				signal,
			);
			return { ...prepared.result, note: `${prepared.result.note}\n${summary.toString("utf8")}` };
		}
		return prepared.result;
	}
}

/** Pi's read over one document the gateway just served, so its paging is Pi's own. */
function memoryRead(relative: string, content: Buffer) {
	return createReadTool("/workspace", {
		operations: {
			async access() {
				// The gateway just served the one document this read reaches.
			},
			async readFile(absolute) {
				if (contextPath(absolute) !== relative) {
					throw new Error("The context document is unavailable");
				}
				return content;
			},
			async detectImageMimeType() {
				return null;
			},
		},
	});
}
