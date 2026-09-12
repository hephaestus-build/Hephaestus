import { Progress as ProgressRoot } from "@base-ui/react/progress";
import { Info, Star } from "lucide-react";

import { cn } from "cn";
import { Button } from "@/components/ui/button";
import { ProgressIndicator, ProgressTrack } from "@/components/ui/progress";

import { LeagueIcon } from "./LeagueIcon";
import { getLeagueFromPoints } from "./utils";

export interface LeagueProgressCardProps {
	leaguePoints: number;
	onInfoClick?: () => void;
}

export function LeagueProgressCard({ leaguePoints, onInfoClick }: LeagueProgressCardProps) {
	const currentLeague = getLeagueFromPoints(leaguePoints);

	const progressValue = currentLeague
		? ((leaguePoints - currentLeague.minPoints) * 100) /
			(currentLeague.maxPoints - currentLeague.minPoints)
		: 0;

	if (!currentLeague) return null;

	return (
		<div className="flex items-center gap-2 2xl:gap-4">
			<LeagueIcon leaguePoints={leaguePoints} size="lg" />
			<div className="flex flex-col -space-y-1 min-w-[140px]">
				<div className="flex items-center gap-2">
					<div>
						<span className="text-sm font-semibold text-muted-foreground">
							{currentLeague.name}
						</span>
						<div className="w-full flex items-center justify-center gap-1 text-sm text-muted-foreground">
							<span className="whitespace-nowrap">
								{currentLeague.maxPoints === Number.POSITIVE_INFINITY
									? `${leaguePoints}`
									: `${leaguePoints} / ${currentLeague.maxPoints}`}
							</span>
							<Star className="h-4 w-4 flex-shrink-0" />
						</div>
					</div>
					{onInfoClick && (
						<Button variant="ghost" size="icon" onClick={onInfoClick} aria-label="About leagues">
							<Info className="text-muted-foreground" />
						</Button>
					)}
				</div>
				{currentLeague.maxPoints !== Number.POSITIVE_INFINITY && (
					<div className="flex items-center gap-2 mt-1">
						<ProgressRoot.Root
							value={progressValue}
							aria-label={`${Math.round(progressValue)}% progress to next league`}
							className="w-full"
						>
							<ProgressTrack className="h-2 bg-secondary">
								<ProgressIndicator
									className={cn("absolute", {
										"bg-league-bronze": currentLeague.name === "Bronze",
										"bg-league-silver": currentLeague.name === "Silver",
										"bg-league-gold": currentLeague.name === "Gold",
										"bg-league-diamond": currentLeague.name === "Diamond",
									})}
								/>
							</ProgressTrack>
						</ProgressRoot.Root>
						<LeagueIcon
							leaguePoints={currentLeague.maxPoints + 1}
							size="sm"
							className="flex-shrink-0"
						/>
					</div>
				)}
			</div>
		</div>
	);
}
