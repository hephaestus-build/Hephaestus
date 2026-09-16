import type { AgentJob } from "@/api/types.gen";
import { asDate } from "@/lib/dates";
import { humanizeToken } from "@/lib/humanize";
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
 * `holdReason` is a plain string on the wire and the server may add reasons, so an unknown one still
 * has to read as English: a map for what we know, humanised underscores for what we don't.
 */
const HOLD_REASON_COPY: Record<string, HoldReasonCopy | undefined> = {
	BUDGET: {
		label: "Over the AI budget",
		detail:
			"The monthly AI cap is spent, so this run is parked rather than failed. It resumes on its own once the cap is raised or the month rolls over. AI usage names which purse is capped and who can lift it.",
	},
};

const UNKNOWN_HOLD_DETAIL =
	"This run is parked rather than failed. It resumes on its own once the hold lifts.";

export function holdReasonCopy(reason: string): HoldReasonCopy {
	const known = HOLD_REASON_COPY[reason];
	if (known) {
		return known;
	}
	return { label: humanizeToken(reason), detail: UNKNOWN_HOLD_DETAIL };
}

export function isCancellable(status: AgentJob["status"]): boolean {
	return status === "QUEUED" || status === "RUNNING";
}

export function isResultProcessingRetryable(
	job: Pick<AgentJob, "status" | "deliveryStatus">,
): boolean {
	return job.status === "COMPLETED" && job.deliveryStatus === "FAILED";
}

export function formatTokens(value: number | undefined): string {
	if (value == null) {
		return "—";
	}
	return value.toLocaleString();
}

/**
 * Pads a money figure so its decimal point lands where every other row's does — `tabular-nums`
 * equalises glyph *width* but not a missing `.00`. `visibility: hidden` keeps the space that
 * `display: none` would collapse, and `aria-hidden` keeps the pad out of the accessible name.
 */
export function MoneyCell({ children }: { children: string }) {
	return (
		<>
			{children}
			{!children.includes(".") && (
				<span className="invisible" aria-hidden>
					.00
				</span>
			)}
		</>
	);
}

/** The submit-time snapshot, not the runner-reported `llmModel`, which exists only once it has run. */
export function modelLabel(job: Pick<AgentJob, "model">): string {
	return job.model ?? "—";
}
