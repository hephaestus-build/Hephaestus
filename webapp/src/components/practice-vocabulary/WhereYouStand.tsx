import { useId } from "react";

import type { TrendSupport } from "@/api/types.gen";
import { SectionLabel } from "@/components/common/SectionLabel";
import { StatusBadge } from "@/components/common/StatusBadge";
import { hasText } from "@/lib/text";
import {
	isSettledStanding,
	type PracticeGroupStandingValue,
	standingDefs,
	type StandingScope,
} from "./practice-group-standing-defs";
import { PRACTICE_TREND_DEFS, type TrendDirection } from "./practice-trend-defs";
import { formatTrendProvenance, shownTrendDirection } from "./practice-trend-presentation";
import { PracticeTrendLabel } from "./PracticeTrendChip";

export interface WhereYouStandProps {
	standing: PracticeGroupStandingValue;
	/**
	 * What the standing rests on, after the registry's sentence: the work it was read from, or the
	 * practices counted.
	 */
	basis?: string;
	direction?: TrendDirection;
	support?: TrendSupport;
	/** Whose standing this is; the trend's provenance is worded for it. */
	scope: StandingScope;
}

/**
 * The standing and the trend a level's header shows as a badge and a chip, each followed by the
 * registry's sentence for it and what it rests on — printed beside them, so here neither is a
 * tooltip's trigger. The two lines sit in one box on the `bg-sidebar` ground the card's action
 * band uses, under the label, so the reader's own standing is told from the catalog's words
 * below it. A standing no review has settled says so and has no trend: a direction over no
 * verdict would be a claim about nothing.
 */
export function WhereYouStand({ standing, basis, direction, support, scope }: WhereYouStandProps) {
	const headingId = useId();
	const settled = isSettledStanding(standing);
	const shownDirection = shownTrendDirection(direction, support);
	const def = standingDefs(scope)[standing];
	return (
		<section className="flex flex-col gap-2.5" aria-labelledby={headingId}>
			<SectionLabel as="h2" id={headingId}>
				Where you stand
			</SectionLabel>
			<div className="flex max-w-2xl flex-col gap-3 rounded-lg border bg-sidebar p-4">
				<p className="text-sm">
					<StatusBadge def={def} className="mr-2 align-middle" />
					{def.description}
					{hasText(basis) && ` ${basis}`}
				</p>
				{settled && (
					<p className="text-sm">
						<PracticeTrendLabel
							direction={shownDirection}
							className="mr-2 inline-flex flex-nowrap gap-x-1.5 align-middle whitespace-nowrap"
						/>
						{PRACTICE_TREND_DEFS[shownDirection].description}
						{support && ` ${formatTrendProvenance(support, shownDirection, scope)}`}
					</p>
				)}
			</div>
		</section>
	);
}
