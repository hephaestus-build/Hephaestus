import { FlaskConical, Wrench } from "lucide-react";

import type { Survey, SurveyInvitation } from "@/api/types.gen";
import type { StatusDefs } from "@/components/common/status-def";

import { READERS } from "./feedback-copy";

interface ResearchSurvey {
	purpose: "RESEARCH";
	researchOrganization: string;
}

/**
 * Whether a survey is research. The server makes a survey research exactly when it names a research
 * organisation, so that organisation is the test, and every survey that passes it carries one.
 */
export function isResearch<T extends Pick<SurveyInvitation, "purpose" | "researchOrganization">>(
	survey: T,
): survey is T & ResearchSurvey {
	return survey.researchOrganization !== undefined;
}

/** "a study run by X", naming the organisation a research survey was published for. */
export function studyOf(survey: Pick<ResearchSurvey, "researchOrganization">): string {
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
		description: `Read by ${READERS} to improve the product.`,
	},
	RESEARCH: {
		label: "Research",
		icon: FlaskConical,
		badgeVariant: "default",
		description:
			"Offered only to members who take part in the research program, and labeled as research. The answers are that study’s data, not product feedback.",
	},
};
