import { NoEntryIcon } from "@primer/octicons-react";
import { AwardIcon } from "lucide-react";
import type { ReactNode } from "react";

import { cn } from "cn";
import type { LeaderboardEntry, UserInfo } from "@/api/types.gen";
import { ActivityBadges } from "@/components/leaderboard/ActivityBadges";
import type { LeaderboardVariant } from "@/components/leaderboard/LeaderboardPage";
import { Avatar, AvatarFallback, AvatarImage } from "@/components/ui/avatar";
import { Skeleton } from "@/components/ui/skeleton";
import {
	Table,
	TableBody,
	TableCell,
	TableHead,
	TableHeader,
	TableRow,
} from "@/components/ui/table";
import { getInitials } from "@/lib/avatar";
import { getTeamAvatarUrl, type ProviderType } from "@/lib/provider";

import { LeagueIcon } from "./LeagueIcon";

type TeamLeaderboardEntry = LeaderboardEntry & {
	team: NonNullable<LeaderboardEntry["team"]>;
};

export interface LeaderboardTableProps {
	leaderboard?: LeaderboardEntry[] | TeamLeaderboardEntry[];
	isLoading: boolean;
	variant: LeaderboardVariant;
	currentUser?: UserInfo;
	renderUserLink?: (username: string, children: ReactNode) => ReactNode;
	renderTeamLink?: (teamId: number, children: ReactNode) => ReactNode;
	teamLabelsById?: Record<number, string>;
	providerType?: ProviderType;
	leaguesEnabled?: boolean;
}
export function LeaderboardTable({
	leaderboard = [],
	isLoading,
	variant,
	currentUser,
	renderUserLink,
	renderTeamLink,
	teamLabelsById,
	providerType = "GITHUB",
	leaguesEnabled = true,
}: LeaderboardTableProps) {
	if (isLoading) {
		return <LeaderboardTableSkeleton />;
	}

	if (leaderboard.length === 0) {
		return (
			<div className="flex flex-col items-center justify-center px-4 py-8 text-center">
				<NoEntryIcon className="h-12 w-12 text-provider-danger-foreground mb-2" />
				<h2 className="text-lg font-medium">No entries found</h2>
				<p className="text-muted-foreground">There are no leaderboard entries available.</p>
			</div>
		);
	}

	const isTeam = variant === "TEAM";
	const entries: LeaderboardEntry[] = leaderboard;

	return (
		<Table>
			<TableHeader>
				<TableRow>
					<TableHead className="text-center w-10">Rank</TableHead>
					{!isTeam && leaguesEnabled && <TableHead className="text-center w-20">League</TableHead>}
					<TableHead className="w-56">{isTeam ? "Team" : "Contributor"}</TableHead>
					<TableHead className="text-center">
						<div className="flex justify-center items-center gap-1 text-provider-done-foreground">
							<span className="flex items-center gap-0.5">
								<AwardIcon className="size-4" /> Score
							</span>
						</div>
					</TableHead>
					<TableHead>Activity</TableHead>
				</TableRow>
			</TableHeader>
			<TableBody>
				{entries.map((entry) => {
					if (isTeam) {
						const team = entry.team;
						if (!team) return null;
						const displayName = teamLabelsById?.[team.id] ?? team.name;
						const teamIdentity = (
							<div className="flex items-center gap-2 font-medium">
								<Avatar className="size-9">
									<AvatarImage
										src={getTeamAvatarUrl(providerType, team.id) ?? undefined}
										alt={`${displayName}'s avatar`}
									/>
									<AvatarFallback>{getInitials(displayName)}</AvatarFallback>
								</Avatar>
								<span className="text-wrap text-muted-foreground">{displayName}</span>
							</div>
						);
						return (
							<TableRow key={team.id} id={`team-${team.id}`}>
								<TableCell className="text-center">{entry.rank}</TableCell>
								<TableCell>
									{renderTeamLink ? renderTeamLink(team.id, teamIdentity) : teamIdentity}
								</TableCell>
								<TableCell className="text-center font-medium">{entry.score}</TableCell>
								<TableCell>
									<ActivityBadges
										reviewedPullRequests={entry.reviewedPullRequests}
										changeRequests={entry.numberOfChangeRequests}
										approvals={entry.numberOfApprovals}
										comments={entry.numberOfComments}
										codeComments={entry.numberOfCodeComments}
										ownReplies={entry.numberOfOwnReplies}
										openPullRequests={entry.numberOfOpenPullRequests}
										mergedPullRequests={entry.numberOfMergedPullRequests}
										closedPullRequests={entry.numberOfClosedPullRequests}
										openedIssues={entry.numberOfOpenedIssues}
										closedIssues={entry.numberOfClosedIssues}
										providerType={providerType}
									/>
								</TableCell>
							</TableRow>
						);
					}

					const user = entry.user;
					if (!user) {
						return null;
					}

					const currentUserLogin = currentUser?.login ? currentUser.login.toLowerCase() : undefined;
					const isCurrentUser = currentUserLogin === user.login.toLowerCase();
					const userIdentity = (
						<div className="flex items-center gap-2 font-medium">
							<Avatar className="size-9">
								<AvatarImage src={user.avatarUrl || undefined} alt={`${user.name}'s avatar`} />
								<AvatarFallback>{getInitials(user.name, user.login)}</AvatarFallback>
							</Avatar>
							<span className="text-wrap text-muted-foreground">{user.name}</span>
						</div>
					);

					return (
						<TableRow
							key={user.login}
							id={`rank-${entry.rank}`}
							className={cn(isCurrentUser && "bg-accent dark:bg-accent/30 dark:hover:bg-accent/50")}
						>
							<TableCell className="text-center">{entry.rank}</TableCell>
							{leaguesEnabled && (
								<TableCell className="px-0">
									<div className="flex flex-col justify-center items-center">
										<LeagueIcon leaguePoints={user.leaguePoints} showPoints />
									</div>
								</TableCell>
							)}
							<TableCell>
								{renderUserLink ? renderUserLink(user.login, userIdentity) : userIdentity}
							</TableCell>
							<TableCell className="text-center font-medium">{entry.score}</TableCell>
							<TableCell>
								<ActivityBadges
									reviewedPullRequests={entry.reviewedPullRequests}
									changeRequests={entry.numberOfChangeRequests}
									approvals={entry.numberOfApprovals}
									comments={entry.numberOfComments}
									codeComments={entry.numberOfCodeComments}
									ownReplies={entry.numberOfOwnReplies}
									openPullRequests={entry.numberOfOpenPullRequests}
									mergedPullRequests={entry.numberOfMergedPullRequests}
									closedPullRequests={entry.numberOfClosedPullRequests}
									openedIssues={entry.numberOfOpenedIssues}
									closedIssues={entry.numberOfClosedIssues}
									providerType={providerType}
									highlightReviews={isCurrentUser}
								/>
							</TableCell>
						</TableRow>
					);
				})}
			</TableBody>
		</Table>
	);
}

