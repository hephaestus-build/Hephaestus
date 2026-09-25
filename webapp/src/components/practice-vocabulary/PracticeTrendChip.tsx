import { cn } from "cn";

import type { PracticeTrend, TrendSupport } from "@/api/types.gen";
import { FOCUS_RING } from "@/components/common/focus";
import { type StatusDef, statusToneClass } from "@/components/common/status-def";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";

import type { StandingScope } from "./practice-group-standing-defs";
import { PRACTICE_TREND_DEFS } from "./practice-trend-defs";
import { formatTrendProvenance } from "./practice-trend-presentation";

const TREND_CLASS = "flex w-fit flex-wrap items-center gap-x-1 rounded-sm text-sm";

function TrendWords({ def }: { def: StatusDef }) {
	const DirectionIcon = def.icon;
	return (
		<>
			<DirectionIcon className="size-4 shrink-0" aria-hidden />
			<span>{def.label}</span>
		</>
	);
}

export interface PracticeTrendLabelProps {
	direction: PracticeTrend["direction"];
	className?: string;
}

/** A trend as the registry's icon and words in its tone, where its sentence is printed beside it. */
export function PracticeTrendLabel({ direction, className }: PracticeTrendLabelProps) {
	const def = PRACTICE_TREND_DEFS[direction];
	return (
		<span className={cn(TREND_CLASS, statusToneClass(def.badgeVariant), className)}>
			<TrendWords def={def} />
		</span>
	);
}

export interface PracticeTrendChipProps {
	direction: PracticeTrend["direction"];
	/**
	 * The evidence behind the direction. Without it there is no provenance to give, so the tooltip
	 * says the registry's sentence for the direction instead.
	 */
	support?: TrendSupport;
	scope: StandingScope;
	className?: string;
}

/**
 * The trend label as a button holding its provenance in a tooltip, so a keyboard reaches the
 * sentence too. The button answers nothing of its own and says so with `data-tooltip-only`, so a
 * row that is itself a control takes a pointer's press on it (`PracticeTableRow`).
 */
export function PracticeTrendChip({
	direction,
	support,
	scope,
	className,
}: PracticeTrendChipProps) {
	const def = PRACTICE_TREND_DEFS[direction];
	return (
		<Tooltip>
			<TooltipTrigger
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
			</TooltipTrigger>
			<TooltipContent className="max-w-80">
				<p>{support ? formatTrendProvenance(support, direction, scope) : def.description}</p>
			</TooltipContent>
		</Tooltip>
	);
}
