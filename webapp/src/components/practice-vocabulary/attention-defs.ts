import { CircleAlertIcon, WrenchIcon } from "lucide-react";

import type { StatusDefs } from "@/components/common/status-def";

/**
 * Why a practice stands in "What needs your attention": the work the developer had already put in
 * went back to nothing, or a review wrote about the practice for the first time in this run.
 */
type AttentionKind = "reset" | "new";

/** Status lives in the glyph, never in the words beside it. */
export const ATTENTION_DEFS: StatusDefs<AttentionKind> = {
	reset: {
		label: "Back to no clean work",
		icon: CircleAlertIcon,
		badgeVariant: "destructive",
		description:
			"Your work had started to answer this feedback, and a review has raised the practice again.",
	},
	new: {
		label: "New feedback",
		icon: WrenchIcon,
		badgeVariant: "warning",
		description: "A review wrote feedback about this practice on work it looked at.",
	},
};