function LeaderboardTableSkeleton() {
	return (
		<Table>
			<TableHeader>
				<TableRow>
					<TableHead className="text-center w-16">Rank</TableHead>
					<TableHead className="text-center w-20">League</TableHead>
					<TableHead>Contributor</TableHead>
					<TableHead className="text-center">Score</TableHead>
					<TableHead>Activity</TableHead>
				</TableRow>
			</TableHeader>
			<TableBody>
				{Array.from({ length: 10 }, (_, idx) => `skeleton-${idx}`).map((key, idx) => (
					<TableRow key={key}>
						<TableCell>
							<Skeleton className="h-5 w-7" style={{ width: `${20 + 1 * idx}px` }} />
						</TableCell>
						<TableCell>
							<Skeleton className="h-8 w-8 mx-auto" />
						</TableCell>
						<TableCell className="py-2">
							<div className="flex items-center gap-2">
								<Skeleton className="w-10 h-10 rounded-full" />
								<Skeleton className="h-5" style={{ width: `${100 + (idx % 3) * 75}px` }} />
							</div>
						</TableCell>
						<TableCell className="text-center">
							<Skeleton
								className="h-5 mx-auto"
								style={{ width: `${20 + (10 - idx) + (idx % 3) * 4}px` }}
							/>
						</TableCell>
						<TableCell className="py-2">
							<Skeleton
								className="h-5"
								style={{ width: `${30 + ((idx % 4) * 20) / (idx + 1)}px` }}
							/>
						</TableCell>
					</TableRow>
				))}
			</TableBody>
		</Table>
	);
}
