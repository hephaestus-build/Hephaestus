import { type ChildProcess, spawn } from "node:child_process";
import { createHash } from "node:crypto";
import { createReadStream, constants as fsConstants, openAsBlob } from "node:fs";
import { lstat, mkdir, mkdtemp, open, opendir, rm, writeFile } from "node:fs/promises";
import { constants, tmpdir } from "node:os";
import path from "node:path";
import { setTimeout as sleep } from "node:timers/promises";

import { type FileEntry, parseSessionEntries } from "@earendil-works/pi-coding-agent";

import { present } from "./gateway-capabilities.ts";

// The worker follows SIGTERM with SIGKILL after its stop timeout; the child gets this much of it and
// the upload of whatever out/ holds by then gets the rest.
const CHILD_STOP_GRACE_MS = 3000;
const UPLOAD_BACKOFF_MS = 500;
const MAX_UPLOAD_BACKOFF_MS = 30_000;
const UPLOAD_GRACE_MS = 10 * 60_000;
const EXIT_UPLOAD_FAILED = 43;
const EXIT_WORK_TIMEOUT = 124;

function exitCodeOf(code: number | null, signal: NodeJS.Signals | null): number {
	if (code !== null) {
		return code;
	}
	return 128 + (signal === null ? 0 : constants.signals[signal]);
}

/** The child's exit code, or the error that kept it from running. */
async function exited(child: ChildProcess) {
	const done = Promise.withResolvers<number>();
	child.once("error", done.reject);
	child.once("exit", (code, signal) => done.resolve(exitCodeOf(code, signal)));
	return done.promise;
}

async function run(command: string, args: string[]) {
	return exited(spawn(command, args, { stdio: "inherit" }));
}

function retryable(status: number): boolean {
	return status === 425 || status >= 500;
}

async function waitBeforeRetry(attempt: number, deadline: number): Promise<void> {
	const delay = Math.min(
		MAX_UPLOAD_BACKOFF_MS,
		UPLOAD_BACKOFF_MS * 2 ** Math.min(attempt - 1, 16),
		Math.max(0, deadline - Date.now()),
	);
	await sleep(delay);
}

export async function upload(
	url: URL,
	token: string,
	archive: string,
	deadline = Date.now() + UPLOAD_GRACE_MS,
): Promise<void> {
	const hash = createHash("sha256");
	for await (const chunk of createReadStream(archive)) {
		const value: unknown = chunk;
		if (!Buffer.isBuffer(value)) {
			throw new Error("Archive stream returned non-binary data");
		}
		hash.update(value);
	}
	const digest = hash.digest();
	const contentDigest = `sha-256=:${digest.toString("base64")}:`;
	const etag = `"${digest.toString("hex")}"`;
	for (let attempt = 1; ; attempt += 1) {
		const remaining = deadline - Date.now();
		if (remaining <= 0) {
			throw new Error("Result upload deadline expired");
		}
		let response: Response;
		try {
			response = await fetch(url, {
				method: "POST",
				signal: AbortSignal.timeout(remaining),
				headers: {
					Authorization: `Bearer ${token}`,
					"Content-Type": "application/x-tar",
					"Content-Digest": contentDigest,
				},
				// A Blob body carries a Content-Length, which the gateway bounds before reading.
				body: await openAsBlob(archive),
			});
		} catch (error) {
			console.error(`result upload attempt ${attempt} failed: ${String(error)}`);
			await waitBeforeRetry(attempt, deadline);
			continue;
		}
		await response.body?.cancel();
		if (response.status === 204 || response.status === 409) {
			if (response.headers.get("etag") === etag) {
				return;
			}
			throw new Error(`Result upload was not confirmed: ${response.status}`);
		}
		if (!retryable(response.status)) {
			throw new Error(`Result upload refused: ${response.status}`);
		}
		console.error(`result upload attempt ${attempt} answered ${response.status}`);
		await waitBeforeRetry(attempt, deadline);
	}
}

