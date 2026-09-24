import { cn } from "cn";
import {
	LeagueBronzeIcon,
	LeagueDiamondIcon,
	LeagueGoldIcon,
	LeagueMasterIcon,
	LeagueNoneIcon,
	LeagueSilverIcon,
} from "@/components/leaderboard/LeagueIcons";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";

import { getLeagueLabel, getLeagueTier } from "./utils";

export interface LeagueIconProps {
	leaguePoints?: number;
	size?: "sm" | "default" | "lg" | "max" | "full";
	showPoints?: boolean;
	className?: string;
}

export function LeagueIcon({
	leaguePoints,
	size = "default",
	showPoints = false,
	className,
}: LeagueIconProps) {
	// Get tier and label based on league points
	const tier = getLeagueTier(leaguePoints);
	const label = getLeagueLabel(tier);

	// Get the appropriate icon component based on the tier
	const IconComponent = {
		none: LeagueNoneIcon,
		bronze: LeagueBronzeIcon,
		silver: LeagueSilverIcon,
		gold: LeagueGoldIcon,
		diamond: LeagueDiamondIcon,
		master: LeagueMasterIcon,
	}[tier];

	return (
		<Tooltip>
			<TooltipTrigger
				render={<div className={cn("flex flex-col items-center justify-center", className)} />}
			>
				<IconComponent size={size} aria-label={`${label} tier`} />
				{showPoints && (
					<span className="text-xs font-semibold text-muted-foreground">{leaguePoints}</span>
				)}
			</TooltipTrigger>
			<TooltipContent>
				<p>{label} League</p>
			</TooltipContent>
		</Tooltip>
	);
}
