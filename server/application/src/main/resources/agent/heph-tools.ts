// heph-tools.ts — a conversation turn's tools Pod, as the host reaches it.
//
// The worker binds the turn attempt's tools Pod and serves the turn's documents. The host dials the Pod over tool
// protocol v1 on TCP, puts the documents and each loaded repository into it, and runs `read` and `bash` there. The
// answers keep the shapes of Pi's own `read` and `bash`, which the model knows.

import { createHash } from "node:crypto";
import { setTimeout as sleep } from "node:timers/promises";

import type {
	SandboxExecAnswer,
	SandboxLoadAnswer,
	SandboxReadAnswer,
	SandboxRefusal,
} from "./heph-host-protocol.ts";
import { type PutArchive, ToolClient } from "./tool-client.ts";
import { type ExecExit, type ReadPage, ToolProtocolError } from "./tool-protocol.ts";

/** The context folder: the tools workspace of the Pod, where the documents and every loaded repository land. */
export const FOLDER = "/tmp/tools/workspace";

/** Pi's `read` and `bash` bounds. */
export const MAX_LINES = 2000;
export const MAX_BYTES = 50 * 1024;

/** The longest one command runs. */
export const MAX_COMMAND_MS = 60_000;

/** How long a loader's summary may take, and how much of it the note keeps. */
const SUMMARY_MS = 10_000;
const SUMMARY_BYTES = 8 * 1024;

/** How long the host waits for a bound Pod's tools program to answer: it may still be starting. */
const OPEN_WAIT_MS = 60_000;
const OPEN_RETRY_MS = 200;

/** Dials a bound Pod, which may still be starting; a wrong nonce fails at once. */
async function openPod(
	address: { host: string; port: number },
	nonce: string,
): Promise<ToolClient> {
	const until = Date.now() + OPEN_WAIT_MS;
	for (;;) {
		try {
			return await ToolClient.open(address, nonce);
		} catch (error) {
			if (Date.now() >= until || (error instanceof ToolProtocolError && error.code === "nonce")) {
				throw error;
			}
			await sleep(OPEN_RETRY_MS);
		}
	}
}

/** The worker's side of a turn's tools: bind, discard, the documents and the loads. */
export interface ToolsWorker {
	bind: (signal: AbortSignal) => Promise<BindAnswer>;
	discard: (id: string) => Promise<void>;
	documents: (signal: AbortSignal) => Promise<Buffer>;
	document: (
		request: { path: string; offset?: number; limit?: number },
		signal: AbortSignal,
	) => Promise<SandboxReadAnswer>;
	load: (path: string, signal: AbortSignal) => Promise<LoadedArchive | SandboxRefusal>;
}

export type BindAnswer =
	| { ok: true; id: string; host: string; port: number; nonce: string }
	| SandboxRefusal;

/** A load the worker prepared: what the model reads about it, the command to summarize it, and its archive. */
export interface LoadedArchive {
	ok: true;
	path: string;
	resolved: { ref: string; sha: string; cite: string };
	note: string;
	summary: string[];
	archive: PutArchive;
	/** Releases an archive that is never put. */
	cancel: () => Promise<void>;
}

/** Pi's `formatSize`. */
export function formatSize(bytes: number): string {
	if (bytes < 1024) {
		return `${bytes}B`;
	}
	if (bytes < 1024 * 1024) {
		return `${(bytes / 1024).toFixed(1)}KB`;
	}
	return `${(bytes / (1024 * 1024)).toFixed(1)}MB`;
}

const utf8Length = (text: string) => Buffer.byteLength(text, "utf8");

/** Pi's `splitLinesForCounting`: a final newline ends the last line and starts none. */
function splitForCounting(content: string): string[] {
	if (content === "") {
		return [];
	}
	const lines = content.split("\n");
	if (content.endsWith("\n")) {
		lines.pop();
	}
	return lines;
}

