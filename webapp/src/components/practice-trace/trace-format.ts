import type { PracticeTraceEntry, ReviewRequestOutcome, TracedSignal } from "@/api/types.gen";
import type { ReviewSectionId } from "@/components/admin/practices/review/review-sections";
import { statusValues } from "@/components/practice-vocabulary/status-def";
import {
	TRACE_OUTCOME_DEFS,
	type TraceOutcome,
} from "@/components/practice-vocabulary/trace-outcome-defs";
import { WITHHOLDING_REASON_DEFS } from "@/components/practice-vocabulary/withholding-defs";

export type { TraceOutcome };
export type WithheldReason = PracticeTraceEntry["withheldReasons"][number];
export type SignalState = TracedSignal["state"];
export type SignalStateReason = NonNullable<TracedSignal["stateReason"]>;
export type DiscoveredVia = TracedSignal["discoveredVia"];

export const OUTCOMES = statusValues(TRACE_OUTCOME_DEFS);

export const SIGNAL_STATE_LABELS: Record<SignalState, string> = {
	RECORDED: "Recorded",
	DEFERRED: "Waiting to see if this keeps changing",
	TRIGGERED: "Started a review",
	SUPPRESSED: "No review started",
	PENDING: "Queued for review",
	LAPSED: "Expired before it was reviewed",
};

/**
 * Third person throughout: any member of the workspace can open this page, so the occurrence being
 * explained is usually somebody else's.
 */
export const SIGNAL_STATE_REASON_LABELS: Record<SignalStateReason, string> = {
	GATE_SKIPPED: "This workspace's review settings turned it away",
	COOLDOWN_ACTIVE: "This work was reviewed too recently; a later change gets its own review",
	REQUEST_COOLDOWN_ACTIVE: "A review of this was already asked for a moment ago",
	REQUESTER_QUOTA_EXHAUSTED: "Whoever asked had used up their hour's allowance, which refills",
	CONCURRENT_DUPLICATE: "The same review was already running",
	COALESCED: "Later issue changes replaced this update before a review started",
	OUT_OF_REVIEW_SCOPE:
		"The author, repository, or base branch is outside the workspace's review coverage",
	WORKSPACE_INACTIVE: "The workspace was not active",
	PRACTICES_DISABLED: "Practice reviews are switched off for this workspace",
	MEMBER_AI_DECLINED: "The developer has not enabled AI practice reviews in this workspace",
	NO_ACTIVE_PRACTICE: "No practice was watching for this when it happened",
	// States the fact and stops: the instruction to act on it travels with the link in
	// REFUSAL_FIXES, which only readers who can act on it are shown.
	REVIEW_MODEL_UNBOUND: "No AI model is set up to run reviews",
	PRACTICE_AUTONOMY_OFF: "Every practice watching this is turned off; raising one lets it run",
	BUDGET_EXHAUSTED: "The workspace's AI budget was used up; it refills",
	SUBJECT_UNLINKED: "Hephaestus could not tell whose work this is",
	MODEL_UNAVAILABLE: "The AI model set for reviews is no longer available",
	ARTIFACT_NOT_VISIBLE:
		"This work is not showing on the provider right now; it will be checked again",
	PENDING_DEADLINE_EXCEEDED: "It waited too long to be picked up",
	ARTIFACT_GONE: "The work no longer exists",
	STALE_ROLLOUT_REVISION: "Review settings changed after this review started",
};

/**
 * One vocabulary answers both "why did this occurrence go nowhere" and "why was my request
 * refused", but the server generates the two unions separately. The declarations below assert they
 * stay mutually assignable: `false` does not satisfy `extends true`, so a reason added to only
 * one of them is a compile error here rather than a link silently lost on one of the two surfaces.
 */
type RequestRefusalReason = NonNullable<ReviewRequestOutcome["reason"]>;

type Agree<A, B> = [A] extends [B] ? ([B] extends [A] ? true : false) : false;

export type ReasonVocabulariesAgree<
	T extends true = Agree<RequestRefusalReason, SignalStateReason>,
> = T;

export type RefusalReason = SignalStateReason;

/** Names the destination on its own: a link is read out of its sentence (WCAG 2.4.4). */
interface RefusalFixLabel {
	label: string;
}

