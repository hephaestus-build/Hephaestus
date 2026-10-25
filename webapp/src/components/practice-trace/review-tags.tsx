import { LayersIcon } from "lucide-react";

import type { ReviewRef } from "@/api/types.gen";
import { StatusBadge } from "@/components/common/StatusBadge";
import { REVIEW_ORIGIN_DEFS } from "@/components/practice-vocabulary/review-origin-defs";
import { Badge } from "@/components/ui/badge";

import { reviewOrdinalLabel } from "./review-groups";

/**
 * How a review began, where that is news: requested, scheduled or backfilled. A review an event
 * started is the usual case, so it has no tag.
 */
export function ReviewOriginTag({ origin }: { origin: Exclude<ReviewRef["origin"], "EVENT"> }) {
	return <StatusBadge def={REVIEW_ORIGIN_DEFS[origin]} />;
}

export function ReviewOrdinalTag({
	position,
	total,
}: {
	position: number | undefined;
	total?: number | undefined;
}) {
	const label = position === undefined ? undefined : reviewOrdinalLabel(position, total);
	if (label === undefined) {
		return null;
	}
	// The stack says these are runs of one work, as the label counts them.
	return (
		<Badge variant="muted">
			<LayersIcon aria-hidden data-icon="inline-start" />
			{label}
		</Badge>
	);
}
