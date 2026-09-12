import { Progress as ProgressRoot } from "@base-ui/react/progress";
import { ClockIcon } from "@primer/octicons-react";

import { cn } from "cn";
import { ProgressIndicator, ProgressTrack } from "@/components/ui/progress";

export interface XpProgressProps {
	currentXP: number;
	xpNeeded: number;
	nextLevel: number;
	totalXP: number;
	contributingSince?: string;
	className?: string;
}

export function XpProgress({
	currentXP,
	xpNeeded,
	nextLevel,
	totalXP,
	contributingSince,
	className,
}: XpProgressProps) {
	const percentage = xpNeeded > 0 ? Math.min(100, Math.max(0, (currentXP / xpNeeded) * 100)) : 0;

	return (
		<div className={cn("w-full", className)}>
			<div className="flex flex-col gap-1.5">
				<div className="flex justify-between items-baseline px-0.5">
					<span className="text-xs font-semibold text-muted-foreground">
						{currentXP.toLocaleString()} / {xpNeeded.toLocaleString()} XP to Level {nextLevel}
					</span>
					<span className="text-xs text-muted-foreground">{totalXP.toLocaleString()} XP total</span>
				</div>

				<div className="relative h-2.5 w-full bg-secondary/80 rounded-full overflow-hidden">
					<div className="absolute inset-0 z-10 bg-gradient-to-b from-white/10 to-transparent pointer-events-none rounded-full" />

					<ProgressRoot.Root
						value={percentage}
						aria-label={`Progress to level ${nextLevel}`}
						className="h-full w-full"
					>
						<ProgressTrack className="h-full rounded-full bg-transparent">
							<ProgressIndicator className="absolute bg-gradient-to-r from-primary/90 to-primary rounded-full transition-all duration-500" />
						</ProgressTrack>
					</ProgressRoot.Root>
				</div>

				{contributingSince && (
					<div className="flex items-center gap-1.5 text-muted-foreground text-xs mt-0.5">
						<ClockIcon size={12} className="shrink-0" />
						<span>Contributing since {contributingSince}</span>
					</div>
				)}
			</div>
		</div>
	);
}
