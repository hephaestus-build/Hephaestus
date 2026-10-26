import {
	ArchiveIcon,
	BrushCleaningIcon,
	CircleSlashIcon,
	UserRoundXIcon,
	VolumeOffIcon,
} from "lucide-react";
import type { ReviewFeedback } from "@/api/types.gen";
import type { StatusDefs } from "@/components/common/status-def";

export type WithholdingReason = NonNullable<ReviewFeedback["stopReason"]>;
export type WithholdingFamily =
	| "WORK_MOVED_ON"
	| "POLICY"
	| "DEVELOPER_CHOICE"
	| "ADMIN_CORRECTION"
	| "HOUSEKEEPING";

/**
 * The filter grain over the withholding reasons. Each family answers "who decided", which is the cut
 * an operator acts on differently — so that, and not the shape of the pipeline, is what a new reason
 * is filed under.
 */
export const WITHHOLDING_FAMILY_DEFS: StatusDefs<WithholdingFamily> = {
	WORK_MOVED_ON: {
		label: "The work moved on",
		icon: ArchiveIcon,
		badgeVariant: "outline",
		description: "The pull request, issue or document had moved past the point of being told.",
	},
	POLICY: {
		label: "Policy or settings",
		icon: VolumeOffIcon,
		badgeVariant: "outline",
		description:
			"A delivery policy or setting, such as public feedback, the current review scope, approval, feedback limits, feedback mode or silent mode.",
	},
	DEVELOPER_CHOICE: {
		label: "The developer’s choice",
		icon: UserRoundXIcon,
		badgeVariant: "outline",
		description:
			"The developer opted out, or already disagreed with this kind of feedback or marked it not applicable.",
	},
	ADMIN_CORRECTION: {
		label: "An admin corrected it",
		icon: CircleSlashIcon,
		badgeVariant: "outline",
		description: "A workspace admin marked an observation behind it as incorrect.",
	},
	HOUSEKEEPING: {
		label: "Housekeeping",
		icon: BrushCleaningIcon,
		badgeVariant: "outline",
		description:
			"The pipeline dropped it: a duplicate, an empty body, a chat that never came, or feedback it could not write.",
	},
};

const REASON_FAMILY: Record<WithholdingReason, WithholdingFamily> = {
	EVIDENCE_NOT_PERMITTED: "POLICY",
	NO_EVIDENCE: "HOUSEKEEPING",
	UNCORROBORATED: "HOUSEKEEPING",
	ITEM_GONE: "WORK_MOVED_ON",
	ITEM_CLOSED: "WORK_MOVED_ON",
	ITEM_MERGED: "WORK_MOVED_ON",
	ITEM_STATE_INELIGIBLE: "WORK_MOVED_ON",
	ISSUE_SNAPSHOT_CHANGED: "WORK_MOVED_ON",
	REVIEWED_VERSION_CHANGED: "WORK_MOVED_ON",
	REVIEW_CANCELLED: "POLICY",
	VOLUME_CAPPED: "POLICY",
	PRACTICE_REQUIRES_APPROVAL: "POLICY",
	BACKFILL_QUIET: "POLICY",
	INSTANCE_SILENCED: "POLICY",
	FEEDBACK_OFF: "POLICY",
	WORKSPACE_DISABLED: "POLICY",
	WORKSPACE_DELIVERY_PAUSED: "POLICY",
	STALE_ROLLOUT_REVISION: "POLICY",
	OUTSIDE_CURRENT_SCOPE: "POLICY",
	PUBLIC_SUBJECT_INELIGIBLE: "POLICY",
	APPROVAL_STALE: "POLICY",
	APPROVAL_NO_LONGER_ELIGIBLE: "POLICY",
	NO_AI: "DEVELOPER_CHOICE",
	DELIVERY_OFF: "DEVELOPER_CHOICE",
	PERSON_SUPPRESSED: "DEVELOPER_CHOICE",
	RECIPIENT_UNAVAILABLE: "DEVELOPER_CHOICE",
	REACTED_DISAGREE: "DEVELOPER_CHOICE",
	REACTED_NOT_APPLICABLE: "DEVELOPER_CHOICE",
	RESULT_MARKED_INCORRECT: "ADMIN_CORRECTION",
	COMPOSER_WITHHELD: "HOUSEKEEPING",
	NOTHING_NEW: "HOUSEKEEPING",
	REPEATS_DELIVERED_NOTE: "HOUSEKEEPING",
	PUBLIC_HISTORY_CHANGED: "HOUSEKEEPING",
	PUBLIC_DELIVERY_UNRESOLVED: "HOUSEKEEPING",
	EMPTY_AFTER_SANITIZE: "HOUSEKEEPING",
	CONVERSATION_EXPIRED: "HOUSEKEEPING",
	DELIVERY_UNCONFIRMED: "HOUSEKEEPING",
	NOT_COMPOSED: "HOUSEKEEPING",
};

