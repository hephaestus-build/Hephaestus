import { ThumbsDownIcon, ThumbsUpIcon } from "lucide-react";

import type { PracticeGroupReviewObservation } from "@/api/types.gen";

import type { StatusDefs } from "@/components/common/status-def";

export type FeedbackUsefulness = NonNullable<PracticeGroupReviewObservation["feedbackUsefulness"]>;
export const FEEDBACK_USEFULNESS_DEFS: StatusDefs<FeedbackUsefulness> = {
	HELPFUL: {
		label: "Helpful",
		icon: ThumbsUpIcon,
		badgeVariant: "success",
		description: "This told you something you could act on.",
	},
	UNHELPFUL: {
		label: "Not helpful",
		icon: ThumbsDownIcon,
		badgeVariant: "destructive",
		description: "This was not worth the read.",
	},
};
