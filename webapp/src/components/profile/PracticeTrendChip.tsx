import { cn } from "cn";
import type { PracticeTrend, TrendSupport } from "@/api/types.gen";
import { PRACTICE_TREND_DEFS } from "@/components/practice-vocabulary/practice-trend-defs";
import { statusToneClass } from "@/components/practice-vocabulary/status-def";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";
import { formatTrendProvenance, type TrendScope } from "./practice-trend-presentation";

export interface PracticeTrendChipProps {
	direction: PracticeTrend["direction"];
	support: TrendSupport;
	scope: TrendScope;
	className?: string;
}
export function PracticeTrendChip({
	direction,
	support,
	scope,
	className,
}: PracticeTrendChipProps) {
	const def = PRACTICE_TREND_DEFS[direction];
	const DirectionIcon = def.icon;
	return (
		<Tooltip>
			<TooltipTrigger
				render={
					<button
						type="button"
						className={cn(
							"flex w-fit cursor-help flex-wrap items-center gap-x-1 rounded-sm text-sm leading-5 outline-none hover:text-foreground focus-visible:ring-2 focus-visible:ring-ring",
							statusToneClass(def.badgeVariant),
							className,
						)}
					/>
				}
			>
				<DirectionIcon className="size-4 shrink-0" aria-hidden />
				<span>{def.label}</span>
			</TooltipTrigger>
			<TooltipContent className="max-w-80 space-y-1">
				<p>{formatTrendProvenance(support, direction, scope)}</p>
				<p>This describes recent evidence, not your overall ability.</p>
			</TooltipContent>
		</Tooltip>
	);
}