/**
 * The precise sentence for one reason, shown on the row that has it. Third person throughout:
 * anyone with access can open these screens and the work is usually somebody else's.
 */
export const WITHHOLDING_REASON_DEFS: Record<WithholdingReason, string> = {
	EVIDENCE_NOT_PERMITTED: "The evidence cannot be used for this feedback.",
	NO_EVIDENCE: "The feedback has no evidence that supports it.",
	UNCORROBORATED: "The feedback could not be confirmed against its evidence.",
	ITEM_GONE: "The work no longer exists.",
	ITEM_CLOSED: "The work was closed before the feedback could be posted.",
	ITEM_MERGED: "The work was already merged, so a note on it would arrive too late.",
	ITEM_STATE_INELIGIBLE: "The work was in a state that takes no feedback, such as a draft.",
	ISSUE_SNAPSHOT_CHANGED: "The issue changed before this feedback could be posted.",
	REVIEWED_VERSION_CHANGED: "The work changed before this feedback could be posted.",
	REVIEW_CANCELLED: "The review was cancelled before another write could start.",
	VOLUME_CAPPED: "Over the limit on how much feedback one person gets from a single review.",
	PRACTICE_REQUIRES_APPROVAL: "This feedback is waiting for a workspace admin to approve it.",
	BACKFILL_QUIET: "Found while reviewing past work, which is measured but never sent.",
	INSTANCE_SILENCED: "Silent mode was on for the whole instance.",
	FEEDBACK_OFF: "The practice’s feedback mode is Off.",
	WORKSPACE_DISABLED: "Practice feedback is not enabled for this workspace.",
	WORKSPACE_DELIVERY_PAUSED: "Feedback on the work is paused for this workspace.",
	STALE_ROLLOUT_REVISION:
		"Review settings changed after this review started, so its feedback was not sent.",
	OUTSIDE_CURRENT_SCOPE:
		"The developer, repository, or base branch is no longer in the review scope.",
	PUBLIC_SUBJECT_INELIGIBLE: "Public feedback must address the author’s work.",
	APPROVAL_STALE: "The approved copies no longer match the content or destination being sent.",
	APPROVAL_NO_LONGER_ELIGIBLE: "The practices behind this feedback no longer require approval.",
	NO_AI: "The developer has not agreed to AI feedback.",
	DELIVERY_OFF: "The developer turned this kind of delivery off.",
	PERSON_SUPPRESSED: "Feedback to this person is turned off in this workspace.",
	RECIPIENT_UNAVAILABLE: "The developer could not receive feedback.",
	REACTED_DISAGREE: "The developer disagreed with feedback like this before.",
	REACTED_NOT_APPLICABLE: "The developer marked feedback like this not applicable before.",
	RESULT_MARKED_INCORRECT:
		"A workspace admin marked an observation behind it as incorrect, so nothing more of it was sent.",
	COMPOSER_WITHHELD: "The review decided, with a reason, not to put this on the work.",
	NOTHING_NEW: "It said nothing the developer was not already told.",
	REPEATS_DELIVERED_NOTE:
		"It read word for word like the note already posted on the same work for the same person.",
	PUBLIC_HISTORY_CHANGED:
		"It could not be shown to account for the other feedback on the same work for the same person.",
	PUBLIC_DELIVERY_UNRESOLVED:
		"Other feedback for the same person on this work may already have appeared, and that is still unknown.",
	EMPTY_AFTER_SANITIZE: "Nothing was left to send once the text had been cleaned up.",
	CONVERSATION_EXPIRED: "It waited for a conversation that never happened, then aged out.",
	DELIVERY_UNCONFIRMED: "It may have appeared, but that was never confirmed.",
	NOT_COMPOSED:
		"The feedback could not be written, for example because time or budget ran out, so nothing was sent.",
};

export function withholdingFamily(reason: WithholdingReason): WithholdingFamily {
	return REASON_FAMILY[reason];
}

export function withholdingReasonSentence(reason: WithholdingReason): string {
	return WITHHOLDING_REASON_DEFS[reason];
}

function isWithholdingReason(value: string): value is WithholdingReason {
	return Object.hasOwn(REASON_FAMILY, value);
}

const WITHHOLDING_FAMILY_REASONS: Record<WithholdingFamily, WithholdingReason[]> = {
	WORK_MOVED_ON: [],
	POLICY: [],
	DEVELOPER_CHOICE: [],
	ADMIN_CORRECTION: [],
	HOUSEKEEPING: [],
};
for (const reason of Object.keys(REASON_FAMILY).filter(isWithholdingReason)) {
	WITHHOLDING_FAMILY_REASONS[withholdingFamily(reason)].push(reason);
}

export function reasonsInFamilies(families: readonly WithholdingFamily[]): WithholdingReason[] {
	return families.flatMap((family) => WITHHOLDING_FAMILY_REASONS[family]);
}