/**
 * The two shapes a destination has: a plain admin route taking only `workspaceSlug`, or a section
 * of the Review page, which is one route plus a search param. A union rather than an optional
 * `search`, so a fix cannot name a section of a page that has none.
 */
export type RefusalFix = RefusalFixLabel &
	(
		| {
				to:
					| "/w/$workspaceSlug/admin/models"
					| "/w/$workspaceSlug/admin/practices"
					| "/w/$workspaceSlug/admin/settings"
					| "/w/$workspaceSlug/admin/usage";
				section?: never;
		  }
		| { to?: never; section: ReviewSectionId }
	);

/**
 * Where each refusal is undone, for the readers who can undo it.
 *
 * <p>Partial on purpose: a reason earns an entry only when a workspace admin can go somewhere and
 * change the answer. The rest are self-healing, already running, terminal, or fixable only by the
 * author on their own account. Offering a settings page that cannot affect what the reader just
 * read is worse than offering nothing, because they will change something to make it stop.
 */
export const REFUSAL_FIXES: Partial<Record<SignalStateReason, RefusalFix>> = {
	GATE_SKIPPED: { section: "when-and-where", label: "Open Review: When and where" },
	OUT_OF_REVIEW_SCOPE: { section: "when-and-where", label: "Open Review: When and where" },
	PRACTICES_DISABLED: { section: "when-and-where", label: "Open Review: When and where" },
	PRACTICE_AUTONOMY_OFF: { section: "how-much", label: "Open Review: How much" },
	NO_ACTIVE_PRACTICE: { to: "/w/$workspaceSlug/admin/practices", label: "Open Practice setup" },
	REVIEW_MODEL_UNBOUND: { to: "/w/$workspaceSlug/admin/models", label: "Set up a review model" },
	MODEL_UNAVAILABLE: { to: "/w/$workspaceSlug/admin/models", label: "Open AI models" },
	BUDGET_EXHAUSTED: { to: "/w/$workspaceSlug/admin/usage", label: "Open AI usage" },
};

export const DISCOVERED_VIA_LABELS: Record<DiscoveredVia, string> = {
	EVENT: "Live event",
	SYNC: "Noticed during a sync",
	MANUAL: "Requested by hand",
	BACKFILL: "Backfill of past work",
	SWEEP: "Found by a recurring check",
};

export const DISCOVERED_VIA_DESCRIPTIONS: Record<DiscoveredVia, string> = {
	EVENT: "Reported by the provider as it happened, so the time is exact.",
	SYNC: "Spotted by a scheduled sync, so the time is only as precise as the sync.",
	MANUAL: "Someone asked for this review explicitly.",
	BACKFILL: "Recorded while catching up on work that predates the connection.",
	SWEEP:
		"Found by the recurring check over recent work, not announced by the provider — so the time is only as precise as the check.",
};

/** One vocabulary with the delivery surface: two sentences for one enum value is a drift. */
export const WITHHELD_REASON_LABELS: Record<WithheldReason, string> = WITHHOLDING_REASON_DEFS;

export function occurrenceDomId(signalId: string): string {
	return `occurrence-${signalId}`;
}

/** "Moment", not "signal": the authoring screens call the thing a practice waits for a moment. */
export function signalCountsLabel(signalCount: number, reviewedSignalCount: number): string {
	const moments = signalCount === 1 ? "1 moment recorded" : `${signalCount} moments recorded`;
	return `${moments} · ${reviewedSignalCount} started a review`;
}

/**
 * Never collapsed into the outcome badge: "Reviewed" with nothing delivered is a configured state,
 * and the reader has to see both halves to know which one to go and change.
 */
export function deliveryLabel(entry: PracticeTraceEntry): string {
	if (entry.deliveredCount > 0) {
		return entry.deliveredCount === 1
			? "1 piece of feedback reached the developer"
			: `${entry.deliveredCount} pieces of feedback reached the developer`;
	}
	if (entry.observationCount === 0) {
		return "Nothing was measured, so nothing was sent";
	}
	const measured =
		entry.observationCount === 1 ? "1 measurement" : `${entry.observationCount} measurements`;
	return entry.withheldReasons.length > 0
		? `${measured}, none sent`
		: `${measured}, nothing needed saying`;
}
