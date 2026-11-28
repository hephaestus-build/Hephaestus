import { hoursBefore, minutesBefore } from "@/stories/story-clock";

import type {
	TeamChoicePoint,
	TeamCitation,
	TeamMemberMessageData,
	TeamMemberRef,
	TeamMentorMessageData,
	TeamRevisit,
} from "./team-place-entries";
import type { TeamThreadData } from "./team-threads";

/** Synthetic people and work only. */
export const YOU: TeamMemberRef = {
	accountId: 1,
	displayName: "Ada Lovelace",
	you: true,
	former: false,
};
export const TEAMMATE: TeamMemberRef = {
	accountId: 2,
	displayName: "Alan Turing",
	you: false,
	former: false,
};
export const FORMER_MEMBER: TeamMemberRef = { you: false, former: true };

const VERSION_1 = "3b0f6a52-1c1e-4d7a-9a51-0a5c2f1d0001";
const SNAPSHOT = "3b0f6a52-1c1e-4d7a-9a51-0a5c2f1d0101";

export const CITATIONS: readonly TeamCitation[] = [
	{
		observationId: "6c1d2e3f-0000-4000-8000-000000000041",
		anchor: {
			versionId: VERSION_1,
			versionSequence: 1,
			snapshotId: SNAPSHOT,
			elementId: "12:40",
			label: "Students × Define",
		},
		available: true,
	},
	{
		observationId: "6c1d2e3f-0000-4000-8000-000000000041",
		anchor: {
			versionId: VERSION_1,
			versionSequence: 1,
			snapshotId: SNAPSHOT,
			elementId: "12:44",
			label: "Students × Ideate",
		},
		available: true,
	},
];

/** As the server sends a citation whose source is gone: not available, with no anchor. */
export const UNAVAILABLE_CITATION: TeamCitation = {
	observationId: "6c1d2e3f-0000-4000-8000-000000000042",
	available: false,
};

export const QUESTION: TeamMemberMessageData = {
	id: "a1000000-0000-4000-8000-000000000001",
	author: TEAMMATE,
	text: "Which user need should we take into the next version first?",
	createdAt: hoursBefore(2),
	deleted: false,
};

export const ANSWER: TeamMentorMessageData = {
	id: "a1000000-0000-4000-8000-000000000002",
	kind: "ANSWER",
	status: "COMPOSED",
	body: [
		"The review found two needs from your interviews that no idea answers yet.",
		"",
		"One option: start with the need in **Students × Define**, because three of your interview notes name it.",
	].join("\n"),
	citations: CITATIONS,
	revisits: [],
	answerUse: { observations: 2, decisions: 1, messages: 3 },
	createdAt: minutesBefore(110),
};

export const CHOICE_POINT: TeamChoicePoint = {
	problemId: "p1000000-0000-4000-8000-000000000001",
	options: [
		{ id: "o1", text: "Verify the current step-free route to the return point." },
		{ id: "o2", text: "Compare another return point that has a documented route." },
	],
	draftNote:
		"We will check the step-free route to the return point, because the map currently assumes it exists. Next review: look for a route observation or an explicit unknown.",
	citations: CITATIONS.slice(0, 1),
};

export const REVISIT: TeamRevisit = {
	decisionId: "p1000000-0000-4000-8000-000000000001",
	decisionRecordId: "d1000000-0000-4000-8000-000000000002",
	finding: "CHANGE_SHOWN",
	statement: "The route is now documented. The step-free entrance is still unverified.",
	citations: CITATIONS.slice(1),
};

export const REVIEW_THREAD: TeamThreadData = {
	id: "t1000000-0000-4000-8000-000000000001",
	title: "Journey map, version 1",
	review: { itemTitle: "Journey map", versionSequence: 1, versionState: "AVAILABLE" },
	lastActivityAt: minutesBefore(110),
};

export const LATER_REVIEW_THREAD: TeamThreadData = {
	id: "t1000000-0000-4000-8000-000000000002",
	title: "Journey map, version 2",
	review: { itemTitle: "Journey map", versionSequence: 2, versionState: "AVAILABLE" },
	lastActivityAt: minutesBefore(20),
};
