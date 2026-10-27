import {
	CircleAlertIcon,
	CircleCheckIcon,
	CircleDashedIcon,
	CircleSlashIcon,
	ClockIcon,
	EyeOffIcon,
	HourglassIcon,
	LoaderIcon,
	PackageXIcon,
	TimerOffIcon,
} from "lucide-react";

import type { ReviewJob, ReviewPractice, ReviewRef, ReviewSummary } from "@/api/types.gen";

import type { StatusDefs } from "@/components/common/status-def";

export type ReviewStatus = ReviewRef["status"];
export type ReviewStopReason = NonNullable<ReviewSummary["stopReason"]>;
export type ReviewPracticeStatus = ReviewPractice["status"];
export type ReviewJobStatus = ReviewJob["status"];
export type ReviewJobProcessingStatus = ReviewJob["processingStatus"];

// Review status describes execution; result outcomes and feedback delivery are separate facts.
export const REVIEW_STATUS_DEFS: StatusDefs<ReviewStatus> = {
	QUEUED: {
		label: "Queued",
		icon: ClockIcon,
		badgeVariant: "secondary",
		description:
			"Waiting to start: for capacity, for room for its sandbox, for the workspace’s AI budget, or for a retry.",
	},
	RUNNING: {
		label: "Running",
		icon: LoaderIcon,
		badgeVariant: "secondary",
		description: "Being run now. Observations appear as it finishes.",
	},
	COMPLETE: {
		label: "Complete",
		icon: CircleCheckIcon,
		badgeVariant: "success",
		description: "It ran to the end. Whether it found or sent anything is a separate question.",
	},
	PARTIAL: {
		label: "Partial",
		icon: CircleDashedIcon,
		badgeVariant: "warning",
		description: "Its observations cover only the work it reached.",
	},
	FAILED: {
		label: "Failed",
		icon: CircleAlertIcon,
		badgeVariant: "destructive",
		description: "It stopped on an error. Observations it recorded before then still stand.",
	},
	CANCELLED: {
		label: "Cancelled",
		icon: CircleSlashIcon,
		badgeVariant: "outline",
		description: "It was stopped before it finished.",
	},
};

/**
 * How a review ended, as the developer's own surfaces read it. Those surfaces carry no stop reason,
 * so each sentence must hold for every reason. A partial review may have run to the end with a
 * practice failed or withheld, or may predate a record of what it covered, so it names only scope.
 */
export const OWN_REVIEW_STATUS_DEFS: StatusDefs<ReviewStatus> = {
	QUEUED: {
		label: "Waiting",
		icon: ClockIcon,
		badgeVariant: "secondary",
		description:
			"The review has not started yet. It waits for capacity, the workspace’s AI budget, or a retry.",
	},
	RUNNING: {
		label: "Running",
		icon: LoaderIcon,
		badgeVariant: "secondary",
		description: "The review is running. What it records appears as it finishes.",
	},
	COMPLETE: {
		label: "Completed",
		icon: CircleCheckIcon,
		badgeVariant: "success",
		description: "The review ran to the end. Whether it found anything is a separate question.",
	},
	PARTIAL: {
		label: "Partial",
		icon: CircleDashedIcon,
		badgeVariant: "warning",
		description: "The review’s observations cover only the work it reached.",
	},
	FAILED: {
		label: "Failed",
		icon: CircleAlertIcon,
		badgeVariant: "destructive",
		description: "The review stopped before it finished. What it recorded up to then still stands.",
	},
	CANCELLED: {
		label: "Cancelled",
		icon: CircleSlashIcon,
		badgeVariant: "outline",
		description: "The review was stopped before it finished.",
	},
};

/**
 * What a partial review's results cover, beside the recorded stop reason that says why. A partial
 * review may have stopped early or run to the end with a practice failed or withheld, so this names
 * only the scope and never the cause.
 */
export const PARTIAL_REVIEW_SCOPE: Record<ReviewRef["kind"], string> = {
	ITEM: "Its observations and feedback cover only the practices it reviewed.",
	SCAN: "Its observations cover only the work it reached.",
};

