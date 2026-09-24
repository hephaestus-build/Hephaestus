import { CircleCheckIcon, WrenchIcon } from "lucide-react";

import type { ReviewObservation } from "@/api/types.gen";

import type { StatusDefs } from "@/components/common/status-def";

export type Assessment = NonNullable<ReviewObservation["assessment"]>;

/** Contextual desirability of the specified behavior, not the outcome of reviewing the work. */
export const ASSESSMENT_DEFS: StatusDefs<Assessment> = {
	GOOD: {
		label: "Good behaviour",
		icon: CircleCheckIcon,
		badgeVariant: "secondary",
		description:
			"The specified behavior is desirable in this context. Its presence is positive; its absence is negative.",
	},
	BAD: {
		label: "Bad behaviour",
		icon: WrenchIcon,
		badgeVariant: "secondary",
		description:
			"The specified behavior is undesirable in this context. Its presence is negative; its absence is positive.",
	},
};
