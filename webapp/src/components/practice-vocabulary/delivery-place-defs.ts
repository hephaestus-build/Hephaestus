import { BotMessageSquareIcon, MessageSquareQuoteIcon, UserRoundIcon } from "lucide-react";

import type { ReviewFeedback } from "@/api/types.gen";

import type { StatusDefs } from "@/components/common/status-def";

export type DeliveryPlace = ReviewFeedback["channel"];

/**
 * **Where** a piece of feedback goes. One of the two delivery axes, and never the same cell as the
 * other one: place answers "where", `deliveryOutcome` answers "what happened".
 *
 * <p>The labels are parallel prepositional phrases so each reads as English inside a sentence —
 * "delivered on the work", "raised in conversation".
 */
export const DELIVERY_PLACE_DEFS: StatusDefs<DeliveryPlace> = {
	IN_CONTEXT: {
		label: "On the work",
		icon: MessageSquareQuoteIcon,
		badgeVariant: "outline",
		description: "Posted on the pull request, issue or document the feedback is about.",
	},
	IN_CHAT: {
		label: "In conversation",
		icon: BotMessageSquareIcon,
		badgeVariant: "outline",
		description:
			"Held back until the developer's next chat with the mentor, then raised there. What is stored is the move to make, not the words, so it is withheld from every operator surface including this one.",
	},
	IN_APP: {
		label: "On their practice pages",
		icon: UserRoundIcon,
		badgeVariant: "outline",
		description:
			"Shown only on the developer's own practice pages, which is also what delivers it. The text is withheld from every operator surface, including this one.",
	},
};

/**
 * The places worth offering as a filter — now all of them.
 *
 * <p>`IN_APP` was held out while the channel was declared and unwritten, because a filter for it
 * could only ever return an empty page and an operator would read that as "none today" rather than
 * "never any". A producer exists, so rows badged *On their practice pages* now appear in the list, and a
 * badge whose words match no option leaves a reader unable to filter for the row in front of them.
 * Filtering to it shows the same thing every other row shows minus the composed text, which the
 * query service withholds for the two private channels.
 */
export const FILTERABLE_PLACES: readonly DeliveryPlace[] = ["IN_CONTEXT", "IN_CHAT", "IN_APP"];
