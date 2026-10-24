import {
	BadgeCheckIcon,
	CircleSlashIcon,
	MessageSquareIcon,
	MessageSquareWarningIcon,
} from "lucide-react";

import type { ResultMark } from "@/api/types.gen";

import type { StatusDef, StatusDefs } from "@/components/common/status-def";

export type ResultMarkKind = ResultMark["kind"];

// Accepted marks cannot override Incorrect; developer disagreement belongs to feedback responses.
export const RESULT_MARK_DEFS: Record<ResultMarkKind, StatusDef & { note: string }> = {
	INCORRECT: {
		label: "Marked incorrect",
		icon: CircleSlashIcon,
		badgeVariant: "destructive",
		description:
			"A workspace admin marked this observation incorrect. Its pending feedback stops and posted comments on the work are retracted.",
		note: "Only observations an admin found wrong are marked. An unmarked one is not confirmed correct.",
	},
	ACCEPTED: {
		label: "Marked accepted",
		icon: BadgeCheckIcon,
		badgeVariant: "success",
		description:
			"A workspace admin accepted this observation. It records the review’s quality and changes no feedback.",
		note: "Accepted observations leave the open lists. Acceptance does not change any outcome.",
	},
};

export const RESULT_MARK_ACTIONS: Record<ResultMarkKind, string> = {
	INCORRECT: "Mark incorrect",
	ACCEPTED: "Mark accepted",
};

export const REMOVE_MARK_ACTION = "Remove mark";

export type Disagreement = "DISAGREED" | "NOT_DISAGREED";

export const DISAGREEMENT_DEFS: StatusDefs<Disagreement> = {
	DISAGREED: {
		label: "Disagreed",
		icon: MessageSquareWarningIcon,
		badgeVariant: "warning",
		description:
			"The developer disagrees with feedback written from this observation and explained why. Mark the observation incorrect or withdraw the feedback if they are right.",
	},
	NOT_DISAGREED: {
		label: "No disagreement",
		icon: MessageSquareIcon,
		badgeVariant: "outline",
		description: "The developer disagrees with no feedback written from this observation.",
	},
};
