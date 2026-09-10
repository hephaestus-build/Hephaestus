import type { WorkspaceOnboarding } from "@/api/types.gen";

export function mentorPreferenceReason(preference: WorkspaceOnboarding) {
	if (preference.aiChoice === "NO_AI") return "no-ai";
	if (preference.aiChoice == null)
		return preference.aiChoiceRequired ? "choice-required" : undefined;
	if (
		!preference.aiOptions.some(
			(option) => option.choice === preference.aiChoice && option.mentorReady,
		)
	)
		return "unavailable";
	return undefined;
}