/** The end of `line` that fits in `limit` UTF-8 bytes, without a split character. */
function lastBytes(line: string, limit: number): string {
	const bytes = Buffer.from(line, "utf8");
	let from = Math.max(0, bytes.length - limit);
	// A UTF-8 continuation byte is 0x80 to 0xBF.
	while (from < bytes.length && (bytes[from] ?? 0) >= 0x80 && (bytes[from] ?? 0) < 0xc0) {
		from += 1;
	}
	return bytes.subarray(from).toString("utf8");
}

/** Whether a document path is served from the turn's memory: `INDEX.md`, `FIELDS.md` and `inputs/context/**`. */
export function isDocument(path: string, folder = FOLDER): boolean {
	const relative = folderRelative(path, folder);
	return (
		relative !== undefined &&
		(relative === "INDEX.md" || relative === "FIELDS.md" || relative.startsWith("inputs/context/"))
	);
}

/** The path relative to the folder, or `undefined` when it leaves the folder. */
export function folderRelative(path: string, folder = FOLDER): string | undefined {
	const trimmed = path.trim();
	if (trimmed.includes("\0")) {
		return undefined;
	}
	let rest: string;
	if (trimmed === folder || trimmed.startsWith(`${folder}/`)) {
		rest = trimmed.slice(folder.length);
	} else if (trimmed.startsWith("/") || trimmed.startsWith("~")) {
		return undefined;
	} else {
		rest = trimmed;
	}
	const segments: string[] = [];
	for (const segment of rest.split("/")) {
		if (segment === "" || segment === ".") {
			continue;
		}
		if (segment === "..") {
			if (segments.length === 0) {
				return undefined;
			}
			segments.pop();
			continue;
		}
		segments.push(segment);
	}
	return segments.join("/");
}

/**
 * Pi's `read` of a text file, from one page of it and the file's counts: the same text and notices that Pi's own `read`
 * gives. `chunk` holds the file from the page's first line on, at most `MAX_LINES + 1` lines and `MAX_BYTES + 1` bytes.
 */
export function piRead(
	path: string,
	offset: number | undefined,
	limit: number | undefined,
	newlines: number,
	firstLineBytes: number,
	chunk: Buffer,
): { text: string } | { offsetError: string } {
	const totalLines = newlines + 1;
	const startLine = offset === undefined ? 0 : Math.max(0, offset - 1);
	if (startLine >= totalLines) {
		return {
			offsetError: `Offset ${String(offset)} is beyond end of file (${totalLines} lines total)`,
		};
	}
	const wanted =
		limit === undefined ? totalLines - startLine : Math.min(limit, totalLines - startLine);
	const toEnd = startLine + wanted >= totalLines;
	let selected = chunk.toString("utf8");
	let whole: boolean;
	if (toEnd) {
		whole = chunk.length <= MAX_BYTES && wanted <= MAX_LINES + 1;
	} else {
		const chunkNewlines = selected.split("\n").length - 1;
		whole = wanted <= MAX_LINES + 1 && chunkNewlines === wanted && selected.endsWith("\n");
		if (whole) {
			// Pi joins the selected lines without the newline of the last one, before it measures them.
			selected = selected.slice(0, -1);
		}
	}
	const lines = splitForCounting(selected);
	if (!whole && chunk.length > MAX_BYTES && lines.length > 0 && !selected.endsWith("\n")) {
		// The byte bound cut the last line: it is incomplete, and a page never holds part of a line.
		lines.pop();
	}
	const startDisplay = startLine + 1;
	const fits = whole && lines.length <= MAX_LINES && utf8Length(selected) <= MAX_BYTES;
	if (fits) {
		if (limit !== undefined && !toEnd) {
			const next = startLine + wanted + 1;
			return {
				text: `${selected}\n\n[${totalLines - startLine - wanted} more lines in file. Use offset=${next} to continue.]`,
			};
		}
		return { text: selected };
	}
	if (firstLineBytes > MAX_BYTES) {
		return {
			text: `[Line ${startDisplay} is ${formatSize(firstLineBytes)}, exceeds ${formatSize(MAX_BYTES)} limit. Use bash: sed -n '${startDisplay}p' ${path} | head -c ${MAX_BYTES}]`,
		};
	}
	const shown: string[] = [];
	let bytes = 0;
	let byBytes = false;
	for (const line of lines) {
		if (shown.length >= MAX_LINES) {
			break;
		}
		const lineBytes = utf8Length(line) + (shown.length === 0 ? 0 : 1);
		if (bytes + lineBytes > MAX_BYTES) {
			byBytes = true;
			break;
		}
		shown.push(line);
		bytes += lineBytes;
	}
	if (!byBytes && chunk.length > MAX_BYTES && shown.length === lines.length) {
		// No more came because of the byte bound: the next line would not have fit either.
		byBytes = true;
	}
	if (shown.length >= MAX_LINES && bytes <= MAX_BYTES) {
		byBytes = false;
	}
	const endDisplay = startDisplay + shown.length - 1;
	const notice = byBytes
		? `[Showing lines ${startDisplay}-${endDisplay} of ${totalLines} (${formatSize(MAX_BYTES)} limit). Use offset=${endDisplay + 1} to continue.]`
		: `[Showing lines ${startDisplay}-${endDisplay} of ${totalLines}. Use offset=${endDisplay + 1} to continue.]`;
	return { text: `${shown.join("\n")}\n\n${notice}` };
}