const BLOCK = 512;
/** The server refuses a whole result past these (SandboxOutputArchive); a transcript must never cost the result. */
const RESULT_BYTES = 50 * 1024 * 1024;
const RESULT_ENTRIES = 10_000;
const TRACE_BYTES = 7 * 1024 * 1024;
const TRACE_FILE_BYTES = 2 * 1024 * 1024;
/** Room kept for the trace directory, its manifest and the end of the archive. */
const TRACE_RESERVE = 1024 * 1024;
const REDACTED = Buffer.from("[attempt-credential]", "utf8");
/** The runner's REVIEW_SESSION_ENTRY (pi-session-lifecycle.ts); the runner sources are not in this image. */
const REVIEW_SESSION_ENTRY = "hephaestus.review-session";
const PHASES = new Set(["practice", "public-review", "private-feedback"]);
const STOP_REASONS = new Set(["stop", "length", "toolUse", "error", "aborted"]);
/** The tools a review session is given (pi-runner.ts); any other name is counted as "other". */
const TOOL_NAMES = new Set([
	"read",
	"grep",
	"find",
	"ls",
	"bash",
	"codemode",
	"read_practice",
	"report_observation",
	"report_feedback",
	"report_review",
]);

/**
 * What one session did, as enums and numbers only. The worker keeps this alone when it cannot index every person a
 * transcript may name, so nothing here is text the session wrote: no arguments, results, prose, paths or error text.
 */
export interface SessionSummary {
	phase: string | null;
	practiceRevisionId: number | null;
	entries: number;
	assistantCalls: number;
	stopReasons: Record<string, number>;
	usage: { input: number; output: number; cacheRead: number; cacheWrite: number };
	toolCalls: Record<string, number>;
	toolErrors: number;
	compactions: number;
	/** Failed or aborted calls by the adapter's closed failure kind. */
	modelFailures: Record<string, number>;
	/** The adapter's time of the latest of them, in epoch milliseconds, when it stated one. */
	lastModelFailureAt: number | null;
	/** The failure the session's last call ended on; null once a later call succeeded. */
	finalModelFailure: ModelFailure | null;
	/** Failed or aborted calls whose failure diagnostic was missing or unreadable; their kind reads UNKNOWN. */
	unattributedModelFailures: number;
	/** The adapter's own call spans in milliseconds, failed calls included; calls without one are not counted. */
	modelCallTiming: {
		timedCalls: number;
		respondedCalls: number;
		elapsedMs: number;
		failedElapsedMs: number;
		maxElapsedMs: number;
		responseMs: number;
	};
}

/**
 * The patched OpenAI-completions adapter's failure diagnostic (patches/@earendil-works__pi-ai@1.0.0.patch). The
 * runner reads the same contract in its own tree (server/application/src/main/resources/agent/pi-model-failure.ts):
 * this image and that staged runner are delivered separately, so neither can import the other.
 */
interface ModelFailure {
	kind: string;
	/** ADAPTER when the adapter stated the kind, UNKNOWN included; MISSING without a diagnostic; INVALID if unreadable. */
	source: "ADAPTER" | "MISSING" | "INVALID";
	phase: string | null;
	status: number | null;
	at: number | null;
}
const MODEL_FAILURE_KINDS = new Set([
	"HTTP_ERROR",
	"CONNECTION_TIMEOUT",
	"CONNECTION_ERROR",
	"STREAM_INCOMPLETE",
	"FINISH_REASON_ERROR",
	"ABORTED",
	"UNKNOWN",
]);
const MAX_EPOCH_MS = 8_640_000_000_000_000;

/** A failed message's own diagnostic as closed values; anything malformed is UNKNOWN and claims nothing more. */
function lastDiagnostic(diagnostics: unknown, type: string): unknown {
	const list: unknown[] = Array.isArray(diagnostics) ? diagnostics : [];
	return list.findLast((entry) => isRecord(entry) && entry.type === type);
}

function unknownFailure(source: "MISSING" | "INVALID"): ModelFailure {
	return {
		kind: "UNKNOWN",
		source,
		phase: null,
		status: null,
		at: null,
	};
}

