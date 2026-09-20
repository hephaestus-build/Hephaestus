import { format } from "date-fns";
import type { ReactNode } from "react";

import { cn } from "cn";
import type { ProfileXpRecord, RepositoryInfo, UserInfo } from "@/api/types.gen";
import { LeagueIcon } from "@/components/leaderboard/LeagueIcon";
import {
	getLeagueColor,
	getLeagueForegroundColor,
	getLeagueTier,
} from "@/components/leaderboard/utils.ts";
import { Avatar, AvatarFallback, AvatarImage } from "@/components/ui/avatar";
import { Skeleton } from "@/components/ui/skeleton";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";
import { getInitials } from "@/lib/avatar";

import { XpProgress } from "./XpProgress";

export interface ProfileHeaderProps {
	user?: UserInfo;
	firstContribution?: Date;
	contributedRepositories?: RepositoryInfo[];
	leaguePoints?: number;
	userXpRecord?: ProfileXpRecord;
	isLoading: boolean;
	progressionEnabled?: boolean;
	leaguesEnabled?: boolean;
}

const FIRST_LEVEL: ProfileXpRecord = {
	currentLevel: 1,
	currentLevelXP: 0,
	totalXP: 0,
	xpNeeded: 150,
};

export function ProfileHeader({
	user,
	firstContribution,
	leaguePoints = 0,
	userXpRecord = FIRST_LEVEL,
	isLoading,
	progressionEnabled = true,
	leaguesEnabled = true,
}: ProfileHeaderProps) {
	const { currentLevel: level, currentLevelXP: currentXp, xpNeeded, totalXP } = userXpRecord;

	const formattedFirstContribution = firstContribution
		? format(firstContribution, "MMMM yyyy")
		: undefined;

	const rawTier = getLeagueTier(leaguePoints);
	const leagueTier = rawTier === "none" ? "bronze" : rawTier;

	let identity: ReactNode = null;
	if (isLoading) {
		identity = (
			<div className="flex min-w-0 flex-col gap-1.5">
				<Skeleton className="h-7 w-40" />
				<Skeleton className="h-5 w-48" />
			</div>
		);
	} else if (user) {
		identity = (
			<div className="flex min-w-0 flex-col gap-0.5">
				<h1 className="text-xl leading-tight font-bold break-words md:text-2xl">{user.name}</h1>
				<div className="flex min-w-0 flex-wrap items-center gap-2">
					<a
						className="min-w-0 text-sm break-all text-muted-foreground transition-colors hover:text-primary md:text-base"
						href={user.htmlUrl}
						target="_blank"
						rel="noopener noreferrer"
					>
						{user.htmlUrl ? new URL(user.htmlUrl).host : ""}/{user.login}
					</a>
				</div>
			</div>
		);
	}

	return (
		<div className="flex min-w-0 flex-row items-start justify-between gap-4 sm:gap-6">
			<div className="flex w-full max-w-xl min-w-0 flex-col gap-4">
				<div className="flex min-w-0 items-center gap-4">
					<div className="relative shrink-0">
						{isLoading ? (
							<Avatar className="size-16">
								<Skeleton className="h-full w-full rounded-full" />
							</Avatar>
						) : (
							<Avatar className="size-16 border-2 border-background shadow-sm">
								<AvatarImage src={user?.avatarUrl} alt={`${user?.login}'s avatar`} />
								<AvatarFallback>{getInitials(user?.name, user?.login)}</AvatarFallback>
							</Avatar>
						)}

						{isLoading ? (
							<Skeleton className="absolute -right-1 -bottom-1 size-7 rounded-full" />
						) : (
							<Tooltip>
								<TooltipTrigger
									render={
										<div
											className={cn(
												"absolute -right-1 -bottom-1 flex size-7 items-center justify-center rounded-full border-2 border-background text-xs font-bold",
												getLeagueColor(leagueTier),
												getLeagueForegroundColor(leagueTier),
											)}
										/>
									}
								>
									{level}
								</TooltipTrigger>
								<TooltipContent side="bottom">
									<p>Level {level}</p>
								</TooltipContent>
							</Tooltip>
						)}
					</div>

					{identity}
				</div>

				{progressionEnabled &&
					(isLoading ? (
						<div className="flex flex-col gap-2">
							<Skeleton className="h-4 w-48" />
							<Skeleton className="h-2.5 w-full max-w-sm" />
							<Skeleton className="h-4 w-40" />
						</div>
					) : (
						<XpProgress
							className="max-w-sm"
							currentXP={currentXp}
							xpNeeded={xpNeeded}
							nextLevel={level + 1}
							totalXP={totalXP}
							contributingSince={formattedFirstContribution}
						/>
					))}
			</div>

			{leaguesEnabled && (
				<div className="flex shrink-0 flex-col items-center gap-1">
					{isLoading ? (
						<>
							<Skeleton className="size-16 rounded-full" />
							<Skeleton className="h-5 w-12" />
						</>
					) : (
						<>
							<LeagueIcon leaguePoints={leaguePoints} size="lg" />
							<span className="text-base font-semibold text-muted-foreground">{leaguePoints}</span>
						</>
					)}
				</div>
			)}
		</div>
	);
}
