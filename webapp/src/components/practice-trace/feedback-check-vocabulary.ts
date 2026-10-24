import type { FeedbackCheck, FeedbackCheckFacts } from "@/api/types.gen";
import { WITHHOLDING_REASON_DEFS } from "@/components/practice-vocabulary/withholding-defs";

type Stage = FeedbackCheck["stage"];
type Reason = NonNullable<FeedbackCheck["reason"]>;
type Fact = {
	[Key in keyof FeedbackCheckFacts]: FeedbackCheckFacts[Key] extends boolean ? Key : never;
}[keyof FeedbackCheckFacts];

export const FEEDBACK_CHECK_STAGE_LABELS = {
	COMPOSITION: "Composition",
	AUTOMATIC: "Automatic approval",
	APPROVED: "Approved by an admin",
	EGRESS: "Final send",
} satisfies Record<Stage, string>;

export const FEEDBACK_CHECK_FACT_LABELS = {
	inScope: "In review scope",
	evidenceAllowed: "Evidence may be shown",
	modelAllowed: "Model may be used",
	deliveryEnabled: "Feedback turned on",
	incorrectBasis: "Rests on an observation marked incorrect",
	currentVersion: "Reviewed version is current",
	approvalCurrent: "Approval still current",
	writeStarted: "Sending started",
} satisfies Record<Fact, string>;

export const FEEDBACK_CHECK_REASON_SENTENCES = WITHHOLDING_REASON_DEFS satisfies Record<
	Reason,
	string
>;
