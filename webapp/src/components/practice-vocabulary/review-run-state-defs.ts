import { CircleAlertIcon, CircleCheckIcon, LoaderIcon } from "lucide-react";

import type { ProfileReviewRun } from "@/api/types.gen";
import type { StatusDefs } from "@/components/common/status-def";

/**
 * How a review ended, as the developer's own surfaces read it: coarser than the operator console's
 * `REVIEW_STATUS_DEFS`, since a timeout and a cancellation both mean "it did not finish" to them.
 */
export type ReviewRunState = NonNullable<ProfileReviewRun["status"]>;

export const REVIEW_RUN_STATE_DEFS: StatusDefs<ReviewRunState> = {
	IN_PROGRESS: {
		label: "Running",
		icon: LoaderIcon,
		badgeVariant: "secondary",
		description: "The review is running. What it records appears as it finishes.",
	},
	COMPLETED: {
		label: "Completed",
		icon: CircleCheckIcon,
		badgeVariant: "success",
		description: "The review ran to the end. Whether it found anything is a separate question.",
	},
	FAILED: {
		label: "Failed",
		icon: CircleAlertIcon,
		badgeVariant: "destructive",
		description: "The review stopped before it finished. What it recorded up to then still stands.",
	},
};
