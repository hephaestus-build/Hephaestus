import type { ReviewObservation } from "@/api/types.gen";

import { ASSESSMENT_DEFS } from "./assessment-defs";
import { PRESENCE_DEFS } from "./presence-defs";
import type { StatusDef } from "./status-def";

export type ObservationResultFacts = Pick<ReviewObservation, "presence" | "assessment">;

/**
 * What one observation concluded, as a single registry entry.
 *
 * <p>Two enums answer two questions — was the target behavior present (`presence`), and was that
 * good or bad (`assessment`) — while a row has one icon and one badge. This is the single rule for
 * collapsing them, so the icon, the badge and any future filter cannot disagree. `NOT_APPLICABLE`
 * and `INCONCLUSIVE` end the question and carry no assessment; the other presences may lack one,
 * and then the presence entry is the honest answer.
 */
export function observationResult(observation: ObservationResultFacts): StatusDef {
	const { presence, assessment } = observation;
	if (presence === "NOT_APPLICABLE" || presence === "INCONCLUSIVE") return PRESENCE_DEFS[presence];
	return assessment ? ASSESSMENT_DEFS[assessment] : PRESENCE_DEFS[presence];
}
