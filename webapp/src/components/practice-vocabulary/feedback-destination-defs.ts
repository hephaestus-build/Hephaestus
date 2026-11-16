import { BotMessageSquareIcon, MessageSquareQuoteIcon, UserRoundIcon } from "lucide-react";

import type { ReviewFeedback } from "@/api/types.gen";

import { isKnownValue, type StatusDefs } from "@/components/common/status-def";

export type FeedbackDestination = ReviewFeedback["destination"];

export const FEEDBACK_DESTINATION_DEFS = {
	ON_WORK: {
		label: "On the work",
		icon: MessageSquareQuoteIcon,
		badgeVariant: "outline",
		description: "Posted on the pull request, issue or document the feedback is about.",
	},
	CONVERSATION: {
		label: "In conversation",
		icon: BotMessageSquareIcon,
		badgeVariant: "outline",
		description:
			"Held back until the developer’s next conversation with Hephaestus, then raised there. What is stored is the move to make, not the words, so it is withheld from every admin page, including this one.",
	},
	PRACTICE_PAGE: {
		label: "On the practice page",
		icon: UserRoundIcon,
		badgeVariant: "outline",
		description:
			"Shown only on the developer’s own practice pages, which is also what delivers it. The text is withheld from every admin page, including this one.",
	},
} satisfies StatusDefs<string>;

// Private destination text is withheld; its absence must not be presented as missing evidence.
export const ADMIN_WITHHELD_TEXT = {
	CONVERSATION: {
		title: "Notes for the conversation are private",
		detail:
			"Any notes prepared for the developer’s next conversation with Hephaestus are private to them and withheld from admins.",
	},
	PRACTICE_PAGE: {
		title: "Text is private to the developer",
		detail:
			"The text was written for the developer’s own practice pages and is withheld from admins.",
	},
} satisfies Partial<Record<string, { title: string; detail: string }>>;

/**
 * The words that stand in for a private destination's text, or undefined where an admin may read
 * it. A destination that this build does not know may be private, so its text stays withheld.
 */
export function adminWithheldText(
	destination: FeedbackDestination,
): { title: string; detail: string } | undefined {
	if (isKnownValue(ADMIN_WITHHELD_TEXT, destination)) {
		return ADMIN_WITHHELD_TEXT[destination];
	}
	return isKnownValue(FEEDBACK_DESTINATION_DEFS, destination)
		? undefined
		: {
				title: "Text is withheld",
				detail:
					"This version of Hephaestus does not recognize where this feedback goes, so it does not show the text.",
			};
}
