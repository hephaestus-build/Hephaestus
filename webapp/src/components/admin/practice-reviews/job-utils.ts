import type { AgentJob } from "@/api/types.gen";
import { asDate } from "@/lib/dates";
import { hasText } from "@/lib/text";

export type JobWait = { kind: "hold"; reason: string } | { kind: "backoff" };

/**
 * `null` for a run that is simply claimable: `availableAt` is in the past for almost every run, so a
 * "due …" line on every queued row would be noise. A hold is keyed off `holdReason` alone, not the
 * clock — the server re-parks a still-capped run each time its `availableAt` lapses.
 */
export function jobWait(
	job: Pick<AgentJob, "status" | "holdReason" | "availableAt">,
	now: number,
): JobWait | null {
	if (job.status !== "QUEUED") {
		return null;
	}
	if (hasText(job.holdReason)) {
		return { kind: "hold", reason: job.holdReason };
	}
	const availableAt = asDate(job.availableAt);
	return availableAt && availableAt.getTime() > now ? { kind: "backoff" } : null;
}

export interface HoldReasonCopy {
	label: string;
	/** Sentence for the details panel. Never says "failed": a hold is a wait that ends by itself. */
	detail: string;
}

/**
 * `holdReason` is a plain string on the wire and the server may add reasons, so an unknown one reads
 * as a plain "On hold" rather than as its constant.
 */
const HOLD_REASON_COPY: Record<string, HoldReasonCopy | undefined> = {
	BUDGET: {
		label: "Over the AI budget",
		detail:
			"The monthly AI budget is used up, so this review is waiting, not failed. It continues on its own when the budget is raised or the next month starts. AI usage shows which budget is used up and who can raise it.",
	},
};

const UNKNOWN_HOLD_DETAIL =
	"This review is waiting, not failed. It continues on its own when the hold ends.";

export function holdReasonCopy(reason: string): HoldReasonCopy {
	const known = HOLD_REASON_COPY[reason];
	if (known) {
		return known;
	}
	return { label: "On hold", detail: UNKNOWN_HOLD_DETAIL };
}

export function isCancellable(status: AgentJob["status"]): boolean {
	return status === "QUEUED" || status === "RUNNING";
}

export function isResultProcessingRetryable(
	job: Pick<AgentJob, "status" | "deliveryStatus">,
): boolean {
	return job.status === "COMPLETED" && job.deliveryStatus === "FAILED";
}

/** The submit-time snapshot, not the runner-reported `llmModel`, which exists only once it has run. */
export function modelLabel(job: Pick<AgentJob, "model">): string {
	return job.model ?? "—";
}
