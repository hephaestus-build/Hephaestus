import type { ReviewObservation } from "@/api/types.gen";
import { OUTCOME_DEFS } from "./outcome-defs";

export type ObservationResultFacts = Pick<ReviewObservation, "outcome">;

export function observationResult(observation: ObservationResultFacts) {
	return OUTCOME_DEFS[observation.outcome];
}
