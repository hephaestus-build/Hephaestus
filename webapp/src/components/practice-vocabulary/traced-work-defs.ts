import { CircleDashedIcon, CirclePlayIcon } from "lucide-react";

import type { TracedArtifact } from "@/api/types.gen";

import type { StatusDefs } from "@/components/common/status-def";

/** Whether anything recorded about a piece of work started a practice review. */
export type TracedWorkState = "REVIEW_STARTED" | "NO_REVIEW_STARTED";

export const TRACED_WORK_DEFS: StatusDefs<TracedWorkState> = {
	REVIEW_STARTED: {
		label: "Review started",
		icon: CirclePlayIcon,
		badgeVariant: "secondary",
		description: "At least one moment recorded on this work started a practice review.",
	},
	NO_REVIEW_STARTED: {
		label: "No review started",
		icon: CircleDashedIcon,
		badgeVariant: "outline",
		description:
			"Moments were recorded on this work, but none of them started a practice review. Open it to read why each one stopped.",
	},
};

/** Read off the counts the list carries: the server sends no state of its own for this. */
export function tracedWorkState(
	work: Pick<TracedArtifact, "reviewedSignalCount">,
): TracedWorkState {
	return work.reviewedSignalCount > 0 ? "REVIEW_STARTED" : "NO_REVIEW_STARTED";
}
