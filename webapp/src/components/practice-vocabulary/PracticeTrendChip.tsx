import { useRender } from "@base-ui/react/use-render";
import { cn } from "cn";
import type { ReactElement } from "react";

import type { PracticeTrend, TrendSupport } from "@/api/types.gen";
import { FOCUS_RING } from "@/components/common/focus";
import { statusToneClass } from "@/components/common/status-def";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";

import { PRACTICE_TREND_DEFS } from "./practice-trend-defs";
import { formatTrendProvenance, type TrendScope } from "./practice-trend-presentation";

export interface PracticeTrendChipProps {
	direction: PracticeTrend["direction"];
	/**
	 * The evidence behind the direction. Without it there is no provenance to give, so the tooltip
	 * says the registry's sentence for the direction instead.
	 */
	support?: TrendSupport;
	scope: TrendScope;
	/**
	 * The chip as this element and nothing more — a `<span />` where the sentence is printed beside
	 * it, so there is no tooltip to reach and nothing to focus. Without it the chip is a button
	 * holding the tooltip, so a keyboard reaches the sentence too.
	 */
	render?: ReactElement;
	className?: string;
}
export function PracticeTrendChip({
	direction,
	support,
	scope,
	render,
	className,
}: PracticeTrendChipProps) {
	const def = PRACTICE_TREND_DEFS[direction];
	const DirectionIcon = def.icon;
	const chip = useRender({
		// The button only holds the tooltip: a row that is itself a control takes the press
		// (`PracticeTableRow`).
		render: render ?? <button type="button" data-tooltip-only="" />,
		props: {
			className: cn(
				"flex w-fit flex-wrap items-center gap-x-1 rounded-sm text-sm",
				statusToneClass(def.badgeVariant),
				!render && ["cursor-help hover:text-foreground", FOCUS_RING],
				className,
			),
			children: (
				<>
					<DirectionIcon className="size-4 shrink-0" aria-hidden />
					<span>{def.label}</span>
				</>
			),
		},
	});
	if (render) {
		return chip;
	}
	return (
		<Tooltip>
			<TooltipTrigger render={chip} />
			<TooltipContent className="max-w-80">
				<p>{support ? formatTrendProvenance(support, direction, scope) : def.description}</p>
			</TooltipContent>
		</Tooltip>
	);
}
