import { z } from "zod";

import {
	type DetailStackEntry,
	detailStackSchema,
} from "@/components/core/detail-drawer/detail-stack";

/** The levels the surveys page can render. Anything else in the URL is dropped by the schema. */
export const SURVEY_LEVEL_KINDS = ["survey", "survey-new"] as const;

export type SurveyLevelKind = (typeof SURVEY_LEVEL_KINDS)[number];

/** Only the composer holds a draft — see `GUARDED_LEVEL_KINDS`. Results are read-only. */
export const GUARDED_SURVEY_LEVEL_KINDS = [
	"survey-new",
] as const satisfies readonly SurveyLevelKind[];

/** A stack id is a survey id; a survey that does not exist yet has none. */
const NEW_ENTRY_ID = "draft";

export function surveyLevel(id?: string): DetailStackEntry<SurveyLevelKind> {
	return id === undefined ? { kind: "survey-new", id: NEW_ENTRY_ID } : { kind: "survey", id };
}

export const adminSurveysSearchSchema = z.object({
	...detailStackSchema(SURVEY_LEVEL_KINDS).shape,
	page: z.coerce.number().int().min(0).optional().catch(undefined),
});
