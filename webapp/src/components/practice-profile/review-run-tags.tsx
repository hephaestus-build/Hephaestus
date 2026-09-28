import { SparkleIcon } from "lucide-react";

import { OBSERVATION_ORIGIN_DEFS } from "@/components/practice-vocabulary/observation-origin-defs";
import { Badge } from "@/components/ui/badge";

/**
 * The two tags a review run wears, in one home because the list and the run's own head both draw
 * them and a tag that reads one way on one surface and another way on the next is worse than none.
 * Neither is a status tone: one is a position in a list, the other is how the review came to run,
 * and a badge in a status tone beside a real run state would read as a second state.
 *
 * The newest run is the one the eye should land on, so it is filled with the mentor accent the
 * profile spends on exactly that — and the row it sits in stays untinted, since a whole row in a
 * tone reads as a state of the work rather than a position in a list.
 */
export function LatestRunTag() {
	return (
		<Badge variant="mentorSolid">
			<SparkleIcon aria-hidden />
			Latest
		</Badge>
	);
}

/**
 * A run somebody asked for rather than one the work occasioned. The hand is the observation-origin
 * registry's mark for that same fact, so the two surfaces cannot drift apart.
 */
export function RequestedByHandTag() {
	const Icon = OBSERVATION_ORIGIN_DEFS.MANUAL.icon;
	return (
		<Badge variant="muted">
			<Icon aria-hidden />
			Requested by hand
		</Badge>
	);
}

/**
 * Which review of its own work this one is: "2nd review", "3rd review". The first review of a piece
 * of work wears none, so the tag marks the rows and the runs that have been round before rather
 * than every one of them. `xs` in a table row, where it sits under the work's own title; its own
 * size beside the run head's other tags.
 */
export function ReviewOrdinalTag({ label, size }: { label: string; size?: "xs" }) {
	return (
		<Badge variant="muted" size={size}>
			{label}
		</Badge>
	);
}
