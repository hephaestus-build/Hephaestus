import type { ReviewObservation } from "@/api/types.gen";

import { ASSESSMENT_STATUS_DEFS } from "./assessment-status-defs";
import { OUTCOME_DEFS, derivedOutcome } from "./outcome-defs";
import type { StatusDef } from "./status-def";

export type ObservationResultFacts = Pick<
	ReviewObservation,
	"assessmentStatus" | "presence" | "assessment"
>;

/** Status explains unassessed work; assessed results derive their outcome from the matrix. */
export function observationResult(observation: ObservationResultFacts): StatusDef {
	if (observation.assessmentStatus !== "ASSESSED")
		return ASSESSMENT_STATUS_DEFS[observation.assessmentStatus];
	if (!observation.presence || !observation.assessment)
		throw new Error("Assessed observations require both matrix axes");
	return OUTCOME_DEFS[derivedOutcome(observation.presence, observation.assessment)];
}
