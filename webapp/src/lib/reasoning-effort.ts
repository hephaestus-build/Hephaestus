import type { LlmModel } from "@/api/types.gen";

/** The server's effort choices; each provider and model supports its own subset. */
export type ReasoningEffort = NonNullable<LlmModel["reasoningEffort"]>;

/** No effort set: none is sent, and the provider's own default applies. */
export const PROVIDER_DEFAULT_EFFORT = "PROVIDER_DEFAULT";

export type ReasoningEffortChoice = ReasoningEffort | typeof PROVIDER_DEFAULT_EFFORT;

export const REASONING_EFFORT_CHOICES: readonly { value: ReasoningEffortChoice; label: string }[] =
	[
		{ value: PROVIDER_DEFAULT_EFFORT, label: "Provider default" },
		{ value: "NONE", label: "None" },
		{ value: "MINIMAL", label: "Minimal" },
		{ value: "LOW", label: "Low" },
		{ value: "MEDIUM", label: "Medium" },
		{ value: "HIGH", label: "High" },
		{ value: "XHIGH", label: "Extra high" },
		{ value: "MAX", label: "Max" },
	];

export function isReasoningEffortChoice(value: unknown): value is ReasoningEffortChoice {
	return REASONING_EFFORT_CHOICES.some((choice) => choice.value === value);
}

/** The choice as the create request carries it: absent for the provider default. */
export function reasoningEffortOf(choice: ReasoningEffortChoice): ReasoningEffort | undefined {
	return choice === PROVIDER_DEFAULT_EFFORT ? undefined : choice;
}

/** The choice as an update carries it: the provider default clears whatever was set. */
export function reasoningEffortUpdateOf(
	choice: ReasoningEffortChoice,
): { reasoningEffort: ReasoningEffort } | { clearReasoningEffort: true } {
	return choice === PROVIDER_DEFAULT_EFFORT
		? { clearReasoningEffort: true }
		: { reasoningEffort: choice };
}