function modelFailure(diagnostics: unknown): ModelFailure {
	const diagnostic = lastDiagnostic(diagnostics, "openai_completions_failure");

	if (diagnostic === undefined) {
		return unknownFailure("MISSING");
	}
	if (!isRecord(diagnostic) || !isRecord(diagnostic.details)) {
		return unknownFailure("INVALID");
	}
	const { kind, phase, status } = diagnostic.details;
	if (typeof kind !== "string" || !MODEL_FAILURE_KINDS.has(kind)) {
		return unknownFailure("INVALID");
	}
	const at = diagnostic.timestamp;
	return {
		kind,
		source: "ADAPTER",
		phase: phase === "request" || phase === "response_body" ? phase : null,
		status:
			kind === "HTTP_ERROR" &&
			Number.isInteger(status) &&
			Number(status) >= 400 &&
			Number(status) <= 599
				? Number(status)
				: null,
		at:
			Number.isSafeInteger(at) && Number(at) >= 0 && Number(at) <= MAX_EPOCH_MS ? Number(at) : null,
	};
}

function canAddMilliseconds(total: number, value: number): boolean {
	return value <= Number.MAX_SAFE_INTEGER - total;
}

function duration(value: unknown): number | null {
	return typeof value === "number" && Number.isSafeInteger(value) && value >= 0 ? value : null;
}

/** Adds one message's adapter call span; an unreadable span is not counted, and a response beyond it is dropped. */
function addModelCall(
	timing: SessionSummary["modelCallTiming"],
	diagnostics: unknown,
	failed: boolean,
): void {
	const diagnostic = lastDiagnostic(diagnostics, "openai_completions_call");
	const details = isRecord(diagnostic) && isRecord(diagnostic.details) ? diagnostic.details : null;
	const elapsed = duration(details?.elapsedMs);
	if (elapsed === null) {
		return;
	}
	const response = duration(details?.responseMs);
	const validResponse = response !== null && response <= elapsed ? response : null;
	if (
		!canAddMilliseconds(timing.elapsedMs, elapsed) ||
		(failed && !canAddMilliseconds(timing.failedElapsedMs, elapsed)) ||
		(validResponse !== null && !canAddMilliseconds(timing.responseMs, validResponse))
	) {
		return;
	}
	timing.timedCalls += 1;
	timing.elapsedMs += elapsed;
	timing.maxElapsedMs = Math.max(timing.maxElapsedMs, elapsed);
	if (failed) {
		timing.failedElapsedMs += elapsed;
	}
	if (validResponse !== null) {
		timing.respondedCalls += 1;
		timing.responseMs += validResponse;
	}
}

export interface TraceManifest {
	schemaVersion: 1;
	budgetBytes: number;
	sessionScanTruncated: boolean;
	sessions: {
		file: string | null;
		bytes: number;
		redacted: boolean;
		partialLineDropped: boolean;
		omitted: "oversize" | "budget" | "unreadable" | null;
		summary: SessionSummary | null;
	}[];
}

function count(counts: Record<string, number>, key: string): void {
	counts[key] = (counts[key] ?? 0) + 1;
}

/** A sum of the file's token counts that stays an exact integer, whatever the file claims. */
function addTokens(total: number, value: unknown): number {
	const tokens = typeof value === "number" && Number.isSafeInteger(value) && value >= 0 ? value : 0;
	return Math.min(Number.MAX_SAFE_INTEGER, total + tokens);
}

function isRecord(value: unknown): value is Record<string, unknown> {
	return typeof value === "object" && value !== null && !Array.isArray(value);
}

/** No custom entry's free-form fields enter a summary. */
function sessionBinding(data: unknown): {
	phase: string | null;
	practiceRevisionId: number | null;
} {
	if (typeof data !== "object" || data === null) {
		return { phase: null, practiceRevisionId: null };
	}
	const phase = "phase" in data ? data.phase : null;
	const revision = "practiceRevisionId" in data ? data.practiceRevisionId : null;
	return {
		phase: typeof phase === "string" && PHASES.has(phase) ? phase : null,
		practiceRevisionId:
			typeof revision === "number" && Number.isSafeInteger(revision) && revision > 0
				? revision
				: null,
	};
}

function addUsage(totals: SessionSummary["usage"], usage: unknown): void {
	if (!isRecord(usage)) {
		return;
	}
	for (const bucket of ["input", "output", "cacheRead", "cacheWrite"] as const) {
		totals[bucket] = addTokens(totals[bucket], bucket in usage ? usage[bucket] : 0);
	}
}

