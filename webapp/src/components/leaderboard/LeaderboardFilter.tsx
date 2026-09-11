import { SlidersHorizontal } from "lucide-react";
import type { LeaderboardSchedule } from "@/lib/timeframe";

import type { LeaderboardVariant } from "@/components/leaderboard/LeaderboardPage";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Label } from "@/components/ui/label";
import { Switch } from "@/components/ui/switch";

import { type LeaderboardSortType, SortFilter } from "./SortFilter";
import { TeamFilter, type TeamFilterOption } from "./TeamFilter";
import { TimeframeFilter } from "./TimeframeFilter";

export interface LeaderboardFilterProps {
	teamOptions: TeamFilterOption[];
	onTeamChange?: (team: string) => void;
	onSortChange?: (sort: LeaderboardSortType) => void;
	onTimeframeChange?: (afterDate: string, beforeDate?: string, timeframe?: string) => void;
	selectedTeam?: string;
	selectedSort?: LeaderboardSortType;
	afterDate?: string;
	beforeDate?: string;
	leaderboardSchedule?: LeaderboardSchedule;
	selectedMode: LeaderboardVariant;
	onModeChange?: (mode: LeaderboardVariant) => void;
	leaguesEnabled?: boolean;
}

export function LeaderboardFilter({
	teamOptions,
	onTeamChange,
	onSortChange,
	onTimeframeChange,
	selectedTeam,
	selectedSort,
	afterDate,
	beforeDate,
	leaderboardSchedule,
	selectedMode,
	onModeChange,
	leaguesEnabled = true,
}: LeaderboardFilterProps) {
	return (
		<Card>
			<CardHeader>
				<CardTitle>
					<SlidersHorizontal className="inline mr-2 h-4 w-4" /> Leaderboard Options
				</CardTitle>
				<CardDescription>
					Customize the leaderboard view by filtering and sorting the data.
				</CardDescription>
			</CardHeader>
			<CardContent>
				<div className="flex items-center space-x-2 mb-4">
					<Switch
						id="mode-switch"
						checked={selectedMode === "TEAM"}
						onCheckedChange={(checked) => onModeChange?.(checked ? "TEAM" : "INDIVIDUAL")}
					/>
					<Label htmlFor="mode-switch">Team Leaderboard</Label>
				</div>
				<div className="space-y-4">
					{selectedMode === "INDIVIDUAL" && (
						<TeamFilter
							options={teamOptions}
							onTeamChange={onTeamChange}
							selectedTeam={selectedTeam}
						/>
					)}
					{selectedMode === "INDIVIDUAL" && (
						<SortFilter
							onSortChange={onSortChange}
							selectedSort={selectedSort}
							leaguesEnabled={leaguesEnabled}
						/>
					)}
					<TimeframeFilter
						onTimeframeChange={onTimeframeChange}
						afterDate={afterDate}
						beforeDate={beforeDate}
						leaderboardSchedule={leaderboardSchedule}
					/>
				</div>
			</CardContent>
		</Card>
	);
}
