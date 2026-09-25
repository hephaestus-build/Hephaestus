import type { PracticeStanding, PracticeTrend, TrendSupport } from "@/api/types.gen";
import { StatusBadge } from "@/components/common/StatusBadge";

import { standingDefs, type StandingScope } from "./practice-group-standing-defs";
import { shownTrendDirection } from "./practice-trend-presentation";
import { PracticeTrendChip } from "./PracticeTrendChip";
import { StatusTooltip } from "./StatusTooltip";

export interface StandingBadgeProps {
	standing: PracticeStanding["standing"];
	/** Whose standing this is; the sentence behind the badge is worded for it. */
	scope: StandingScope;
}

/**
 * A standing as the registry's badge with its sentence behind it. A button, so a keyboard reaches
 * the sentence too; it answers nothing of its own and says so with `data-tooltip-only`, so a row
 * that is itself a control takes a pointer's press on it (`PracticeTableRow`).
 */
export function StandingBadge({ standing, scope }: StandingBadgeProps) {
	const def = standingDefs(scope)[standing];
	return (
		<StatusTooltip
			def={def}
			render={
				<StatusBadge
					def={def}
					render={<button type="button" />}
					data-tooltip-only=""
					className="cursor-help"
				/>
			}
		/>
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
