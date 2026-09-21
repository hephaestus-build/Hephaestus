import {
	CircleAlertIcon,
	CircleCheckIcon,
	CircleSlashIcon,
	ClockIcon,
	LoaderIcon,
	TimerOffIcon,
} from "lucide-react";

import type { AgentJob } from "@/api/types.gen";

import type { StatusDefs } from "@/components/common/status-def";

export type ReviewStatus = AgentJob["status"];
export type ResultProcessingStatus = NonNullable<AgentJob["deliveryStatus"]>;

/**
 * Whether a review *ran*. Nothing here says anything about what it found or who heard about it —
 * those are `assessment-defs` and `delivery-outcome-defs`.
 */
export const REVIEW_STATUS_DEFS: StatusDefs<ReviewStatus> = {
	QUEUED: {
		label: "Queued",
		icon: ClockIcon,
		badgeVariant: "secondary",
		description: "Waiting to be picked up. A queued review may also be parked on a hold.",
	},
	RUNNING: {
		label: "Running",
		icon: LoaderIcon,
		badgeVariant: "secondary",
		description: "Being run now; results appear as it finishes.",
	},
	COMPLETED: {
		label: "Completed",
		icon: CircleCheckIcon,
		badgeVariant: "success",
		description: "It ran to the end. Whether it found or sent anything is a separate question.",
	},
	FAILED: {
		label: "Failed",
		icon: CircleAlertIcon,
		badgeVariant: "destructive",
		description: "It stopped on an error and produced no results.",
	},
	TIMED_OUT: {
		label: "Timed out",
		icon: TimerOffIcon,
		badgeVariant: "destructive",
		description: "It ran past the time it is allowed and was stopped.",
	},
	CANCELLED: {
		label: "Cancelled",
		icon: CircleSlashIcon,
		badgeVariant: "outline",
		description: "Somebody stopped it before it finished.",
	},
};

/**
 * Processing the review's results is independent of publishing feedback. The job's wire field is
 * `deliveryStatus`; individual feedback records establish whether anything reached a developer.
 */
export const RESULT_PROCESSING_DEFS: StatusDefs<ResultProcessingStatus> = {
	DELIVERED: {
		label: "Results processed",
		icon: CircleCheckIcon,
		badgeVariant: "success",
		description: "Result processing finished. Feedback may still await approval or be withheld.",
	},
	PENDING: {
		label: "Results awaiting processing",
		icon: ClockIcon,
		badgeVariant: "secondary",
		description: "The review results are waiting to be processed.",
	},
	FAILED: {
		label: "Result processing failed",
		icon: CircleAlertIcon,
		badgeVariant: "destructive",
		description: "Processing the review results did not succeed.",
	},
};
