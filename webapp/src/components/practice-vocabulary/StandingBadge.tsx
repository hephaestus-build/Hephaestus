import type { PracticeStanding, PracticeTrend, TrendSupport } from "@/api/types.gen";
import { StatusBadge } from "@/components/common/StatusBadge";

import {
	isSettledStanding,
	standingDefs,
	type StandingScope,
} from "./practice-group-standing-defs";
import {
	explainStanding,
	formatStandingWork,
	shownTrendDirection,
	standingWork,
} from "./practice-trend-presentation";
import { PracticeTrendChip } from "./PracticeTrendChip";
import { StatusTooltip } from "./StatusTooltip";

export interface StandingBadgeProps {
	standing: PracticeStanding["standing"];
	/** Whose standing this is; the sentence behind the badge is worded for it. */
	scope: StandingScope;
	/** With it, a settled standing names the pieces of work it is read from. */
	support?: TrendSupport;
}

/**
 * A button, so a keyboard reaches the tooltip's sentence. `data-tooltip-only` lets a row that is
 * itself a control take a pointer's press on it (`PracticeTableRow`).
 */
export function StandingBadge({ standing, scope, support }: StandingBadgeProps) {
	const registryDef = standingDefs(scope)[standing];
	const def = { ...registryDef, description: explainStanding(standing, scope, support) };
	const work = isSettledStanding(standing) ? standingWork(support) : undefined;
	return (
		<>
			<StatusTooltip
				def={def}
				render={
					<StatusBadge
						def={registryDef}
						render={<button type="button" />}
						data-tooltip-only=""
						className="cursor-help"
					/>
				}
			/>
			{work !== undefined && (
				<span className="text-xs text-muted-foreground">{formatStandingWork(work)}</span>
			)}
		</>
	);
}

export interface TrendNoteProps {
	direction?: PracticeTrend["direction"];
	support?: TrendSupport;
	scope: StandingScope;
}

/** The trend chip under a badge, for a row that may have no comparable trend at all. */
export function TrendNote({ direction, support, scope }: TrendNoteProps) {
	return (
		<PracticeTrendChip
			direction={shownTrendDirection(direction, support)}
			support={support}
			scope={scope}
			className="flex-nowrap gap-x-1.5 whitespace-nowrap"
		/>
	);
}
