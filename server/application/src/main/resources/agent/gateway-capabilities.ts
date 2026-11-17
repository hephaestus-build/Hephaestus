import {
	closeSync,
	constants,
	fstatSync,
	lstatSync,
	openSync,
	readSync,
	rmSync,
	writeFileSync,
} from "node:fs";
import { setTimeout as sleep } from "node:timers/promises";

import { errorKind } from "./pi-diagnostics.ts";
import { PROGRAM_FAILURE } from "./sandbox-paths.ts";

/**
 * A review program's crash, for the review kind to report with the run's end: the server keeps no sandbox log. One
 * line, `<program>:<error kind>`, of closed codes only.
 */
const PROGRAM_FAILURE_LINE = /^(?:init|run):[A-Za-z][A-Za-z.\-_]{0,63}$/u;
/** The longest record that the programs write, with room to spare. */
const PROGRAM_FAILURE_MAX_BYTES = 128;
/** What the kind reports for a record that is not a small regular file. */
export const UNREADABLE_PROGRAM_FAILURE = "record:unreadable";

// The runner shares /tmp and runs model-driven code, so the record's path is untrusted. Something there can be a FIFO,
// which blocks a plain open, or a link to anywhere. Each side therefore opens only a file that it can trust: the
// writer creates a new one, and the reader opens a regular file without following links and without blocking.

export function recordProgramFailure(
	program: "init" | "run",
	error: unknown,
	file: string = PROGRAM_FAILURE,
): void {
	try {
		rmSync(file, { force: true });
		writeFileSync(file, `${program}:${errorKind(error)}`, { flag: "wx", mode: 0o600 });
	} catch {
		// The exit code alone still ends the run.
	}
}

type Recorded = { kind: "none" } | { kind: "unreadable" } | { kind: "line"; line: string };

function readRecord(file: string): Recorded {
	try {
		if (!lstatSync(file).isFile()) {
			return { kind: "unreadable" };
		}
	} catch {
		return { kind: "none" };
	}
	let descriptor: number;
	try {
		// oxlint-disable-next-line no-bitwise -- open flags are a bit set
		descriptor = openSync(file, constants.O_RDONLY | constants.O_NOFOLLOW | constants.O_NONBLOCK);
	} catch {
		return { kind: "unreadable" };
	}
	try {
		const opened = fstatSync(descriptor);
		if (!opened.isFile() || opened.size > PROGRAM_FAILURE_MAX_BYTES) {
			return { kind: "unreadable" };
		}
		const buffer = Buffer.alloc(PROGRAM_FAILURE_MAX_BYTES);
		const length = readSync(descriptor, buffer, 0, PROGRAM_FAILURE_MAX_BYTES, 0);
		return { kind: "line", line: buffer.subarray(0, length).toString("utf8").trim() };
	} catch {
		return { kind: "unreadable" };
	} finally {
		closeSync(descriptor);
	}
}

/**
 * The failure a review program recorded since the last call, if any; it is gone afterwards. A record that is not a
 * small regular file is never read: the kind reports it as unreadable.
 */
export function takeProgramFailure(file: string = PROGRAM_FAILURE): string | undefined {
	const recorded = readRecord(file);
	if (recorded.kind === "none") {
		return undefined;
	}
	try {
		// Removes a link or a FIFO itself, never what a link points to.
		rmSync(file, { force: true });
	} catch {
		// A directory stays, and reads as unreadable again.
	}
	if (recorded.kind === "unreadable") {
		return UNREADABLE_PROGRAM_FAILURE;
	}
	return PROGRAM_FAILURE_LINE.test(recorded.line) ? recorded.line : undefined;
}

export interface GatewayCapabilities {
	workspaceByteBudget: number;
}

const PROTOCOL_VERSION = 3;

const LIMIT_WAIT_MS = 1000;

/** How long the gateway asks a limited caller to wait. */
export function retryAfterMs(response: Response): number {
	const retry = response.headers.get("retry-after");
	const seconds = retry === null ? Number.NaN : Number(retry);
	if (Number.isFinite(seconds) && seconds >= 0) {
		return seconds * 1000;
	}
	const date = retry === null ? Number.NaN : Date.parse(retry);
	return Number.isFinite(date) ? Math.max(0, date - Date.now()) : LIMIT_WAIT_MS;
}

function isByteBudget(value: unknown): value is number {
	return typeof value === "number" && Number.isSafeInteger(value) && value > 0;
}

/** A prepared input stays on its claim while admission is busy, bounded by the work signal. */
export async function fetchGatewayInput(
	endpoint: string,
	headers: Record<string, string>,
	signal: AbortSignal,
): Promise<Response> {
	for (;;) {
		signal.throwIfAborted();
		try {
			const response = await fetch(endpoint, { headers, signal });
			if (response.status !== 503 && response.status !== 429) {
				return response;
			}
			await response.body?.cancel();
			await sleep(retryAfterMs(response), undefined, { signal });
		} catch (error) {
			if (signal.aborted || !(error instanceof TypeError)) {
				throw error;
			}
			await sleep(LIMIT_WAIT_MS, undefined, { signal });
		}
	}
}

/** What the gateway advertises for this session, refused whole when any field is not as declared. */
export async function discoverCapabilities(
	endpoint: string,
	headers: Record<string, string>,
	signal: AbortSignal,
): Promise<GatewayCapabilities> {
	const discovery = await fetchGatewayInput(endpoint, headers, signal);
	if (!discovery.ok) {
		await discovery.body?.cancel();
		throw new Error(`Gateway discovery refused: ${discovery.status}`);
	}
	const capabilities: unknown = await discovery.json();
	if (
		typeof capabilities !== "object" ||
		capabilities === null ||
		!("protocolVersion" in capabilities) ||
		capabilities.protocolVersion !== PROTOCOL_VERSION ||
		!("workspaceByteBudget" in capabilities) ||
		!isByteBudget(capabilities.workspaceByteBudget)
	) {
		throw new Error("Invalid gateway capabilities");
	}
	return { workspaceByteBudget: capabilities.workspaceByteBudget };
}

/** Whether an environment value is set and not empty: the gateway refuses to start on either. */
export function present(value: string | undefined): value is string {
	return value !== undefined && value !== "";
}