function toolNames(contents: unknown): string[] {
	const list: unknown[] = Array.isArray(contents) ? contents : [];
	return list.flatMap((content) => {
		if (
			typeof content !== "object" ||
			content === null ||
			!("type" in content) ||
			content.type !== "toolCall"
		) {
			return [];
		}
		const name = "name" in content ? content.name : null;
		return [typeof name === "string" && TOOL_NAMES.has(name) ? name : "other"];
	});
}

/** Reads the native entries through the SDK's own parser; every value kept is an allowlisted enum or a number. */
export function summarize(entries: FileEntry[]): SessionSummary {
	const summary: SessionSummary = {
		phase: null,
		practiceRevisionId: null,
		entries: entries.length,
		assistantCalls: 0,
		stopReasons: {},
		usage: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 },
		toolCalls: {},
		toolErrors: 0,
		compactions: 0,
		modelFailures: {},
		lastModelFailureAt: null,
		finalModelFailure: null,
		unattributedModelFailures: 0,
		modelCallTiming: {
			timedCalls: 0,
			respondedCalls: 0,
			elapsedMs: 0,
			failedElapsedMs: 0,
			maxElapsedMs: 0,
			responseMs: 0,
		},
	};
	for (const entry of entries) {
		if (entry.type === "custom" && entry.customType === REVIEW_SESSION_ENTRY) {
			const binding = sessionBinding(entry.data);
			summary.phase = binding.phase;
			summary.practiceRevisionId = binding.practiceRevisionId;
		} else if (entry.type === "compaction") {
			summary.compactions += 1;
		} else if (entry.type === "message") {
			const { message } = entry;
			if (message.role === "assistant") {
				summary.assistantCalls += 1;
				const reason: unknown = message.stopReason;
				count(
					summary.stopReasons,
					typeof reason === "string" && STOP_REASONS.has(reason) ? reason : "other",
				);
				addUsage(summary.usage, message.usage);
				for (const name of toolNames(message.content)) {
					count(summary.toolCalls, name);
				}
				const failed = reason === "error" || reason === "aborted";
				addModelCall(summary.modelCallTiming, message.diagnostics, failed);
				if (failed) {
					const failure = modelFailure(message.diagnostics);
					count(summary.modelFailures, failure.kind);
					summary.unattributedModelFailures += failure.source === "ADAPTER" ? 0 : 1;
					summary.lastModelFailureAt = failure.at ?? summary.lastModelFailureAt;
					summary.finalModelFailure = failure;
				} else {
					summary.finalModelFailure = null;
				}
			} else if (message.role === "toolResult" && message.isError) {
				summary.toolErrors += 1;
			}
		}
	}
	return summary;
}

function tarBytes(size: number): number {
	return BLOCK + Math.ceil(size / BLOCK) * BLOCK;
}

/** What `tar --format=ustar --blocking-factor=1` writes for the directory, end records included. */
async function footprint(directory: string): Promise<{ bytes: number; entries: number }> {
	let bytes = 2 * BLOCK;
	let entries = 0;
	const visit = async (folder: string) => {
		bytes += BLOCK;
		entries += 1;
		const children = await opendir(folder);
		for await (const entry of children) {
			if (bytes >= RESULT_BYTES || entries >= RESULT_ENTRIES) {
				break;
			}
			const child = path.join(folder, entry.name);
			if (entry.isDirectory()) {
				await visit(child);
			} else {
				const size = entry.isFile() ? await lstat(child) : null;
				bytes += tarBytes(size?.size ?? 0);
				entries += 1;
			}
		}
	};
	await visit(directory);
	return { bytes, entries };
}

/** Bounds directory work too: anything beyond the scan is explicitly unknown. */
const MAX_SESSION_ENTRIES = 1000;

