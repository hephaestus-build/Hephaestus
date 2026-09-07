import type { Survey } from "@/api/types.gen";

export function productSurveyAvailability(
	survey: Pick<Survey, "active" | "startsAt" | "endsAt">,
	now: number,
) {
	if (!survey.active) return "Paused";
	if (survey.endsAt && survey.endsAt.getTime() <= now) return "Ended";
	if (survey.startsAt.getTime() > now) return "Scheduled";
	return "Accepting responses";
}
