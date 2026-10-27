import { cn } from "cn";

import type { TrendSupport } from "@/api/types.gen";
import { FOCUS_RING } from "@/components/common/focus";
import { type StatusDef, statusToneClass } from "@/components/common/status-def";

import type { StandingScope } from "./practice-group-standing-defs";
import { PRACTICE_TREND_DEFS, type TrendDirection } from "./practice-trend-defs";
import { formatTrendProvenance, TREND_EXPLAINED } from "./practice-trend-presentation";
import { StatusTooltip } from "./StatusTooltip";

const TREND_CLASS = "flex w-fit flex-wrap items-center gap-x-1 rounded-sm text-sm";

/** A trend's icon and words, in the tone of the element around them. */
export function TrendWords({ def }: { def: StatusDef }) {
	const DirectionIcon = def.icon;
	return (
		<>
			<DirectionIcon className="size-4 shrink-0" aria-hidden />
			<span>{def.label}</span>
		</>
	);
}

export interface PracticeTrendChipProps {
	direction: TrendDirection;
	/**
	 * The evidence behind the direction. Without it there is no provenance to give, so the tooltip
	 * says the registry's sentence for the direction instead.
	 */
	support?: TrendSupport;
	scope: StandingScope;
	className?: string;
}

/**
 * The trend label as a button holding its provenance behind it ({@link StatusTooltip}), so a keyboard
 * and a touch screen reach the sentence too. The button answers nothing of its own and says so
 * with `data-tooltip-only`, so a row that is itself a control takes a mouse press on it
 * (`PracticeTableRow`). A tap or a key opens the sentence instead.
 */
export function PracticeTrendChip({
	direction,
	support,
	scope,
	className,
}: PracticeTrendChipProps) {
	const def = PRACTICE_TREND_DEFS[direction];
	const evidence = support ? formatTrendProvenance(support, direction, scope) : def.description;
	// What a trend compares comes first, then this one's evidence.
	const sentence = `${TREND_EXPLAINED} ${evidence}`;
	return (
		<StatusTooltip
			openOnTap
			bare
			def={{ label: def.label, description: sentence }}
			render={
				<button
					type="button"
					data-tooltip-only=""
					className={cn(
						TREND_CLASS,
						statusToneClass(def.badgeVariant),
						"cursor-help hover:text-foreground",
						FOCUS_RING,
						className,
					)}
				/>
			}
		>
			<TrendWords def={def} />
		</StatusTooltip>
	);
}