async function sessionFiles(directory: string): Promise<{ files: string[]; truncated: boolean }> {
	const found: string[] = [];
	let visited = 0;
	let truncated = false;
	const visit = async (folder: string, depth: number) => {
		const attributes = await lstat(folder).catch(() => null);
		const entries =
			attributes !== null && attributes.isDirectory()
				? await opendir(folder).catch(() => null)
				: null;
		if (entries === null) {
			truncated = true;
			return;
		}
		for await (const entry of entries) {
			if (visited >= MAX_SESSION_ENTRIES) {
				truncated = true;
				break;
			}
			visited += 1;
			const child = path.join(folder, entry.name);
			if (entry.isDirectory() && depth < 2) {
				await visit(child, depth + 1);
			} else if (entry.isFile() && entry.name.endsWith(".jsonl")) {
				found.push(child);
			}
			if (truncated) {
				break;
			}
		}
	};
	await visit(directory, 0);
	return { files: found.toSorted(), truncated };
}

/** A regular file read whole, never through a link, or "oversize" past the limit. */
async function readBounded(file: string, limit: number): Promise<Buffer | "oversize"> {
	const handle = await open(file, fsConstants.O_NOFOLLOW);
	try {
		const stat = await handle.stat();
		if (!stat.isFile()) {
			throw new Error("not a regular file");
		}
		if (stat.size > limit) {
			return "oversize";
		}
		// One read may return fewer bytes than asked for; read until the end or one byte past the limit.
		const buffer = Buffer.alloc(limit + 1);
		let filled = 0;
		while (filled <= limit) {
			const { bytesRead } = await handle.read(buffer, filled, limit + 1 - filled, filled);
			if (bytesRead === 0) {
				break;
			}
			filled += bytesRead;
		}
		return filled > limit ? "oversize" : buffer.subarray(0, filled);
	} finally {
		await handle.close();
	}
}

/** Every literal occurrence of the attempt credential replaced, byte for byte elsewhere. */
function redact(bytes: Buffer, secret: Buffer): { bytes: Buffer; redacted: boolean } {
	const parts: Buffer[] = [];
	let from = 0;
	for (let at = bytes.indexOf(secret, from); at !== -1; at = bytes.indexOf(secret, from)) {
		parts.push(bytes.subarray(from, at), REDACTED);
		from = at + secret.length;
	}
	if (from === 0) {
		return { bytes, redacted: false };
	}
	parts.push(bytes.subarray(from));
	return { bytes: Buffer.concat(parts), redacted: true };
}

/** A process stopped mid-write leaves a torn last line; only whole native entries are kept. */
function wholeLines(bytes: Buffer): { bytes: Buffer; dropped: boolean } {
	if (bytes.length === 0 || bytes.at(-1) === 0x0a) {
		return { bytes, dropped: false };
	}
	return { bytes: bytes.subarray(0, bytes.lastIndexOf(0x0a) + 1), dropped: true };
}

/**
 * Copies the native session files into out/traces within what the result archive has left, with a manifest of what
 * was copied, omitted and summarized. Never throws: on any failure out/traces is removed and the result goes as is.
 */
