import { Trophy } from "lucide-react";
import type { ReactNode } from "react";
import type { LeaderboardSchedule } from "@/lib/timeframe";

import type { LeaderboardEntry, UserInfo } from "@/api/types.gen";
import { PageHeader } from "@/components/core/PageHeader";
import { PageLayout } from "@/components/core/PageLayout";
import type { ProviderType } from "@/lib/provider";

import { LeaderboardFilter } from "./LeaderboardFilter";
import { LeaderboardLegend } from "./LeaderboardLegend";
import { LeaderboardOverview } from "./LeaderboardOverview";
import { LeaderboardTable } from "./LeaderboardTable";
import type { LeaderboardSortType } from "./SortFilter";

export type LeaderboardVariant = "INDIVIDUAL" | "TEAM";

interface LeaderboardPageProps {
	providerType?: ProviderType;
	leaderboard?: LeaderboardEntry[];
	isLoading: boolean;
	currentUser?: UserInfo;
	currentUserEntry?: LeaderboardEntry;
	leaguePoints?: number;
	leaguePointsChange?: number;
	teamOptions: { value: string; label: string }[];
	teamLabelsById?: Record<number, string>;
	onTeamChange?: (team: string) => void;
	onSortChange?: (sort: LeaderboardSortType) => void;
	onTimeframeChange?: (afterDate: string, beforeDate?: string, timeframe?: string) => void;
	renderUserLink?: (username: string, children: ReactNode) => ReactNode;
	selectedTeam?: string;
	selectedSort?: LeaderboardSortType;
	afterDate?: string;
	beforeDate?: string;
	leaderboardEnd?: string;
	leaderboardSchedule?: LeaderboardSchedule;
	selectedMode: LeaderboardVariant;
	onModeChange?: (mode: LeaderboardVariant) => void;
	renderTeamLink?: (teamId: number, children: ReactNode) => ReactNode;
	leaguesEnabled?: boolean;
}

export function LeaderboardPage({
	providerType = "GITHUB",
	leaderboard,
	isLoading,
	currentUser,
	currentUserEntry,
	leaguePoints = 0,
	leaguePointsChange = 0,
	teamOptions,
	teamLabelsById,
	onTeamChange,
	onSortChange,
	onTimeframeChange,
	renderUserLink,
	selectedTeam,
	selectedSort,
	afterDate,
	beforeDate,
	leaderboardEnd,
	leaderboardSchedule,
	selectedMode,
	onModeChange,
	renderTeamLink,
	leaguesEnabled = true,
}: LeaderboardPageProps) {
	return (
		<PageLayout>
			<PageHeader
				icon={<Trophy />}
				title="Leaderboard"
				description="Compare code review activity across contributors and teams."
			/>
			<div className="min-w-0">
				<div className="grid min-w-0 grid-cols-1 gap-y-4 xl:grid-cols-4 xl:gap-4">
					<div className="col-span-1 min-w-0 space-y-4">
						<div className="xl:sticky xl:top-4 xl:self-start xl:max-h-[calc(100vh-2rem)] xl:overflow-auto">
							<LeaderboardFilter
								selectedMode={selectedMode}
								onModeChange={onModeChange}
								teamOptions={teamOptions}
								onTeamChange={onTeamChange}
								onSortChange={onSortChange}
								onTimeframeChange={onTimeframeChange}
								selectedTeam={selectedTeam}
								selectedSort={selectedSort}
								afterDate={afterDate}
								beforeDate={beforeDate}
								leaderboardSchedule={leaderboardSchedule}
								leaguesEnabled={leaguesEnabled}
							/>
						</div>
					</div>

					<div className="col-span-2 min-w-0 space-y-4">
						{currentUserEntry && (
							<LeaderboardOverview
								leaderboardEntry={currentUserEntry}
								leaguePoints={leaguePoints}
								leaderboardEnd={leaderboardEnd}
								leaguePointsChange={leaguePointsChange}
								leaguesEnabled={leaguesEnabled}
							/>
						)}

						<div className="border rounded-md border-input overflow-auto">
							<LeaderboardTable
								leaderboard={leaderboard}
								isLoading={isLoading}
								variant={selectedMode}
								currentUser={currentUser}
								renderUserLink={renderUserLink}
								renderTeamLink={renderTeamLink}
								teamLabelsById={teamLabelsById}
								providerType={providerType}
								leaguesEnabled={leaguesEnabled}
							/>
						</div>
					</div>

					<div className="col-span-1 min-w-0 xl:sticky xl:top-4 xl:max-h-[calc(100vh-2rem)] xl:self-start xl:overflow-auto">
						<LeaderboardLegend providerType={providerType} />
					</div>
				</div>
			</div>
		</PageLayout>
	);
}
