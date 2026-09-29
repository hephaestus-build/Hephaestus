import { StatusBadge } from "@/components/common/StatusBadge";
import { OBSERVATION_ORIGIN_DEFS } from "@/components/practice-vocabulary/observation-origin-defs";
import { Badge } from "@/components/ui/badge";

import { reviewOrdinalLabel } from "./review-run-groups";

/**
 * The tags a review wears on the list and on its own level. None is a status tone: beside a real
 * review state, a toned badge would read as a second state.
 */
export function LatestReviewTag() {
	return <Badge variant="secondary">Latest</Badge>;
}

/** A review somebody asked for, in the observation-origin registry's entry for the same fact. */
export function RequestedReviewTag() {
	return <StatusBadge def={OBSERVATION_ORIGIN_DEFS.MANUAL} />;
}

/** "2nd review", on a later review of the same work; nothing on the first, or while unknown. */
export function ReviewOrdinalTag({ position }: { position: number | undefined }) {
	const label = position === undefined ? undefined : reviewOrdinalLabel(position);
	if (label === undefined) {
		return null;
	}
	return <Badge variant="muted">{label}</Badge>;
}