export function isActiveReview(status: ReviewStatus): boolean {
	return status === "QUEUED" || status === "RUNNING";
}

/** Why a review stopped, as the server recorded it. */
export const REVIEW_STOP_REASON_SENTENCES: Record<ReviewStopReason, string> = {
	TIMED_OUT: "It ran past the time it is allowed and was stopped.",
	EXECUTION_FAILED: "Running the review failed.",
	PREPARATION_FAILED: "Preparing the review failed.",
	PROCESSING_FAILED: "Processing the review’s output failed.",
	PERSON_DATA_ERASED: "A person’s data was erased.",
	STALE_ROLLOUT_REVISION: "Review settings changed while it waited.",
	CANCELLED_BY_ADMIN: "A workspace admin cancelled it.",
	REPLACED: "A newer review of the same work replaced it.",
	LEGACY_FAILURE: "It failed before Hephaestus recorded why.",
	EXECUTION_INCOMPLETE: "The review did not record an observation for every selected practice.",
	RESULT_WITHHELD: "Its candidate observations were withheld.",
	READINESS_REFUSED: "Something the review needs was not ready.",
	SUSPECT_LISTING: "The file listing changed too much to trust, so files were not removed.",
	BUDGET: "The scan’s budget was used up.",
	USAGE_UNVERIFIABLE: "We could not verify the cost of earlier model calls, so the scan stopped.",
	DEADLINE: "The scan did not finish before its deadline.",
	CANCELLED: "It was cancelled.",
	ENUMERATION_FAILED: "Listing the repository’s files failed.",
	ENUMERATION_LIMIT: "The repository has more files than one scan can list.",
	NO_SCAN_PRACTICES: "No practice is included in scans.",
	MODEL_CHANGED: "The assigned AI model changed while it ran.",
	MODEL_UNAVAILABLE: "The assigned AI model was unavailable.",
	ERROR: "It stopped on an error.",
	LEGACY_SCOPE_UNKNOWN: "It ran before Hephaestus recorded what it covered.",
	CONTRACT_CHANGED: "Hephaestus was upgraded while it waited, so it stopped.",
};

/** The wire values of the server's `CandidateRefusal`; each withholds an observation that failed that check. */
const CANDIDATE_REFUSALS = [
	"malformed_candidate",
	"outcome_problem_mismatch",
	"warrant_outcome_mismatch",
	"result_citations_required",
	"met_coverage_incomplete",
	"coverage_inconsistent",
	"example_outcome_mismatch",
	"example_overlaps_problem",
	"basis_citation_out_of_range",
	"duplicate_problem_key",
	"basis_state_unsupported",
	"basis_revision_unsupported",
	"quote_unverified",
	"source_unavailable",
	"basis_witness_missing",
	"duty_quote_unverified",
] as const;

const CANDIDATE_REFUSAL_SENTENCE =
	"The candidate observation failed one of Hephaestus’s checks, so it was not recorded.";

const REVIEW_WITHHELD_REASON_SENTENCES: Readonly<Record<string, string>> = {
	...REVIEW_STOP_REASON_SENTENCES,
	...Object.fromEntries(CANDIDATE_REFUSALS.map((code) => [code, CANDIDATE_REFUSAL_SENTENCE])),
	readiness_refused: "Evidence that this practice requires could not be captured.",
	ROLE_UNAVAILABLE: "No eligible person holds the role that this practice is about on this work.",
	ROLE_AMBIGUOUS:
		"More than one eligible person holds the role that this practice is about, and Hephaestus does not guess which one.",
	OUTSIDE_REVIEW_SCOPE: "This work or this person is outside the workspace’s review scope.",
	WORKSPACE_INACTIVE: "The workspace was not active when the candidate observation arrived.",
	COPY_AUTHORITY_UNAVAILABLE:
		"Hephaestus could not confirm the record of whose data this review copied, so the candidate observation was not recorded.",
	LEGACY_AMBIGUOUS_RESULTS:
		"This review keeps several historical observations for the same practice and work without choosing one as its answer.",
	LEGACY_REUSE_UNBOUND:
		"This review referred to an earlier observation that could not be identified exactly.",
	source_contract_unsupported:
		"Hephaestus cannot use this practice’s evidence settings for this work. Ask a workspace admin to check them.",
	item_capability_unsupported:
		"Hephaestus cannot use this practice’s Review on or About settings for this kind of work. Ask a workspace admin to check them.",
};

