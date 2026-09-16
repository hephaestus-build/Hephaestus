import { NoEntryIcon } from "@primer/octicons-react";
import { AwardIcon } from "lucide-react";
import type { ReactNode } from "react";

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
	leaderboard?: readonly LeaderboardEntry[] | readonly TeamLeaderboardEntry[];
	isLoading: boolean;
	variant: LeaderboardVariant;
	currentUser?: UserInfo;
	renderUserLink?: (username: string, children: ReactNode) => ReactNode;
	renderTeamLink?: (teamId: number, children: ReactNode) => ReactNode;
	teamLabelsById?: Record<number, string>;
	providerType?: ProviderType;
	leaguesEnabled?: boolean;
}

const NO_ENTRIES: readonly LeaderboardEntry[] = [];

export function LeaderboardTable({
	leaderboard = NO_ENTRIES,
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
				<NoEntryIcon className="mb-2 h-12 w-12 text-provider-danger-foreground" />
				<h2 className="text-lg font-medium">No entries found</h2>
				<p className="text-muted-foreground">There are no leaderboard entries available.</p>
			</div>
		);
	}

	const isTeam = variant === "TEAM";
	const entries: readonly LeaderboardEntry[] = leaderboard;

	return (
		<Table>
			<TableHeader>
				<TableRow>
					<TableHead className="w-10 text-center">Rank</TableHead>
					{!isTeam && leaguesEnabled && <TableHead className="w-20 text-center">League</TableHead>}
					<TableHead className="w-56">{isTeam ? "Team" : "Contributor"}</TableHead>
					<TableHead className="text-center">
						<div className="flex items-center justify-center gap-1 text-provider-done-foreground">
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
						const { team } = entry;
						if (!team) {
							return null;
						}
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

					const { user } = entry;
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
							variant={isCurrentUser ? "highlighted" : "default"}
						>
							<TableCell className="text-center">{entry.rank}</TableCell>
							{leaguesEnabled && (
								<TableCell className="px-0">
									<div className="flex flex-col items-center justify-center">
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
					<TableHead className="w-16 text-center">Rank</TableHead>
					<TableHead className="w-20 text-center">League</TableHead>
					<TableHead>Contributor</TableHead>
					<TableHead className="text-center">Score</TableHead>
					<TableHead>Activity</TableHead>
				</TableRow>
			</TableHeader>
			<TableBody>
				{Array.from({ length: 10 }, (_, idx) => `skeleton-${idx}`).map((key) => (
					<TableRow key={key}>
						<TableCell>
							<Skeleton className="h-5 w-7" />
						</TableCell>
						<TableCell>
							<Skeleton className="mx-auto h-8 w-8" />
						</TableCell>
						<TableCell className="py-2">
							<div className="flex items-center gap-2">
								<Skeleton className="h-10 w-10 rounded-full" />
								<Skeleton className="h-5 w-40" />
							</div>
						</TableCell>
						<TableCell className="text-center">
							<Skeleton className="mx-auto h-5 w-8" />
						</TableCell>
						<TableCell className="py-2">
							<Skeleton className="h-5 w-10" />
						</TableCell>
					</TableRow>
				))}
			</TableBody>
		</Table>
	);
}