/** The chunk that `piRead` pages, from the tools side's page: its lines with their newlines, as a file holds them. */
export function pageChunk(page: ReadPage): Buffer {
	const body = Buffer.from(page.text, "utf8");
	// The tools side drops the page's last newline. It had one when lines follow, or when the file ends with one.
	const reachesEnd = page.nextOffset > page.totalLines;
	// An empty line is a page with no text: it still had its newline.
	const hadNewline =
		!page.truncated &&
		page.nextOffset > page.offset &&
		(!reachesEnd || page.newlines === page.totalLines);
	const chunk = hadNewline ? Buffer.concat([body, Buffer.from("\n")]) : body;
	return chunk.subarray(0, MAX_BYTES + 1);
}

/** What a command's output stream leaves for Pi's tail: the counts of the whole stream and its last bytes. */
export function outputTail() {
	let newlines = 0;
	let total = 0;
	let currentLine = 0;
	let previousLine = 0;
	let tail = Buffer.alloc(0);
	return {
		push(chunk: Buffer): void {
			for (const byte of chunk) {
				if (byte === 0x0a) {
					newlines += 1;
					previousLine = currentLine;
					currentLine = 0;
				} else {
					currentLine += 1;
				}
			}
			total += chunk.length;
			const joined = Buffer.concat([tail, chunk]);
			tail = joined.subarray(Math.max(0, joined.length - (MAX_BYTES + 2)));
		},
		/** Pi's `bash` text of the output: the last lines or bytes, with Pi's notice when cut. */
		text(fullOutputPath: string): { text: string; truncated: boolean } {
			return bashTail({ newlines, total, previousLine, currentLine, tail }, fullOutputPath);
		},
	};
}