export function reviewReasonSentence(code: string | null): string | undefined {
	return code !== null && Object.hasOwn(REVIEW_WITHHELD_REASON_SENTENCES, code)
		? REVIEW_WITHHELD_REASON_SENTENCES[code]
		: undefined;
}

/** Where one practice stands inside one review. Withheld is a decision with a reason, not an observation. */
export const REVIEW_PRACTICE_STATUS_DEFS: StatusDefs<ReviewPracticeStatus> = {
	QUEUED: {
		label: "Queued",
		icon: ClockIcon,
		badgeVariant: "secondary",
		description: "Waiting for its turn in the review.",
	},
	READY: {
		label: "Ready",
		icon: HourglassIcon,
		badgeVariant: "secondary",
		description: "Everything it reads was captured.",
	},
	RUNNING: {
		label: "Running",
		icon: LoaderIcon,
		badgeVariant: "secondary",
		description: "Being reviewed now.",
	},
	DONE: {
		label: "Done",
		icon: CircleCheckIcon,
		badgeVariant: "success",
		description: "It recorded its observations.",
	},
	WITHHELD: {
		label: "Withheld",
		icon: EyeOffIcon,
		badgeVariant: "warning",
		description: "It was not reviewed, for the recorded reason. This is not an observation.",
	},
	FAILED: {
		label: "Failed",
		icon: CircleAlertIcon,
		badgeVariant: "destructive",
		description: "Reviewing it failed. It recorded no observation.",
	},
};

export const REVIEW_JOB_STATUS_DEFS: StatusDefs<ReviewJobStatus> = {
	QUEUED: {
		label: "Queued",
		icon: ClockIcon,
		badgeVariant: "secondary",
		description: "Waiting for a worker.",
	},
	RUNNING: {
		label: "Running",
		icon: LoaderIcon,
		badgeVariant: "secondary",
		description: "A worker is running it.",
	},
	COMPLETED: {
		label: "Completed",
		icon: CircleCheckIcon,
		badgeVariant: "success",
		description: "It ran to the end.",
	},
	FAILED: {
		label: "Failed",
		icon: CircleAlertIcon,
		badgeVariant: "destructive",
		description: "It stopped on an error.",
	},
	TIMED_OUT: {
		label: "Timed out",
		icon: TimerOffIcon,
		badgeVariant: "destructive",
		description: "It ran past the time it is allowed and was stopped.",
	},
	CANCELLED: {
		label: "Cancelled",
		icon: CircleSlashIcon,
		badgeVariant: "outline",
		description: "It was stopped before it finished.",
	},
};

/** Whether one job's output was processed into results. Processing never proves delivery. */
export const REVIEW_JOB_PROCESSING_DEFS: StatusDefs<ReviewJobProcessingStatus> = {
	NONE: {
		label: "Nothing to process",
		icon: PackageXIcon,
		badgeVariant: "outline",
		description: "The job produced no output to process.",
	},
	PENDING: {
		label: "Awaiting processing",
		icon: ClockIcon,
		badgeVariant: "secondary",
		description: "Its output is waiting to be processed.",
	},
	DONE: {
		label: "Processed",
		icon: CircleCheckIcon,
		badgeVariant: "success",
		description: "Its output was processed. Feedback may still await approval or be withheld.",
	},
	FAILED: {
		label: "Processing failed",
		icon: CircleAlertIcon,
		badgeVariant: "destructive",
		description: "Processing its output failed.",
	},
};
