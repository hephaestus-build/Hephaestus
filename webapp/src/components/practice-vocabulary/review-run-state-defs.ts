import { CircleAlertIcon, CircleCheckIcon, LoaderIcon } from "lucide-react";

import type { ProfileReviewRun } from "@/api/types.gen";
import type { StatusDefs } from "@/components/common/status-def";

/**
 * How a review run ended, as the developer's own surfaces read it. Deliberately coarser than the
 * operator console's `REVIEW_STATUS_DEFS`: a run that timed out and one somebody cancelled are both
 * "it did not finish" to the person whose work it was, and the wire makes the same three-way
 * distinction rather than putting six operating states on the developer's page.
 */
export type ReviewRunState = NonNullable<ProfileReviewRun["status"]>;

export const REVIEW_RUN_STATE_DEFS: StatusDefs<ReviewRunState> = {
	IN_PROGRESS: {
		label: "Running",
		icon: LoaderIcon,
		badgeVariant: "secondary",
		description: "The review is being run now; what it finds appears as it finishes.",
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
		description: "The review stopped before it finished; what it recorded up to then still stands.",
	},
};

/**
 * The loader turns while the run does. A still spinner beside the word "Running" reads as a run
 * that has stopped, which is the one thing it must not say. Silenced for a reader who asked for
 * less motion, the way every other spinner in this app is (`components/ui/spinner.tsx`).
 */
export function runStateSpinClass(state: ReviewRunState | undefined): string {
	return state === "IN_PROGRESS" ? "animate-spin motion-reduce:animate-none" : "";
}
