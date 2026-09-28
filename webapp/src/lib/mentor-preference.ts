import type { WorkspaceOnboarding } from "@/api/types.gen";

type MemberAiChoice = NonNullable<WorkspaceOnboarding["aiChoice"]>;

/**
 * Why Heph will not answer; `unavailable` carries the saved choice so the view can name it from the
 * registry — the words for a choice live with its icon, and this layer holds no view vocabulary.
 */
export type MentorNotice =
	| { reason: "no-ai" }
	| { reason: "not-set-up" }
	| { reason: "choice-required" }
	| { reason: "unavailable"; choice: MemberAiChoice };

/**
 * Why Heph will not answer this member, if it will not. The server twin is `MentorRefusal`.
 * `aiChoice == null && !aiChoiceRequired` is `undefined` on purpose: members who haven't chosen
 * are served by the undeclared slot. `not-set-up` comes before the choice: no choice the member
 * could make would bring Heph.
 */
export function mentorPreferenceReason(preference: WorkspaceOnboarding): MentorNotice | undefined {
	if (preference.aiChoice === "NO_AI") {
		return { reason: "no-ai" };
	}
	if (!preference.aiOptions.some((option) => option.mentorReady)) {
		return { reason: "not-set-up" };
	}
	if (preference.aiChoice == null) {
		return preference.aiChoiceRequired ? { reason: "choice-required" } : undefined;
	}
	if (
		!preference.aiOptions.some(
			(option) => option.choice === preference.aiChoice && option.mentorReady,
		)
	) {
		return { reason: "unavailable", choice: preference.aiChoice };
	}
	return undefined;
}

/**
 * The notice per reason; a reason only a workspace owner can change has no `cta`. `unavailable` is
 * true only about Heph — the reason fires whenever `mentorReady` is false, even when practice
 * reviews are ready — so its sentence names no reviews. Its description is two halves around the
 * saved choice's card title, which the view emphasises so that a title with a comma in it still
 * reads as one noun.
 */
export const MENTOR_PREFERENCE_COPY = {
	"no-ai": {
		title: "Heph is off for you",
		description:
			"You chose No AI. There are no new practice reviews about you and no new conversations with Heph in any workspace. Your membership, earlier feedback and past conversations stay.",
		cta: "Change your AI choice",
	},
	"not-set-up": {
		title: "Heph isn't set up in this workspace yet",
		description:
			"No Heph model is ready for any AI choice here. A workspace owner sets one up under AI models.",
	},
	"choice-required": {
		title: "Choose which AI may handle your work",
		description:
			"Until you choose, there are no practice reviews about you and no Heph in this workspace. You answer once, for all your workspaces.",
		cta: "Make your AI choice",
	},
	unavailable: {
		title: "Heph isn't set up for your AI choice yet",
		description: {
			before: "No Heph model is within ",
			after: " yet. Nothing switches you elsewhere. Ask a workspace owner, or change your choice.",
		},
		cta: "Change your AI choice",
	},
} satisfies Record<
	MentorNotice["reason"],
	{ title: string; description: string | { before: string; after: string }; cta?: string }
>;
