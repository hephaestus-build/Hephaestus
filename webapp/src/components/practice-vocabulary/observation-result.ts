import type { ReviewObservation } from "@/api/types.gen";

import { ASSESSMENT_DEFS } from "./assessment-defs";
import { ASSESSMENT_STATUS_DEFS } from "./assessment-status-defs";
import type { StatusDef } from "./status-def";

export type ObservationResultFacts = Pick<
	ReviewObservation,
	"assessmentStatus" | "presence" | "assessment"
>;

/** Status describes unassessed work; assessment describes assessed work. Presence never supplies valence. */
export function observationResult(observation: ObservationResultFacts): StatusDef {
	if (observation.assessmentStatus !== "ASSESSED")
		return ASSESSMENT_STATUS_DEFS[observation.assessmentStatus];
	return observation.assessment
		? ASSESSMENT_DEFS[observation.assessment]
		: ASSESSMENT_STATUS_DEFS.ASSESSED;
}
