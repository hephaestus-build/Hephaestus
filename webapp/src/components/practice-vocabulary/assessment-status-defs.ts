import { CircleCheckIcon, CircleHelpIcon, CircleMinusIcon } from "lucide-react";
import type { ReviewObservation } from "@/api/types.gen";
import type { StatusDefs } from "./status-def";

export type AssessmentStatus = ReviewObservation["assessmentStatus"];

export const ASSESSMENT_STATUS_DEFS: StatusDefs<AssessmentStatus> = {
	ASSESSED: {
		label: "Assessed",
		icon: CircleCheckIcon,
		badgeVariant: "secondary",
		description: "The evidence supports a good or bad assessment of the work.",
	},
	NOT_APPLICABLE: {
		label: "Not applicable",
		icon: CircleMinusIcon,
		badgeVariant: "outline",
		description: "Nothing in this work called for the practice, so there was nothing to judge.",
	},
	UNDETERMINED: {
		label: "Undetermined",
		icon: CircleHelpIcon,
		badgeVariant: "outline",
		description:
			"The evidence was captured and read, but it did not settle the question. This is not a collection failure.",
	},
};
