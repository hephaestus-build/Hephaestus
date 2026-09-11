import { format } from "date-fns";

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
import { cn } from "@/lib/utils.ts";

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

export function ProfileHeader({
	user,
	firstContribution,
	leaguePoints = 0,
	userXpRecord = { currentLevel: 1, currentLevelXP: 0, totalXP: 0, xpNeeded: 150 },
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

	return (
		<div className="flex min-w-0 flex-row items-start justify-between gap-4 sm:gap-6">
			<div className="flex min-w-0 w-full max-w-xl flex-col gap-4">
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
							<Skeleton className="absolute -bottom-1 -right-1 size-7 rounded-full" />
						) : (
							<Tooltip>
								<TooltipTrigger
									render={
										<div
											className={cn(
												"absolute -bottom-1 -right-1 flex size-7 items-center justify-center rounded-full border-2 border-background font-bold text-xs",
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

					{isLoading ? (
						<div className="flex min-w-0 flex-col gap-1.5">
							<Skeleton className="h-7 w-40" />
							<Skeleton className="h-5 w-48" />
						</div>
					) : user ? (
						<div className="flex min-w-0 flex-col gap-0.5">
							<h1 className="break-words text-xl font-bold leading-tight md:text-2xl">
								{user.name}
							</h1>
							<div className="flex min-w-0 flex-wrap items-center gap-2">
								<a
									className="min-w-0 break-all text-sm text-muted-foreground transition-colors hover:text-primary md:text-base"
									href={user.htmlUrl}
									target="_blank"
									rel="noopener noreferrer"
								>
									{user.htmlUrl ? new URL(user.htmlUrl).host : ""}/{user.login}
								</a>
							</div>
						</div>
					) : null}
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
				<div className="flex flex-col items-center gap-1 shrink-0">
					{isLoading ? (
						<>
							<Skeleton className="size-16 rounded-full" />
							<Skeleton className="h-5 w-12" />
						</>
					) : (
						<>
							<LeagueIcon leaguePoints={leaguePoints} size="lg" />
							<span className="text-muted-foreground text-base font-semibold">{leaguePoints}</span>
						</>
					)}
				</div>
			)}
		</div>
	);
}
