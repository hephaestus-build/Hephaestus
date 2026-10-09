import type { WorkspaceOnboarding } from "@/api/types.gen";

type MemberAiChoice = NonNullable<WorkspaceOnboarding["aiChoice"]>;

/**
 * Why Heph will not answer, as the server decides it (`WorkspaceOnboarding.mentorRefusal`).
 * `unavailable` carries the saved choice so the view can name it from the registry; the words for a
 * choice live with its icon, and this layer holds no view vocabulary. `not-set-up` is the same
 * refusal for a member who has made no choice.
 */
export type MentorNotice =
	| { reason: "no-ai" }
	| { reason: "choice-required" }
	| { reason: "erased" }
	| { reason: "not-set-up" }
	| { reason: "unavailable"; choice: MemberAiChoice };

/** Why Heph will not answer this member here, if it will not: the server's refusal, in the view's terms. */
export function mentorNotice(preference: WorkspaceOnboarding): MentorNotice | undefined {
	switch (preference.mentorRefusal) {
		case undefined: {
			return undefined;
		}
		case "NO_AI": {
			return { reason: "no-ai" };
		}
		case "CHOICE_REQUIRED": {
			return { reason: "choice-required" };
		}
		case "PERSON_ERASED": {
			return { reason: "erased" };
		}
		case "UNAVAILABLE": {
			const { aiChoice } = preference;
			return aiChoice === undefined
				? { reason: "not-set-up" }
				: { reason: "unavailable", choice: aiChoice };
		}
	}
}

/**
 * Nothing the member can change brings Heph here, so the navigation does not offer it: their data
 * was erased, or Heph is unavailable to them and no AI choice has a Heph model ready either.
 */
export function isMentorOutOfReach(preference: WorkspaceOnboarding): boolean {
	const { mentorRefusal } = preference;
	return (
		mentorRefusal === "PERSON_ERASED" ||
		(mentorRefusal === "UNAVAILABLE" && !preference.aiOptions.some((option) => option.mentorReady))
	);
}

/**
 * The notice per reason; a reason the member cannot change has no `cta`. `unavailable` is true
 * only about Heph — practice reviews may still be ready — so its sentence names no reviews. Its description is two halves around the
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
		title: "Heph is not set up in this workspace yet",
		description:
			"No Heph model is ready for you here. Ask a workspace owner to set one up under AI models.",
	},
	erased: {
		title: "Heph cannot reply to you",
		description:
			"Your personal data was erased, so Heph does not answer you here. Contact your instance’s privacy contact if you have questions.",
	},
	"choice-required": {
		title: "Choose which AI may handle your work",
		description:
			"Until you choose, there are no practice reviews about you and no Heph in this workspace. You answer once, for all your workspaces.",
		cta: "Make your AI choice",
	},
	unavailable: {
		title: "Heph is not set up for your AI choice yet",
		description: {
			before: "No Heph model is set up for ",
			after: " yet. Nothing switches you elsewhere. Ask a workspace owner, or change your choice.",
		},
		cta: "Change your AI choice",
	},
} satisfies Record<
	MentorNotice["reason"],
	{ title: string; description: string | { before: string; after: string }; cta?: string }
>;
