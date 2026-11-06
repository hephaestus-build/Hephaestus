import { cn } from "cn";

import type { TrendSupport } from "@/api/types.gen";
import { FOCUS_RING } from "@/components/common/focus";
import { type StatusDef, statusToneClass } from "@/components/common/status-def";

import type { StandingScope } from "./practice-group-standing-defs";
import {
	GROUP_TREND_DESCRIPTIONS,
	PRACTICE_TREND_DEFS,
	type TrendDirection,
} from "./practice-trend-defs";
import { trendComparison } from "./practice-trend-presentation";
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

/** The direction in plain words; a group's says it in its own. */
function directionSentence(direction: TrendDirection, scope: StandingScope): string {
	return scope === "group"
		? GROUP_TREND_DESCRIPTIONS[direction]
		: PRACTICE_TREND_DEFS[direction].description;
}

export interface PracticeTrendChipProps {
	direction: TrendDirection;
	/**
	 * The evidence behind the direction: its counts name the compared work and, without a
	 * direction yet, how much is still missing. Without it the tooltip says the default comparison.
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
	// The direction in plain words first, then one sentence on what it compares.
	const sentence = [directionSentence(direction, scope), trendComparison(direction, scope, support)]
		.filter(Boolean)
		.join(" ");
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
