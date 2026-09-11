import { CalendarClock, CircleCheck, CircleDot, PauseCircle } from "lucide-react";

import type { Survey } from "@/api/types.gen";
import type { StatusDefs } from "@/components/practice-vocabulary/status-def";

/**
 * A survey's availability is derived from its schedule and pause flag rather than stored, so the
 * badge is always right at the moment it is rendered. Ended wins over paused: a survey whose window
 * has closed is over whatever the pause flag says, and paused wins over scheduled because a paused
 * survey will not open when its start arrives.
 */
export type SurveyAvailability = "SCHEDULED" | "OPEN" | "PAUSED" | "ENDED";

export const SURVEY_AVAILABILITY_DEFS: StatusDefs<SurveyAvailability> = {
	OPEN: {
		label: "Open",
		icon: CircleDot,
		badgeVariant: "success",
		description: "Members of the audience are invited and can respond.",
	},
	SCHEDULED: {
		label: "Scheduled",
		icon: CalendarClock,
		badgeVariant: "secondary",
		description: "Invitations appear when the start time arrives.",
	},
	PAUSED: {
		label: "Paused",
		icon: PauseCircle,
		badgeVariant: "warning",
		description: "Invitations are hidden and responses are refused until it is resumed.",
	},
	ENDED: {
		label: "Ended",
		icon: CircleCheck,
		badgeVariant: "outline",
		description: "The window has closed; the responses stay.",
	},
};

export function surveyAvailability(
	survey: Pick<Survey, "active" | "startsAt" | "endsAt">,
	now: number,
): SurveyAvailability {
	if (survey.endsAt && survey.endsAt.getTime() <= now) return "ENDED";
	if (!survey.active) return "PAUSED";
	if (survey.startsAt.getTime() > now) return "SCHEDULED";
	return "OPEN";
}
