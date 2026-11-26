import type { SupportShare, SupportShareTurn } from "@/api/types.gen";
import { formatDateTime } from "@/lib/dates";

/** How a turn ended or stands, in the words the person and the admin both read. */
const TURN_STATUS_LABELS: Record<string, string> = {
	QUEUED: "Waiting to start",
	RUNNING: "Running",
	DONE: "Answered",
	FAILED: "Failed",
	STOPPED: "Stopped",
};

/** Why a turn failed, as a closed kind: never the text of an error. */
const FAILURE_REASON_LABELS: Record<string, string> = {
	START_WAIT_EXPIRED: "It waited too long for a free Hephaestus",
	ATTEMPTS_EXHAUSTED: "Hephaestus lost its server twice",
	MODEL_UNAVAILABLE: "The AI model or its provider stopped answering",
	CONTEXT_ERASED: "Your data was erased while Hephaestus answered",
	UNKNOWN: "Hephaestus recorded no reason",
};

export const SHARE_STATE_LABELS: Record<SupportShare["state"], string> = {
	ACTIVE: "Active",
	EXPIRED: "Expired",
	REVOKED: "Revoked",
};

export interface DiagnosticLine {
	term: string;
	value: string;
	/** An identifier an operator searches the server's logs for. */
	code?: boolean;
}

/**
 * What the admin reads about how Hephaestus ran a question: states, times, the kind of a failure and
 * the ids for the logs. Never words of the question or the answer.
 */
export function diagnosticLines(turn: SupportShareTurn): DiagnosticLine[] {
	const lines: DiagnosticLine[] = [
		{ term: "Status", value: TURN_STATUS_LABELS[turn.status] ?? turn.status },
		{ term: "Asked", value: formatDateTime(turn.queuedAt) },
	];
	if (turn.startedAt !== undefined) {
		lines.push({ term: "Started", value: formatDateTime(turn.startedAt) });
	}
	if (turn.finishedAt !== undefined) {
		lines.push({ term: "Ended", value: formatDateTime(turn.finishedAt) });
	}
	if (turn.attempt > 1) {
		lines.push({ term: "Attempts", value: String(turn.attempt) });
	}
	if (turn.failureReason !== undefined) {
		lines.push({
			term: "Why it failed",
			value: FAILURE_REASON_LABELS[turn.failureReason] ?? turn.failureReason,
		});
	}
	if (turn.failedCall !== undefined) {
		const status = turn.failedCallStatus === undefined ? "" : ` (status ${turn.failedCallStatus})`;
		lines.push({ term: "Failed model call", value: `${turn.failedCall}${status}`, code: true });
	}
	lines.push(
		{ term: "Run", value: turn.runId, code: true },
		{ term: "Question", value: turn.turnId, code: true },
	);
	return lines;
}
