import { MessageSquareIcon, MessageSquareWarningIcon } from "lucide-react";

import type { FeedbackAnswer } from "@/api/types.gen";
import type { StatusDefs } from "@/components/common/status-def";

// Admins read the answer and a disagreement's explanation; the server never sends them the rating or other notes.
export type DeveloperAnswer = "ANSWERED" | "DISAGREED";

export const DEVELOPER_ANSWER_DEFS: StatusDefs<DeveloperAnswer> = {
	ANSWERED: {
		label: "Answered",
		icon: MessageSquareIcon,
		badgeVariant: "outline",
		description: "The developer responded to this feedback.",
	},
	DISAGREED: {
		label: "Disagreed",
		icon: MessageSquareWarningIcon,
		badgeVariant: "warning",
		description:
			"The developer disagrees with the feedback and explained why. Read their reason on the feedback.",
	},
};

export function developerAnswer(answer: FeedbackAnswer | null): DeveloperAnswer | undefined {
	if (answer === null) {
		return undefined;
	}
	return answer.resolution === "DISAGREE" ? "DISAGREED" : "ANSWERED";
}
