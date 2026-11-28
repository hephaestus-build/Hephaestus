import { BadgeCheckIcon, CircleHelpIcon, FilePenLineIcon, ScaleIcon } from "lucide-react";

import type { StatusDefs } from "@/components/common/status-def";

/** What a later review shows about a recorded team decision. Only evidence for every part of the note is "Fully evidenced". */
export type RevisitFinding = "CHANGE_SHOWN" | "STILL_UNCERTAIN" | "TRADE_OFF" | "FULLY_EVIDENCED";

export const REVISIT_FINDING_DEFS = {
	CHANGE_SHOWN: {
		label: "Change shown",
		icon: FilePenLineIcon,
		badgeVariant: "outline",
		description: "The new version shows a change that the decision named.",
	},
	STILL_UNCERTAIN: {
		label: "Still uncertain",
		icon: CircleHelpIcon,
		badgeVariant: "outline",
		description: "The new version does not show whether the decision worked.",
	},
	TRADE_OFF: {
		label: "Trade-off",
		icon: ScaleIcon,
		badgeVariant: "warning",
		description: "The change the decision named costs something else in the new version.",
	},
	FULLY_EVIDENCED: {
		label: "Fully evidenced",
		icon: BadgeCheckIcon,
		badgeVariant: "success",
		description: "The new version has evidence for every part of the decision’s note.",
	},
} satisfies StatusDefs<RevisitFinding>;
