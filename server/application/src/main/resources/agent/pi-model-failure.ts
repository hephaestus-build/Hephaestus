/**
 * The diagnostics the patched OpenAI-completions adapter attaches to an assistant message
 * (docker/agents/pi/patches/@earendil-works__pi-ai@1.0.0.patch): `openai_completions_failure` on a failed call, and
 * `openai_completions_call` on every call whose SDK request began. docker/agents/pi/gateway-run.ts reads the same
 * contract from session files.
 */
export const MODEL_FAILURE_KINDS = [
	"HTTP_ERROR",
	"CONNECTION_TIMEOUT",
	"CONNECTION_ERROR",
	"STREAM_INCOMPLETE",
	"FINISH_REASON_ERROR",
	"ABORTED",
	"UNKNOWN",
] as const;

export type ModelFailureKind = (typeof MODEL_FAILURE_KINDS)[number];

export interface ModelFailure {
	kind: ModelFailureKind;
	/**
	 * ADAPTER when the adapter stated the kind, UNKNOWN included; MISSING when the message carries no failure diagnostic;
	 * INVALID when it carries one this contract cannot read.
	 */
	source: "ADAPTER" | "MISSING" | "INVALID";
	/** The adapter stage: before or after obtaining the SDK response. */
	phase: "request" | "response_body" | null;
	/** The native HTTP status, for HTTP_ERROR only. */
	status: number | null;
	/** The adapter's own failure time, in epoch milliseconds. */
	at: number | null;
}

/** One call's adapter span in monotonic milliseconds: request start to message end, and to the SDK response. */
export interface ModelCall {
	elapsedMs: number;
	responseMs: number | null;
}

/** The largest time a JavaScript Date can hold. */
const MAX_EPOCH_MS = 8_640_000_000_000_000;

function isRecord(value: unknown): value is Record<string, unknown> {
	return typeof value === "object" && value !== null && !Array.isArray(value);
}

function lastDiagnostic(message: unknown, type: string): unknown {
	const diagnostics: unknown[] =
		isRecord(message) && Array.isArray(message.diagnostics) ? message.diagnostics : [];
	return diagnostics.findLast((entry) => isRecord(entry) && entry.type === type);
}

function canAddMilliseconds(total: number, value: number): boolean {
	return value <= Number.MAX_SAFE_INTEGER - total;
}

function duration(value: unknown): number | null {
	return Number.isSafeInteger(value) && Number(value) >= 0 ? Number(value) : null;
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

/**
 * A failed message's own diagnostic, validated value by value. Text the message carries is never read; a missing or
 * malformed diagnostic is UNKNOWN and claims nothing more.
 */
export function modelFailure(message: unknown): ModelFailure {
	const diagnostic = lastDiagnostic(message, "openai_completions_failure");

	if (diagnostic === undefined) {
		return unknownFailure("MISSING");
	}
	if (!isRecord(diagnostic) || !isRecord(diagnostic.details)) {
		return unknownFailure("INVALID");
	}
	const { details, timestamp } = diagnostic;
	const kind = MODEL_FAILURE_KINDS.find((candidate) => candidate === details.kind);
	if (kind === undefined) {
		return unknownFailure("INVALID");
	}
	const { phase, status } = details;
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
			Number.isSafeInteger(timestamp) && Number(timestamp) >= 0 && Number(timestamp) <= MAX_EPOCH_MS
				? Number(timestamp)
				: null,
	};
}

/** A message's call span, or null when it has no readable one: a response time beyond the span is dropped. */
export function modelCall(message: unknown): ModelCall | null {
	const diagnostic = lastDiagnostic(message, "openai_completions_call");
	const details = isRecord(diagnostic) && isRecord(diagnostic.details) ? diagnostic.details : null;
	const elapsedMs = duration(details?.elapsedMs);
	if (elapsedMs === null) {
		return null;
	}
	const responseMs = duration(details?.responseMs);
	return {
		elapsedMs,
		responseMs: responseMs !== null && responseMs <= elapsedMs ? responseMs : null,
	};
}

/** Calls summed by their adapter spans; a call without one is not counted, never counted as zero. */
export interface ModelCallTiming {
	timedCalls: number;
	respondedCalls: number;
	elapsedMs: number;
	failedElapsedMs: number;
	maxElapsedMs: number;
	responseMs: number;
}

export function newModelCallTiming(): ModelCallTiming {
	return {
		timedCalls: 0,
		respondedCalls: 0,
		elapsedMs: 0,
		failedElapsedMs: 0,
		maxElapsedMs: 0,
		responseMs: 0,
	};
}

/** Adds one message's span; `failed` is its own error or aborted stop reason. */
export function addModelCall(timing: ModelCallTiming, message: unknown, failed: boolean): void {
	const call = modelCall(message);
	if (call === null) {
		return;
	}
	if (
		!canAddMilliseconds(timing.elapsedMs, call.elapsedMs) ||
		(failed && !canAddMilliseconds(timing.failedElapsedMs, call.elapsedMs)) ||
		(call.responseMs !== null && !canAddMilliseconds(timing.responseMs, call.responseMs))
	) {
		return;
	}
	timing.timedCalls += 1;
	timing.elapsedMs += call.elapsedMs;
	timing.maxElapsedMs = Math.max(timing.maxElapsedMs, call.elapsedMs);
	if (failed) {
		timing.failedElapsedMs += call.elapsedMs;
	}
	if (call.responseMs !== null) {
		timing.respondedCalls += 1;
		timing.responseMs += call.responseMs;
	}
}
