import { ClockIcon } from "@primer/octicons-react";

import { cn } from "cn";
import { Progress, ProgressIndicator, ProgressTrack } from "@/components/ui/progress";

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
				<div className="flex items-baseline justify-between px-0.5">
					<span className="text-xs font-semibold text-muted-foreground">
						{currentXP.toLocaleString()} / {xpNeeded.toLocaleString()} XP to Level {nextLevel}
					</span>
					<span className="text-xs text-muted-foreground">{totalXP.toLocaleString()} XP total</span>
				</div>

				<div className="relative h-2.5 w-full overflow-hidden rounded-full bg-secondary/80">
					<div className="pointer-events-none absolute inset-0 z-10 rounded-full bg-gradient-to-b from-white/10 to-transparent" />

					<Progress
						value={percentage}
						aria-label={`Progress to level ${nextLevel}`}
						className="h-full w-full"
					>
						<ProgressTrack className="h-full rounded-full bg-transparent">
							<ProgressIndicator className="absolute rounded-full bg-gradient-to-r from-primary/90 to-primary transition-all duration-500" />
						</ProgressTrack>
					</Progress>
				</div>

				{contributingSince && (
					<div className="mt-0.5 flex items-center gap-1.5 text-xs text-muted-foreground">
						<ClockIcon size={12} className="shrink-0" />
						<span>Contributing since {contributingSince}</span>
					</div>
				)}
			</div>
		</div>
	);
}
