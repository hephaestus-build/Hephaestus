import {
	BellOffIcon,
	CircleAlertIcon,
	CircleCheckIcon,
	CircleDashedIcon,
	CircleHelpIcon,
	CircleSlashIcon,
	ClockIcon,
	HourglassIcon,
	LoaderIcon,
	TimerOffIcon,
	UnplugIcon,
} from "lucide-react";

import type { PracticeTraceEntry } from "@/api/types.gen";

import type { StatusDefs } from "@/components/common/status-def";

export type TraceOutcome = PracticeTraceEntry["outcome"];

/**
 * What became of one practice on one piece of work — the *measurement* axis, and nothing more.
 * Whether anybody heard about it is the separate question the delivery registries answer, so
 * "Reviewed" having delivered nothing is the HUMAN_APPROVAL setting working as configured, not a failure.
 */
export const TRACE_OUTCOME_DEFS: StatusDefs<TraceOutcome> = {
	REVIEWED: {
		label: "Reviewed",
		icon: CircleCheckIcon,
		badgeVariant: "success",
		description:
			"The practice was measured on this work. Whether any feedback reached anybody is the delivery question, not this one.",
	},
	RUNNING: {
		label: "Running",
		icon: LoaderIcon,
		badgeVariant: "secondary",
		description: "A review is reading this work right now.",
	},
	PENDING: {
		label: "Waiting",
		icon: ClockIcon,
		badgeVariant: "secondary",
		description: "Queued behind other work. It has not started yet.",
	},
	SKIPPED: {
		label: "Skipped",
		icon: CircleSlashIcon,
		badgeVariant: "outline",
		description:
			"The workspace's review settings turned this occurrence away before a review began.",
	},
	NOT_REACHED: {
		label: "Not reached",
		icon: TimerOffIcon,
		badgeVariant: "warning",
		description: "The practice was eligible, but the review ended before it was evaluated.",
	},
	NOT_ASSESSABLE: {
		label: "Couldn't assess",
		icon: CircleHelpIcon,
		badgeVariant: "warning",
		description:
			"A review ran and the evidence it could reach did not settle the practice either way.",
	},
	TURNED_OFF: {
		label: "Turned off",
		icon: BellOffIcon,
		badgeVariant: "outline",
		description: "The practice is switched off for this workspace, so nothing was measured.",
	},
	NOT_OCCASIONED: {
		label: "Not triggered",
		icon: CircleDashedIcon,
		badgeVariant: "outline",
		description: "Nothing happened to this work that this practice watches for.",
	},
	DORMANT: {
		label: "Waiting on a connection",
		icon: UnplugIcon,
		badgeVariant: "warning",
		description:
			"The practice reads from an integration this workspace has not connected, so it cannot run.",
	},
	LAPSED: {
		label: "Expired",
		icon: HourglassIcon,
		badgeVariant: "outline",
		description: "The occasion aged out before a review got to it, and will not be picked up now.",
	},
	FAILED: {
		label: "Failed",
		icon: CircleAlertIcon,
		badgeVariant: "destructive",
		description: "A review started and could not finish. Nothing was measured.",
	},
};
