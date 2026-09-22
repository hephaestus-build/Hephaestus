import type { Workspace } from "@/api/types.gen";
import { firstNonBlank } from "@/lib/text";
import { DEFAULT_SCHEDULE, type LeaderboardSchedule } from "@/lib/timeframe";

type ScheduledWorkspace = Pick<Workspace, "leaderboardScheduleDay" | "leaderboardScheduleTime">;

/**
 * The stored time is free text, so each half of `HH:mm` is defaulted on its own: `"9"` carries no
 * minutes, and a `NaN` minute makes an Invalid Date of every week boundary derived from it.
 */
export function resolveLeaderboardSchedule(
	workspace: ScheduledWorkspace | undefined,
): LeaderboardSchedule {
	if (!workspace) {
		return DEFAULT_SCHEDULE;
	}

	const scheduledTime = firstNonBlank(workspace.leaderboardScheduleTime) ?? "9:00";
	const scheduledDay = workspace.leaderboardScheduleDay ?? 2;
	const [hours = Number.NaN, minutes = Number.NaN] = scheduledTime
		.split(":")
		.map((part) => Number.parseInt(part, 10));

	return {
		day: scheduledDay,
		hour: Number.isNaN(hours) ? 9 : hours,
		minute: Number.isNaN(minutes) ? 0 : minutes,
	};
}
