import type { LlmModel, WorkspaceOnboarding } from "@/api/types.gen";

export const LLM_PROCESSING_LOCATIONS = [
	{ value: "UNCLASSIFIED", label: "Unclassified" },
	{ value: "ON_PREMISES", label: "On-premises" },
	{ value: "PRIVATE_CLOUD", label: "Private cloud" },
] satisfies { value: LlmModel["processingLocation"]; label: string }[];

export type MemberAiChoice = NonNullable<WorkspaceOnboarding["aiChoice"]>;

/** Title + one sentence, the same shape for all three: a longer answer is a heavier answer. */
export const MEMBER_AI_CHOICE_COPY = {
	ON_PREMISES: {
		title: "On-premises",
		description: "AI runs on infrastructure your organisation manages.",
	},
	PRIVATE_CLOUD: {
		title: "Private cloud",
		description: "AI runs in the private cloud this workspace has set up.",
	},
	NO_AI: { title: "No AI", description: "No AI runs for you in this workspace." },
} satisfies Record<MemberAiChoice, { title: string; description: string }>;

/** Display order: the two locations first, then the answer that is always available. */
export const MEMBER_AI_CHOICES = (["ON_PREMISES", "PRIVATE_CLOUD", "NO_AI"] as const).map(
	(value) => ({ value, ...MEMBER_AI_CHOICE_COPY[value] }),
);

export function memberAiChoiceTitle(choice: MemberAiChoice): string {
	return MEMBER_AI_CHOICE_COPY[choice].title;
}
