import { CircleSlashIcon, EyeIcon } from "lucide-react";

import type { ReviewObservation } from "@/api/types.gen";

import type { StatusDefs } from "./status-def";

export type Presence = NonNullable<ReviewObservation["presence"]>;

/**
 * Whether the specified behavior was found in the work — independently of whether that is good or bad,
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
			"The specified behavior is present. Combine presence with its contextual GOOD/BAD assessment to derive the outcome.",
	},
	ABSENT: {
		label: "Absent",
		icon: CircleSlashIcon,
		badgeVariant: "outline",
		description:
			"The specified behavior was absent from an applicable, bounded search. Absence is negative for desirable behavior and positive for undesirable behavior.",
	},
};
