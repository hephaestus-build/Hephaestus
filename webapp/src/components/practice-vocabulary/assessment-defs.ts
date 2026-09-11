import { CircleCheckIcon, WrenchIcon } from "lucide-react";

import type { ReviewObservation } from "@/api/types.gen";

import type { StatusDefs } from "./status-def";

export type Assessment = NonNullable<ReviewObservation["assessment"]>;

/** Desirability of the fixed target, not the outcome of reviewing the work. */
export const ASSESSMENT_DEFS: StatusDefs<Assessment> = {
	GOOD: {
		label: "Good behaviour",
		icon: CircleCheckIcon,
		badgeVariant: "secondary",
		description:
			"The defined target behaviour is desirable. Its presence is positive; its absence is negative.",
	},
	BAD: {
		label: "Bad behaviour",
		icon: WrenchIcon,
		badgeVariant: "secondary",
		description:
			"The defined target behaviour is undesirable. Its presence is negative; its absence is positive.",
	},
};
