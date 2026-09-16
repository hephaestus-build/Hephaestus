import { ArchiveIcon, BrushCleaningIcon, UserRoundXIcon, VolumeOffIcon } from "lucide-react";
import type { ReviewFeedback } from "@/api/types.gen";
import type { StatusDefs } from "@/components/common/status-def";

export type WithholdingReason = NonNullable<ReviewFeedback["suppressionReason"]>;
export type WithholdingFamily = "WORK_MOVED_ON" | "POLICY" | "DEVELOPER_CHOICE" | "HOUSEKEEPING";

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
		label: "Policy kept it quiet",
		icon: VolumeOffIcon,
		badgeVariant: "outline",
		description:
			"A setting somebody chose — a volume limit, a practice's autonomy, or silent mode.",
	},
	DEVELOPER_CHOICE: {
		label: "The developer's choice",
		icon: UserRoundXIcon,
		badgeVariant: "outline",
		description: "The developer opted out, or already told us this kind of feedback was wrong.",
	},
	HOUSEKEEPING: {
		label: "Housekeeping",
		icon: BrushCleaningIcon,
		badgeVariant: "outline",
		description: "The pipeline dropped it: a duplicate, an empty body, or a chat that never came.",
	},
};

const REASON_FAMILY: Record<WithholdingReason, WithholdingFamily> = {
	ARTIFACT_GONE: "WORK_MOVED_ON",
	ARTIFACT_CLOSED: "WORK_MOVED_ON",
	ARTIFACT_MERGED: "WORK_MOVED_ON",
	ARTIFACT_DRAFT: "WORK_MOVED_ON",
	VOLUME_CAPPED: "POLICY",
	PRACTICE_REQUIRES_APPROVAL: "POLICY",
	BACKFILL_QUIET: "POLICY",
	INSTANCE_SILENCED: "POLICY",
	WORKSPACE_DISABLED: "POLICY",
	WORKSPACE_DELIVERY_PAUSED: "POLICY",
	STALE_ROLLOUT_REVISION: "POLICY",
	OUTSIDE_CURRENT_COVERAGE: "POLICY",
	APPROVAL_STALE: "POLICY",
	APPROVAL_NO_LONGER_ELIGIBLE: "POLICY",
	RECIPIENT_OPTED_OUT: "DEVELOPER_CHOICE",
	REACTED_DISPUTED: "DEVELOPER_CHOICE",
	REACTED_NOT_APPLICABLE: "DEVELOPER_CHOICE",
	COMPOSER_DEDUPED: "HOUSEKEEPING",
	EMPTY_AFTER_SANITIZE: "HOUSEKEEPING",
	CONVERSATION_EXPIRED: "HOUSEKEEPING",
};

/**
 * The precise sentence for one reason, shown on the row that has it. Third person throughout:
 * anyone with access can open these screens and the work is usually somebody else's.
 */
export const WITHHOLDING_REASON_DEFS: Record<WithholdingReason, string> = {
	ARTIFACT_GONE: "The work no longer exists.",
	ARTIFACT_CLOSED: "The work was closed before the feedback could be posted.",
	ARTIFACT_MERGED: "The work was already merged, so a note on it would arrive too late.",
	ARTIFACT_DRAFT: "The work was still a draft.",
	VOLUME_CAPPED: "Over the limit on how much feedback one person gets from a single review.",
	PRACTICE_REQUIRES_APPROVAL: "This feedback is waiting for a person to approve it.",
	BACKFILL_QUIET: "Found while reviewing past work, which is measured but never sent.",
	INSTANCE_SILENCED: "Silent mode was switched on for the whole instance.",
	WORKSPACE_DISABLED: "Practice feedback is not enabled for this workspace.",
	WORKSPACE_DELIVERY_PAUSED: "External practice feedback is paused for this workspace.",
	STALE_ROLLOUT_REVISION:
		"Review settings changed after this review started, so its feedback was not sent.",
	OUTSIDE_CURRENT_COVERAGE:
		"The author, repository, or base branch is no longer covered by practice review.",
	APPROVAL_STALE:
		"The approved proposal no longer matches the content or destination being released.",
	APPROVAL_NO_LONGER_ELIGIBLE:
		"The practices behind this proposal no longer require human approval.",
	RECIPIENT_OPTED_OUT: "The developer has opted out of AI feedback.",
	REACTED_DISPUTED: "The developer disputed feedback like this before.",
	REACTED_NOT_APPLICABLE: "The developer marked feedback like this not applicable before.",
	COMPOSER_DEDUPED: "Nearly the same as other feedback from the same review.",
	EMPTY_AFTER_SANITIZE: "Nothing was left to send once the text had been cleaned up.",
	CONVERSATION_EXPIRED: "It waited for a conversation that never happened, then aged out.",
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

const WITHHOLDING_FAMILY_REASONS = Object.keys(REASON_FAMILY)
	.filter(isWithholdingReason)
	.reduce<Record<WithholdingFamily, WithholdingReason[]>>(
		(families, reason) => {
			families[withholdingFamily(reason)].push(reason);
			return families;
		},
		{
			WORK_MOVED_ON: [],
			POLICY: [],
			DEVELOPER_CHOICE: [],
			HOUSEKEEPING: [],
		},
	);

export function reasonsInFamilies(families: readonly WithholdingFamily[]): WithholdingReason[] {
	return families.flatMap((family) => WITHHOLDING_FAMILY_REASONS[family]);
}
