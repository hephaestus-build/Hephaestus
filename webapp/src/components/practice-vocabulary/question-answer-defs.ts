import { CheckIcon, CircleDotDashedIcon, XIcon } from "lucide-react";

import type { ObservationAnswer } from "@/api/types.gen";
import type { StatusDefs } from "@/components/common/status-def";

export type QuestionAnswer = ObservationAnswer["answer"];

/**
 * How a review answered one practice question. Neutral on purpose: a yes is good news for one question
 * and bad news for the next, so the outcome the rules derive carries the colour, never the answer.
 */
export const QUESTION_ANSWER_DEFS: StatusDefs<QuestionAnswer> = {
	YES: {
		label: "Yes",
		icon: CheckIcon,
		badgeVariant: "outline",
		description: "The cited evidence answers the question with yes.",
	},
	NO: {
		label: "No",
		icon: XIcon,
		badgeVariant: "outline",
		description: "The cited evidence answers the question with no.",
	},
	UNDETERMINED: {
		label: "Open",
		icon: CircleDotDashedIcon,
		badgeVariant: "secondary",
		description: "The captured evidence does not settle the question.",
	},
};
