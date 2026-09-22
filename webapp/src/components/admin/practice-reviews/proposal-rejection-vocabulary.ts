import type { DecideFeedbackProposalRequest } from "@/api/types.gen";

export type ProposalRejectionReason = NonNullable<DecideFeedbackProposalRequest["rejectionReason"]>;

export const PROPOSAL_REJECTION_REASONS: {
	value: ProposalRejectionReason;
	label: string;
}[] = [
	{ value: "INCORRECT", label: "Incorrect" },
	{ value: "MISSING_CONTEXT", label: "Missing important context" },
	{ value: "UNHELPFUL", label: "Not useful to the recipient" },
	{ value: "DUPLICATE", label: "Already covered elsewhere" },
	{ value: "INAPPROPRIATE_PLACEMENT", label: "Wrong delivery place" },
	{ value: "OTHER", label: "Something else" },
];

export function proposalRejectionReasonLabel(reason: ProposalRejectionReason): string {
	return PROPOSAL_REJECTION_REASONS.find((option) => option.value === reason)?.label ?? reason;
}
