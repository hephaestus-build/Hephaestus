import { daysBefore, hoursBefore } from "@/stories/story-clock";

import { FORMER_MEMBER, TEAMMATE, YOU } from "./fixtures";
import type { ProblemDecisions, TeamDecisionData } from "./team-decisions";

/** Synthetic decisions on one journey map, after the Learning Fund fixture. */
export const OPEN_RECORD: TeamDecisionData = {
	id: "d1000000-0000-4000-8000-000000000001",
	problemId: "p1000000-0000-4000-8000-000000000001",
	problemSummary: "The map assumes a step-free route to the return point, with no evidence for it.",
	practiceName: "Claims rest on evidence",
	versionId: "3b0f6a52-1c1e-4d7a-9a51-0a5c2f1d0001",
	versionSequence: 1,
	state: "OPEN",
	decidedBy: TEAMMATE,
	decidedAt: daysBefore(2),
};

export const ACTED_ON_RECORD: TeamDecisionData = {
	...OPEN_RECORD,
	id: "d1000000-0000-4000-8000-000000000002",
	state: "ACTED_ON",
	note: "We will check the step-free route to the return point, because the map currently assumes it exists. Next review: look for a route observation or an explicit unknown.",
	decidedBy: YOU,
	decidedAt: hoursBefore(5),
	supersedesId: OPEN_RECORD.id,
};

export const DISMISSED_RECORD: TeamDecisionData = {
	id: "d1000000-0000-4000-8000-000000000003",
	problemId: "p1000000-0000-4000-8000-000000000002",
	problemSummary: "Two user needs from the interviews have no matching idea in the Ideate phase.",
	practiceName: "Research findings reach the design",
	versionId: "3b0f6a52-1c1e-4d7a-9a51-0a5c2f1d0001",
	versionSequence: 1,
	state: "DISMISSED",
	note: "Out of scope for this course: both needs concern the canteen, which our brief excludes.",
	decidedBy: TEAMMATE,
	decidedAt: hoursBefore(3),
};

export const ACTED_ON_DECISIONS: ProblemDecisions = {
	current: ACTED_ON_RECORD,
	earlier: [OPEN_RECORD],
};

export const FORMER_MEMBER_RECORD: TeamDecisionData = {
	...DISMISSED_RECORD,
	decidedBy: FORMER_MEMBER,
};
