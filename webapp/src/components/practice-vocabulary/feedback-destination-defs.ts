import { BotMessageSquareIcon, MessageSquareQuoteIcon, UserRoundIcon } from "lucide-react";

import type { ReviewFeedback } from "@/api/types.gen";

import type { StatusDefs } from "@/components/common/status-def";

export type FeedbackDestination = ReviewFeedback["destination"];

export const FEEDBACK_DESTINATION_DEFS: StatusDefs<FeedbackDestination> = {
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
			"Held back until the developer’s next chat with Hephaestus, then raised there. What is stored is the move to make, not the words, so it is withheld from every admin page, including this one.",
	},
	PRACTICE_PAGE: {
		label: "On the practice page",
		icon: UserRoundIcon,
		badgeVariant: "outline",
		description:
			"Shown only on the developer’s own practice pages, which is also what delivers it. The text is withheld from every admin page, including this one.",
	},
};

// Private destination text is withheld; its absence must not be presented as missing evidence.
export const ADMIN_WITHHELD_TEXT: Partial<
	Record<FeedbackDestination, { title: string; detail: string }>
> = {
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
};
