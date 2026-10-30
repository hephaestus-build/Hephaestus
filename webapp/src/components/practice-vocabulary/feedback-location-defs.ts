import type { ReviewFeedback, ItemRef } from "@/api/types.gen";
import { HephaestusMarkIcon } from "@/components/brand/HephaestusMark";
import type { StatusDefs } from "@/components/common/status-def";
import { GitHubIcon, GitLabIcon, SlackIcon } from "@/components/icons/brand";
import { itemName } from "@/lib/item-kinds";

import { FEEDBACK_DESTINATION_DEFS } from "./feedback-destination-defs";

export type FeedbackLocation = ReviewFeedback["location"];

export const FEEDBACK_LOCATION_DEFS: StatusDefs<FeedbackLocation> = {
	GITHUB: {
		label: "On GitHub",
		icon: GitHubIcon,
		badgeVariant: "outline",
		description: "Posted as a comment on the pull request or issue at GitHub.",
	},
	GITLAB: {
		label: "On GitLab",
		icon: GitLabIcon,
		badgeVariant: "outline",
		description: "Posted as a comment on the merge request or issue at GitLab.",
	},
	OTHER_WORK: {
		// The destination's own word and mark, so the Destination filter and the destination tag agree.
		label: FEEDBACK_DESTINATION_DEFS.ON_WORK.label,
		icon: FEEDBACK_DESTINATION_DEFS.ON_WORK.icon,
		badgeVariant: "outline",
		description:
			"Posted on the work the feedback is about, at a provider the record does not name as GitHub or GitLab.",
	},
	HEPH: {
		label: "In conversation in the app",
		icon: HephaestusMarkIcon,
		badgeVariant: "outline",
		description: "Raised in a turn of the developer’s conversation in the app.",
	},
	SLACK: {
		label: "In conversation in Slack",
		icon: SlackIcon,
		badgeVariant: "outline",
		description: "Raised in a turn of the developer’s conversation in a Slack direct message.",
	},
	NEXT_CONVERSATION: {
		// The destination's own word and mark, so the Destination filter and the destination tag agree.
		label: FEEDBACK_DESTINATION_DEFS.CONVERSATION.label,
		icon: FEEDBACK_DESTINATION_DEFS.CONVERSATION.icon,
		badgeVariant: "outline",
		description:
			"For the developer’s conversation with Hephaestus. No turn raised it yet, or the turn that raised it is gone.",
	},
	PRACTICE_PAGES: {
		// The destination's own word and mark, so the Destination filter and the destination tag agree.
		label: FEEDBACK_DESTINATION_DEFS.PRACTICE_PAGE.label,
		icon: FEEDBACK_DESTINATION_DEFS.PRACTICE_PAGE.icon,
		badgeVariant: "outline",
		description: "A card on the developer’s own practice pages, which only they can read.",
	},
};

/**
 * The location in words, where a reader asks where one piece of feedback went: "Comment on GitHub
 * pull request #12", "Conversation in Slack", "Card on their practice pages". The work's
 * own noun comes from the provider, so a GitLab one reads "merge request !425".
 */
export function feedbackLocationSentence(
	location: FeedbackLocation,
	item: Pick<ItemRef, "kind" | "provider" | "label"> | null,
): string {
	const work = item === null ? undefined : itemName(item);
	switch (location) {
		case "GITHUB":
		case "GITLAB": {
			const provider = location === "GITHUB" ? "GitHub" : "GitLab";
			return work === undefined
				? `Comment on ${provider}`
				: `Comment on ${provider} ${lowerFirst(work)}`;
		}
		case "OTHER_WORK": {
			return work === undefined ? "Comment on the work" : `Comment on ${lowerFirst(work)}`;
		}
		case "HEPH": {
			return "Conversation in the app";
		}
		case "SLACK": {
			return "Conversation in Slack";
		}
		case "NEXT_CONVERSATION": {
			return "For a conversation";
		}
		case "PRACTICE_PAGES": {
			return "Card on their practice pages";
		}
	}
}

/** "Pull request #12" reads "pull request #12" mid-sentence; a label after the noun keeps its case. */
function lowerFirst(value: string): string {
	return value.charAt(0).toLowerCase() + value.slice(1);
}
