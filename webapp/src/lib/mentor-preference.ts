import type { WorkspaceOnboarding } from "@/api/types.gen";

import { memberAiChoiceTitle } from "./llm-processing-location";

/** Why Heph will not answer; `unavailable` carries the saved location's title its sentence names. */
export type MentorNotice =
	| { reason: "no-ai" }
	| { reason: "choice-required" }
	| { reason: "unavailable"; location: string };

/**
 * Why Heph will not answer this member, if it will not. The server twin is
 * `MemberAiPreferences.Decision.permitsAi` plus `MemberAiRoutingAdapter.ready(MENTOR)`.
 * `aiChoice == null && !aiChoiceRequired` is `undefined` on purpose: such members use the
 * workspace-default binding.
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
		return { reason: "unavailable", location: memberAiChoiceTitle(preference.aiChoice) };
	return undefined;
}

/**
 * The notice per reason. `unavailable` is true only about Heph — the reason fires whenever
 * `mentorReady` is false, even when practice reviews are ready — so its sentence names no reviews.
 */
export const MENTOR_PREFERENCE_COPY = {
	"no-ai": {
		title: "Heph is off for you in this workspace",
		description:
			"You chose No AI here: no new practice reviews about you and no new conversations with Heph. Your membership, existing feedback and earlier conversations are unchanged.",
		cta: "Change your AI choice",
	},
	"choice-required": {
		title: "Choose how you want to use AI here",
		description:
			"On-premises, Private cloud or No AI. Nothing is chosen until you choose, and until you do there are no practice reviews about you and no Heph.",
		cta: "Make your AI choice",
	},
	unavailable: {
		title: "Heph isn't set up for your AI choice yet",
		description: (location: string) =>
			`No Heph model is assigned to ${location}. Nothing switches you elsewhere; ask a workspace owner, or change your choice.`,
		cta: "Change your AI choice",
	},
} satisfies Record<
	MentorNotice["reason"],
	{ title: string; description: string | ((location: string) => string); cta: string }
>;

/** The three sentences for one notice, with the `unavailable` location already filled in. */
export function mentorNoticeCopy(notice: MentorNotice): {
	title: string;
	description: string;
	cta: string;
} {
	if (notice.reason === "unavailable") {
		const copy = MENTOR_PREFERENCE_COPY.unavailable;
		return { ...copy, description: copy.description(notice.location) };
	}
	return MENTOR_PREFERENCE_COPY[notice.reason];
}
