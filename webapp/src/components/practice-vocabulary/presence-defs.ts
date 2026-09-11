import { CircleSlashIcon, EyeIcon } from "lucide-react";

import type { ReviewObservation } from "@/api/types.gen";

import type { StatusDefs } from "./status-def";

export type Presence = NonNullable<ReviewObservation["presence"]>;

/**
 * Whether the target behavior was found in the work — independently of whether that is good or bad,
 * which is `assessment-defs`. No value here is good or bad on its own, so none of them is coloured:
 * absence can be a missing good behavior or the avoidance of a harmful behavior.
 *
 */
export const PRESENCE_DEFS: StatusDefs<Presence> = {
	PRESENT: {
		label: "Present",
		icon: EyeIcon,
		badgeVariant: "secondary",
		description:
			"The target behavior is present. Combine presence with the target’s GOOD/BAD assessment to derive the outcome.",
	},
	ABSENT: {
		label: "Absent",
		icon: CircleSlashIcon,
		badgeVariant: "outline",
		description:
			"The applicable target criterion was not found in the searched evidence. Absence is negative for GOOD targets and positive for BAD targets.",
	},
};
