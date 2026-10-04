import type { DecideFeedbackProposalRequest } from "@/api/types.gen";

export type ProposalRejectionReason = NonNullable<DecideFeedbackProposalRequest["rejectionReason"]>;

/**
 * Keyed on the wire union, so a reason the server adds fails the build here rather than reaching a
 * reviewer as a constant name.
 */
const PROPOSAL_REJECTION_LABELS: Record<ProposalRejectionReason, string> = {
	INCORRECT: "Incorrect",
	MISSING_CONTEXT: "Missing important context",
	UNHELPFUL: "Not useful to the recipient",
	DUPLICATE: "Already covered elsewhere",
	INAPPROPRIATE_PLACEMENT: "Wrong place for this feedback",
	OTHER: "Something else",
};

/** The order a reviewer is offered them in. */
const REASON_ORDER = [
	"INCORRECT",
	"MISSING_CONTEXT",
	"UNHELPFUL",
	"DUPLICATE",
	"INAPPROPRIATE_PLACEMENT",
	"OTHER",
] as const satisfies readonly ProposalRejectionReason[];

export const PROPOSAL_REJECTION_REASONS = REASON_ORDER.map((value) => ({
	value,
	label: PROPOSAL_REJECTION_LABELS[value],
}));

export function proposalRejectionReasonLabel(reason: ProposalRejectionReason): string {
	return PROPOSAL_REJECTION_LABELS[reason];
}
