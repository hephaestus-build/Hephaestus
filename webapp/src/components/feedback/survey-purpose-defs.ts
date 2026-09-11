import { FlaskConical, Wrench } from "lucide-react";

import type { Survey, SurveyInvitation } from "@/api/types.gen";
import type { StatusDefs } from "@/components/practice-vocabulary/status-def";

/** "a study run by X" — a research survey always names the organisation it was published for. */
export function studyOf(survey: Pick<SurveyInvitation, "researchOrganization">): string {
	return `a study run by ${survey.researchOrganization}`;
}

/**
 * Why a survey asks, which decides who is invited and who reads the answers. A research survey
 * reaches only accounts that agreed to the study this instance names, and its answers are that
 * study's data, not product feedback.
 */
export const SURVEY_PURPOSE_DEFS: StatusDefs<Survey["purpose"]> = {
	PRODUCT: {
		label: "Product",
		icon: Wrench,
		badgeVariant: "secondary",
		description: "Read by this instance's administrators to improve Hephaestus here.",
	},
	RESEARCH: {
		label: "Research",
		icon: FlaskConical,
		badgeVariant: "default",
		description:
			"Offered only to members who take part in the research programme, and labelled as research; the answers are that study's data, not product feedback.",
	},
};
