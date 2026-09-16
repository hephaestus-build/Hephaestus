import { differenceInHours, differenceInMinutes, differenceInSeconds, isPast } from "date-fns";
import { CalendarClock, MoveRight, TrendingDown, TrendingUp } from "lucide-react";
import { useEffect, useState } from "react";

import type { LeaderboardEntry } from "@/api/types.gen";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { getInitials } from "@/lib/avatar";
import { hasText } from "@/lib/text";

import { LeagueInfoDialog } from "./LeagueInfoDialog";
import { LeagueProgressCard } from "./LeagueProgressCard";

export interface LeaderboardOverviewProps {
	leaderboardEntry: LeaderboardEntry;
	leaguePoints: number;
	leaderboardEnd?: string;
	leaguePointsChange?: number;
	leaguesEnabled?: boolean;
}

/** Coarse time left until the leaderboard closes, e.g. "2d 3h", "4h 12m", "58s". */
function formatTimeRemaining(leaderboardEnd: string | undefined, now: Date): string {
	if (!hasText(leaderboardEnd)) {
		return "N/A";
	}

	const endDate = new Date(leaderboardEnd);

	if (isPast(endDate)) {
		return "Ended";
	}

	const diffHours = differenceInHours(endDate, now);
	const diffMinutes = differenceInMinutes(endDate, now) % 60;
	const diffSeconds = differenceInSeconds(endDate, now) % 60;

	if (diffHours > 24) {
		const days = Math.floor(diffHours / 24);
		const hours = diffHours % 24;
		return `${days}d ${hours}h`;
	}

	if (diffHours > 0) {
		return `${diffHours}h ${diffMinutes}m`;
	}

	if (diffMinutes > 0) {
		return `${diffMinutes}m ${diffSeconds}s`;
	}

	return `${diffSeconds}s`;
}

export function LeaderboardOverview({
	leaderboardEntry,
	leaguePoints,
	leaderboardEnd,
	leaguePointsChange = 0,
	leaguesEnabled = true,
}: LeaderboardOverviewProps) {
	const [leagueInfoOpen, setLeagueInfoOpen] = useState(false);
	const { user } = leaderboardEntry;

	// The per-second interval runs only while the label can still change; the render that first sees
	// the deadline pass clears it.
	const [now, setNow] = useState(() => new Date());
	const counting = leaderboardEnd !== undefined && !isPast(new Date(leaderboardEnd));

	useEffect(() => {
		if (!counting) {
			return;
		}
		const timer = setInterval(() => setNow(new Date()), 1000);
		return () => clearInterval(timer);
	}, [counting]);

	const countdown = formatTimeRemaining(leaderboardEnd, now);

	if (!user) {
		return null;
	}

	// Scroll to the user's rank in the leaderboard table
	const scrollToRank = (rank: number) => {
		const element = document.getElementById(`rank-${rank}`);
		if (element) {
			element.scrollIntoView({ behavior: "smooth" });
		}
	};

	return (
		<Card>
			<CardContent>
				<div className="grid grid-cols-1 items-center justify-between gap-4 sm:grid-cols-2 md:grid-cols-3">
					<Button
						variant="ghost"
						className="flex h-full flex-col items-center sm:col-span-2 md:col-span-1"
						onClick={() => scrollToRank(leaderboardEntry.rank)}
					>
						<div className="flex flex-col items-center gap-2">
							<div className="flex items-end gap-2">
								<span>
									<span className="text-3xl font-light text-muted-foreground">#</span>
									<span className="text-4xl font-light text-primary">{leaderboardEntry.rank}</span>
								</span>
								<div className="flex h-10 w-10 items-center justify-center overflow-hidden rounded-full bg-muted">
									{user.avatarUrl ? (
										<img
											src={user.avatarUrl}
											alt={`${user.name}'s avatar`}
											className="h-full w-full object-cover"
										/>
									) : (
										<span className="text-sm font-medium">
											{getInitials(user.name, user.login)}
										</span>
									)}
								</div>
							</div>
							<span className="text-muted-foreground">{user.name}</span>
						</div>
					</Button>

					<div className="flex flex-col items-center gap-1 text-center">
						<div className="flex items-center gap-1">
							<CalendarClock className="h-4 w-4 text-muted-foreground" />
							<span className="text-sm text-muted-foreground">Leaderboard ends in:</span>
						</div>
						<span className="text-xl font-medium">{countdown}</span>
						{leaguesEnabled && (
							<div className="mt-1 flex flex-wrap items-center justify-center gap-1 text-sm">
								<span className="text-muted-foreground">League points change:</span>
								<div className="flex items-center gap-1">
									<span className="font-medium">{leaguePointsChange}</span>
									{leaguePointsChange > 0 ? (
										<TrendingUp className="h-4 w-4" />
									) : leaguePointsChange < 0 ? (
										<TrendingDown className="h-4 w-4" />
									) : (
										<MoveRight className="h-4 w-4" />
									)}
								</div>
							</div>
						)}
					</div>

					{leaguesEnabled && (
						<div className="flex flex-col items-center gap-1 text-center">
							<LeagueProgressCard
								leaguePoints={leaguePoints}
								onInfoClick={() => setLeagueInfoOpen(true)}
							/>
						</div>
					)}
				</div>
			</CardContent>

			{leaguesEnabled && (
				<LeagueInfoDialog open={leagueInfoOpen} onOpenChange={setLeagueInfoOpen} />
			)}
		</Card>
	);
}
