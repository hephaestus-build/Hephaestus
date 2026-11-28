import type { TeamDecisionState } from "@/components/practice-vocabulary/team-decision-state-defs";

import type { TeamMemberRef } from "./team-place-entries";

/** One record of a team decision on one problem. A later record supersedes it; nothing edits it. */
export interface TeamDecisionData {
	id: string;
	problemId: string;
	problemSummary: string;
	practiceName: string;
	versionId: string;
	versionSequence: number;
	state: TeamDecisionState;
	/** The note in the recording member's words. */
	note?: string;
	decidedBy: TeamMemberRef;
	decidedAt: Date;
	supersedesId?: string;
}

/** A problem's current decision and the records it superseded, newest first. */
export interface ProblemDecisions {
	current: TeamDecisionData;
	earlier: readonly TeamDecisionData[];
}

/**
 * Groups records by problem. The current record is the newest one, and problems are ordered by it, newest first. Every
 * current member reads the current record of each problem, whatever their join date.
 */
export function decisionsByProblem(records: readonly TeamDecisionData[]): ProblemDecisions[] {
	const byProblem = new Map<string, TeamDecisionData[]>();
	for (const record of records) {
		const group = byProblem.get(record.problemId);
		if (group === undefined) {
			byProblem.set(record.problemId, [record]);
		} else {
			group.push(record);
		}
	}
	const problems: ProblemDecisions[] = [];
	for (const group of byProblem.values()) {
		const [current, ...earlier] = [...group].sort(
			(a, b) => b.decidedAt.getTime() - a.decidedAt.getTime(),
		);
		if (current !== undefined) {
			problems.push({ current, earlier });
		}
	}
	return problems.sort((a, b) => b.current.decidedAt.getTime() - a.current.decidedAt.getTime());
}