export async function collectTraces(
	sessionsDirectory: string,
	outDirectory: string,
	secret: string,
): Promise<void> {
	const tracesDirectory = path.join(outDirectory, "traces");
	try {
		// Anything already there was written in the sandbox, not by this collector.
		await rm(tracesDirectory, { recursive: true, force: true });
		const used = await footprint(outDirectory);
		const room = RESULT_BYTES - used.bytes - TRACE_RESERVE;
		const maxFiles = RESULT_ENTRIES - used.entries - 2;
		if (room <= 0 || maxFiles <= 0) {
			return;
		}
		const budgetBytes = Math.min(TRACE_BYTES, room);
		await mkdir(tracesDirectory);
		const secretBytes = Buffer.from(secret, "utf8");
		const found = await sessionFiles(sessionsDirectory);
		const manifest: TraceManifest = {
			schemaVersion: 1,
			budgetBytes,
			sessionScanTruncated: found.truncated,
			sessions: [],
		};
		let spent = 0;
		let copied = 0;
		for (const file of found.files) {
			let read: Buffer | "oversize";
			try {
				read = await readBounded(file, TRACE_FILE_BYTES);
			} catch {
				manifest.sessions.push({
					file: null,
					bytes: 0,
					redacted: false,
					partialLineDropped: false,
					omitted: "unreadable",
					summary: null,
				});
				continue;
			}
			if (read === "oversize") {
				const details = await lstat(file);
				manifest.sessions.push({
					file: null,
					bytes: details.size,
					redacted: false,
					partialLineDropped: false,
					omitted: "oversize",
					summary: null,
				});
				continue;
			}
			const whole = wholeLines(read);
			const guarded = redact(whole.bytes, secretBytes);
			const summary = summarize(parseSessionEntries(guarded.bytes.toString("utf8")));
			const fits = copied < maxFiles && spent + tarBytes(guarded.bytes.length) <= budgetBytes;
			const name = fits ? `${String(copied + 1).padStart(4, "0")}.jsonl` : null;
			if (name !== null) {
				await writeFile(path.join(tracesDirectory, name), guarded.bytes, { flag: "wx" });
				spent += tarBytes(guarded.bytes.length);
				copied += 1;
			}
			manifest.sessions.push({
				file: name,
				bytes: read.length,
				redacted: guarded.redacted,
				partialLineDropped: whole.dropped,
				omitted: name === null ? "budget" : null,
				summary,
			});
		}
		const text = JSON.stringify(manifest);
		if (tarBytes(Buffer.byteLength(text)) + BLOCK > TRACE_RESERVE) {
			throw new Error("the transcript manifest exceeds its reserve");
		}
		await writeFile(path.join(tracesDirectory, "manifest.json"), text, { flag: "wx" });
	} catch (error) {
		// The kind of failure only: a message may quote a path or a session's bytes.
		console.error(
			`session transcripts were not collected: ${error instanceof Error ? error.name : "unknown error"}`,
		);
		await rm(tracesDirectory, { recursive: true, force: true }).catch(() => undefined);
	}
}

async function main() {
	const [command, ...args] = process.argv.slice(2);
	const endpoint = process.env.SANDBOX_RUNTIME_URL;
	const token = process.env.LLM_PROXY_TOKEN;
	if (!present(command) || !present(endpoint) || !present(token)) {
		throw new Error("Command and gateway routing are required");
	}
	const workDeadline = Number(process.env.SANDBOX_WORK_DEADLINE_MS);
	const uploadDeadline = Number(process.env.SANDBOX_UPLOAD_DEADLINE_MS);
	if (
		!Number.isSafeInteger(workDeadline) ||
		!Number.isSafeInteger(uploadDeadline) ||
		uploadDeadline <= workDeadline
	) {
		throw new Error("Valid sandbox work and upload deadlines are required");
	}
	const child = spawn(command, args, { stdio: "inherit" });
	const workTimeout = new AbortController();
	const deadlineTimer = setTimeout(
		() => {
			workTimeout.abort();
			child.kill("SIGTERM");
			setTimeout(() => {
				child.kill("SIGKILL");
			}, CHILD_STOP_GRACE_MS).unref();
		},
		Math.max(0, workDeadline - Date.now()),
	);
	deadlineTimer.unref();
	process.once("SIGTERM", () => {
		child.kill("SIGTERM");
		setTimeout(() => {
			child.kill("SIGKILL");
		}, CHILD_STOP_GRACE_MS).unref();
	});
	const exitCode = await exited(child);
	clearTimeout(deadlineTimer);
	await collectTraces("/workspace/.sessions", "/workspace/out", token);
	const directory = await mkdtemp(path.join(tmpdir(), "gateway-output-"));
	try {
		const archive = path.join(directory, "result.tar");
		if (
			(await run("tar", [
				"--create",
				"--format=ustar",
				"--blocking-factor=1",
				"--file",
				archive,
				"--directory",
				"/workspace",
				"out",
			])) !== 0
		) {
			throw new Error("Could not package sandbox result");
		}
		try {
			await upload(new URL(`${endpoint}/result`), token, archive, uploadDeadline);
		} catch (error) {
			console.error(error);
			process.exitCode = EXIT_UPLOAD_FAILED;
			return;
		}
	} finally {
		await rm(directory, { recursive: true, force: true });
	}
	process.exitCode = workTimeout.signal.aborted ? EXIT_WORK_TIMEOUT : exitCode;
}

if (import.meta.main) {
	try {
		await main();
	} catch (error) {
		console.error(error);
		process.exitCode = 1;
	}
}
