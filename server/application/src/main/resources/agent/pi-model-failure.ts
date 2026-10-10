/**
 * The failure diagnostic the patched OpenAI-completions adapter attaches to a failed assistant message
 * (docker/agents/pi/patches/@earendil-works__pi-ai@1.0.0.patch): type `openai_completions_failure`, its own
 * timestamp, and closed details. docker/agents/pi/gateway-run.ts reads the same contract from session files.
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
	/** The adapter stage: before or after obtaining the SDK response. */
	phase: "request" | "response_body" | null;
	/** The native HTTP status, for HTTP_ERROR only. */
	status: number | null;
	/** The adapter's own failure time, in epoch milliseconds. */
	at: number | null;
}

/** The largest time a JavaScript Date can hold. */
const MAX_EPOCH_MS = 8_640_000_000_000_000;

function isRecord(value: unknown): value is Record<string, unknown> {
	return typeof value === "object" && value !== null && !Array.isArray(value);
}

/**
 * A failed message's own diagnostic, validated value by value. Text the message carries is never read; a missing or
 * malformed diagnostic is UNKNOWN and claims nothing more.
 */
export function modelFailure(message: unknown): ModelFailure {
	const unknown: ModelFailure = { kind: "UNKNOWN", phase: null, status: null, at: null };
	const diagnostics: unknown[] =
		isRecord(message) && Array.isArray(message.diagnostics) ? message.diagnostics : [];
	const diagnostic = diagnostics.findLast(
		(entry) => isRecord(entry) && entry.type === "openai_completions_failure",
	);
	if (!isRecord(diagnostic) || !isRecord(diagnostic.details)) {
		return unknown;
	}
	const { details, timestamp } = diagnostic;
	const kind = MODEL_FAILURE_KINDS.find((candidate) => candidate === details.kind);
	if (kind === undefined) {
		return unknown;
	}
	const { phase, status } = details;
	return {
		kind,
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