/** Pi's `bash` tail of an output, from its counts and its last `MAX_BYTES + 2` bytes. */
function bashTail(
	counts: {
		newlines: number;
		total: number;
		previousLine: number;
		currentLine: number;
		tail: Buffer;
	},
	fullOutputPath: string,
): { text: string; truncated: boolean } {
	const { newlines, total, previousLine, currentLine, tail } = counts;
	const text = tail.toString("utf8");
	const complete = tail.length >= total;
	const endsWithNewline = tail.length > 0 && tail.at(-1) === 0x0a;
	const totalLines = newlines + (total > 0 && !endsWithNewline ? 1 : 0);
	const lastLineBytes = endsWithNewline ? previousLine : currentLine;
	if (complete && totalLines <= MAX_LINES && total <= MAX_BYTES) {
		return { text, truncated: false };
	}
	const lines = splitForCounting(text);
	if (!complete && lines.length > 1) {
		// The first line of the tail may begin before it: it is incomplete.
		lines.shift();
	}
	const shown: string[] = [];
	let bytes = 0;
	let byBytes = false;
	let partial = false;
	for (let index = lines.length - 1; index >= 0 && shown.length < MAX_LINES; index -= 1) {
		const line = lines[index] ?? "";
		const lineBytes = utf8Length(line) + (shown.length === 0 ? 0 : 1);
		if (bytes + lineBytes > MAX_BYTES) {
			byBytes = true;
			if (shown.length === 0) {
				const end = lastBytes(line, MAX_BYTES);
				shown.unshift(end);
				bytes = utf8Length(end);
				partial = true;
			}
			break;
		}
		shown.unshift(line);
		bytes += lineBytes;
	}
	if (!byBytes && !complete && shown.length === lines.length) {
		byBytes = true;
	}
	if (shown.length >= MAX_LINES && bytes <= MAX_BYTES) {
		byBytes = false;
	}
	const content = shown.join("\n");
	const startLine = totalLines - shown.length + 1;
	let notice: string;
	if (partial) {
		notice = `[Showing last ${formatSize(utf8Length(content))} of line ${totalLines} (line is ${formatSize(lastLineBytes)}). Full output: ${fullOutputPath}]`;
	} else if (byBytes) {
		notice = `[Showing lines ${startLine}-${totalLines} of ${totalLines} (${formatSize(MAX_BYTES)} limit). Full output: ${fullOutputPath}]`;
	} else {
		notice = `[Showing lines ${startLine}-${totalLines} of ${totalLines}. Full output: ${fullOutputPath}]`;
	}
	return { text: `${content}\n\n${notice}`, truncated: true };
}

function refusal(error: string, detail: string): SandboxRefusal {
	return { ok: false, error, detail };
}

/**
 * One turn attempt's tools Pod, as the host reaches it. It binds the Pod at the first call that needs one, and binds a
 * new one after the old one or its connection failed. Its calls keep the answer shapes of the worker's earlier ones.
 */
export class TurnTools {
	readonly #worker: ToolsWorker;
	readonly #open: (address: { host: string; port: number }, nonce: string) => Promise<ToolClient>;
	/** The tools workspace of the Pod: the context folder. */
	readonly #folder: string;
	#pod: Promise<{ id: string; client: ToolClient } | SandboxRefusal> | undefined;
	/** The Pod that `#pod` resolved to: a failed call drops only the Pod it ran in, never a newer one. */
	#current: { id: string; client: ToolClient } | undefined;
	/** The repository directories that a put made in the current Pod: a load again replaces one. */
	#loaded = new Set<string>();
	/** Loads run one at a time, so a second load of a directory knows whether the first one made it. */
	#loading: Promise<undefined> = Promise.resolve(undefined);

	constructor(
		worker: ToolsWorker,
		open: (address: { host: string; port: number }, nonce: string) => Promise<ToolClient> = openPod,
		folder = FOLDER,
	) {
		this.#worker = worker;
		this.#open = open;
		this.#folder = folder;
	}

	/** Ends the connection to the current Pod; the worker removes the Pod with the attempt. */
	async close(): Promise<void> {
		const pod = this.#pod;
		this.#pod = undefined;
		this.#current = undefined;
		if (pod === undefined) {
			return;
		}
		const bound = await pod.catch(() => undefined);
		if (bound !== undefined && "client" in bound) {
			bound.client.close();
		}
	}

