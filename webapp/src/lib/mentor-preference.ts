import type { WorkspaceOnboarding } from "@/api/types.gen";

type MemberAiChoice = NonNullable<WorkspaceOnboarding["aiChoice"]>;

/**
 * Why Heph will not answer; `unavailable` carries the saved choice so the view can name it from the
 * registry — the words for a choice live with its icon, and this layer holds no view vocabulary.
 */
export type MentorNotice =
	| { reason: "no-ai" }
	| { reason: "choice-required" }
	| { reason: "unavailable"; choice: MemberAiChoice };

/**
 * Why Heph will not answer this member, if it will not. The server twin is
 * `MemberAiPreferences.Decision.permitsAi` plus `MemberAiRoutingAdapter.ready(MENTOR)`.
 * `aiChoice == null && !aiChoiceRequired` is `undefined` on purpose: members who haven't chosen
 * are served by the undeclared slot.
 */
export function mentorPreferenceReason(preference: WorkspaceOnboarding): MentorNotice | undefined {
	if (preference.aiChoice === "NO_AI") return { reason: "no-ai" };
	if (preference.aiChoice == null)
		return preference.aiChoiceRequired ? { reason: "choice-required" } : undefined;
	if (
		!preference.aiOptions.some(
			(option) => option.choice === preference.aiChoice && option.mentorReady,
		)
	)
		return { reason: "unavailable", choice: preference.aiChoice };
	return undefined;
}

/**
 * The notice per reason. `unavailable` is true only about Heph — the reason fires whenever
 * `mentorReady` is false, even when practice reviews are ready — so its sentence names no reviews.
 * Its description is two halves around the saved choice's card title, which the view emphasises so
 * that a title with a comma in it still reads as one noun.
 */
export const MENTOR_PREFERENCE_COPY = {
	"no-ai": {
		title: "Heph is off for you in this workspace",
		description:
			"You chose No AI here: no new practice reviews about you and no new conversations with Heph. Your membership, existing feedback and earlier conversations are unchanged.",
		cta: "Change your AI choice",
	},
	"choice-required": {
		title: "Choose which AI may handle your work",
		description:
			"Until you choose, there are no practice reviews about you and no Heph in this workspace.",
		cta: "Make your AI choice",
	},
	unavailable: {
		title: "Heph isn't set up for your AI choice yet",
		description: {
			before: "No Heph model is within ",
			after: " yet. Nothing switches you elsewhere — ask a workspace owner, or change your choice.",
		},
		cta: "Change your AI choice",
	},
} satisfies Record<
	MentorNotice["reason"],
	{ title: string; description: string | { before: string; after: string }; cta: string }
>;