	async #bound(signal: AbortSignal): Promise<{ id: string; client: ToolClient } | SandboxRefusal> {
		this.#pod ??= this.#bind(signal);
		const pod = this.#pod;
		try {
			const bound = await pod;
			if ("ok" in bound && this.#pod === pod) {
				// A refusal is the answer of this call only; the next call asks again.
				this.#pod = undefined;
			}
			return bound;
		} catch (error) {
			// A bind that threw, such as an aborted call's, is not the Pod of the calls that follow.
			if (this.#pod === pod) {
				this.#pod = undefined;
			}
			throw error;
		}
	}

	async #bind(signal: AbortSignal): Promise<{ id: string; client: ToolClient } | SandboxRefusal> {
		const answer = await this.#worker.bind(signal);
		if (!answer.ok) {
			return answer;
		}
		let client: ToolClient;
		try {
			client = await this.#open({ host: answer.host, port: answer.port }, answer.nonce);
		} catch {
			await this.#worker.discard(answer.id).catch(() => undefined);
			return refusal("UNAVAILABLE", "The tools did not start. Try again once.");
		}
		try {
			const documents = await this.#worker.documents(signal);
			// The documents land under `inputs/`, so `bash` sees them beside the repositories.
			await client.put(`${this.#folder}/inputs`, archiveOf(documents));
		} catch {
			client.close();
			await this.#worker.discard(answer.id).catch(() => undefined);
			return refusal("UNAVAILABLE", "The context folder could not be prepared. Try again once.");
		}
		this.#loaded = new Set();
		this.#current = { id: answer.id, client };
		return this.#current;
	}

	/** Drops a Pod whose call failed: what it holds may be partial, so the worker removes it. */
	async #lost(pod: { id: string; client: ToolClient }): Promise<void> {
		if (this.#current === pod) {
			this.#pod = undefined;
			this.#current = undefined;
		}
		pod.client.close();
		await this.#worker.discard(pod.id).catch(() => undefined);
	}

	/**
	 * The answer of a call whose Pod failed. The worker removes an attempt's Pod when the workspace's data changes, and
	 * its refusal of the next bind says so: the model reads that, not a failure to retry.
	 */
	async #failed(
		pod: { id: string; client: ToolClient },
		signal: AbortSignal,
		failure: SandboxRefusal,
	): Promise<SandboxRefusal> {
		await this.#lost(pod);
		const next = await this.#bound(signal).catch(() => undefined);
		return next !== undefined && "ok" in next && next.error === "CONTEXT_CHANGED" ? next : failure;
	}

	/** Pi's `read`: a document from the turn's memory, or a page of a file in the Pod. */
	async read(
		request: { path: string; offset?: number; limit?: number },
		signal: AbortSignal,
	): Promise<SandboxReadAnswer> {
		for (const [name, value] of [
			["offset", request.offset],
			["limit", request.limit],
		] as const) {
			if (value !== undefined && !(Number.isInteger(value) && value >= 1)) {
				return refusal("INVALID_ARGUMENT", `${name} must be a whole number of 1 or more.`);
			}
		}
		if (isDocument(request.path, this.#folder)) {
			return this.#worker.document(request, signal);
		}
		const absolute = podPath(request.path, this.#folder);
		if (absolute === undefined) {
			return refusal("NOT_FOUND", `File not found: ${request.path}`);
		}
		const pod = await this.#bound(signal);
		if ("ok" in pod) {
			return pod;
		}
		const first = request.offset ?? 1;
		const lines = Math.min(request.limit ?? Number.MAX_SAFE_INTEGER, MAX_LINES + 1);
		let page: ReadPage;
		try {
			page = await pod.client.read(absolute, first, lines);
		} catch (error) {
			if (signal.aborted) {
				throw error;
			}
			if (!refused(error)) {
				return this.#failed(pod, signal, refusal("UNAVAILABLE", `Could not read ${request.path}.`));
			}
			if (error.code === "large") {
				return refusal("UNAVAILABLE", error.message);
			}
			return error.message === "The path is not a file."
				? refusal("IS_DIRECTORY", `${request.path} is a directory. Use bash: ls ${request.path}`)
				: refusal("NOT_FOUND", `File not found: ${request.path}`);
		}
		const read = piRead(
			request.path,
			request.offset,
			request.limit,
			page.newlines,
			page.firstLineBytes,
			pageChunk(page),
		);
		return "offsetError" in read
			? refusal("OFFSET_BEYOND_END", read.offsetError)
			: { ok: true, text: read.text };
	}

	/** Pi's `bash`: one command in the folder, bounded by a minute and by the turn. */
	async exec(
		request: { command: string; timeoutMs?: number },
		signal: AbortSignal,
	): Promise<SandboxExecAnswer> {
		const pod = await this.#bound(signal);
		if ("ok" in pod) {
			return pod;
		}
		const timeoutMs = Math.min(Math.max(1, request.timeoutMs ?? MAX_COMMAND_MS), MAX_COMMAND_MS);
		const tail = outputTail();
		let exit: ExecExit;
		try {
			exit = await pod.client.exec(
				{ command: request.command, cwd: this.#folder, timeoutMs },
				(chunk) => tail.push(chunk),
				signal,
			);
		} catch (error) {
			if (signal.aborted) {
				throw error;
			}
			const failure = refusal("UNAVAILABLE", "The sandbox failed. Try again once.");
			return refused(error) ? failure : this.#failed(pod, signal, failure);
		}
		const shown = tail.text(exit.fullOutputPath);
		let output = shown.text;
		if (exit.truncated || exit.outputLost) {
			output += "\n[The output was cut here. What follows it is unknown.]";
		}
		const truncated = shown.truncated || exit.truncated || exit.outputLost;
		return {
			ok: true,
			exitCode: exit.timedOut ? null : (exit.code ?? 128),
			output,
			truncated,
			timedOut: exit.timedOut,
			fullOutputPath: truncated && exit.fullOutputPath !== "" ? exit.fullOutputPath : null,
		};
	}

	/** `load_context`: the worker prepares the item, and the host puts it into the Pod and runs its summary. */
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
		// The worker refuses a load before any Pod is bound for it.
		const loaded = await this.#worker.load(request.path, signal);
		if (!loaded.ok) {
			return loaded;
		}
		let pod: { id: string; client: ToolClient } | SandboxRefusal;
		try {
			pod = await this.#bound(signal);
		} catch (error) {
			await loaded.cancel();
			throw error;
		}
		if ("ok" in pod) {
			await loaded.cancel();
			return pod;
		}
		const dir = `${this.#folder}/${loaded.path}`;
		try {
			await pod.client.put(dir, loaded.archive, { replace: this.#loaded.has(dir) });
			this.#loaded.add(dir);
		} catch (error) {
			if (signal.aborted) {
				throw error;
			}
			return this.#failed(
				pod,
				signal,
				refusal("UNAVAILABLE", "The context folder could not be prepared. Try again once."),
			);
		}
		const summary = await summarize(pod.client, dir, loaded.summary, signal);
		return {
			ok: true,
			path: loaded.path,
			resolved: loaded.resolved,
			note: summary === "" ? loaded.note : `${loaded.note}\n${summary}`,
		};
	}
}

/** A loader's summary of a loaded item, run in its directory; empty when there is none or it fails. */
async function summarize(
	client: ToolClient,
	cwd: string,
	argv: string[],
	signal: AbortSignal,
): Promise<string> {
	if (argv.length === 0) {
		return "";
	}
	const chunks: Buffer[] = [];
	let size = 0;
	try {
		const exit = await client.exec(
			{ argv, cwd, timeoutMs: SUMMARY_MS, maxOutputBytes: SUMMARY_BYTES },
			(chunk) => {
				if (size < SUMMARY_BYTES) {
					chunks.push(chunk);
					size += chunk.length;
				}
			},
			signal,
		);
		return exit.timedOut
			? ""
			: Buffer.concat(chunks).subarray(0, SUMMARY_BYTES).toString("utf8").trim();
	} catch {
		return "";
	}
}

/** The Pod's path of a path in the folder; the spill files that `bash` names are in it too. */
function podPath(path: string, folder: string): string | undefined {
	const relative = folderRelative(path, folder);
	if (relative === undefined) {
		return undefined;
	}
	return relative === "" ? folder : `${folder}/${relative}`;
}

/** Whether the tools side refused the call and stays usable; a broken connection or another protocol is not that. */
function refused(error: unknown): error is ToolProtocolError {
	return (
		error instanceof ToolProtocolError && error.code !== "protocol" && error.code !== "version"
	);
}

function archiveOf(bytes: Buffer): PutArchive {
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
