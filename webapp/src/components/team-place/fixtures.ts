import { hoursBefore, minutesBefore } from "@/stories/story-clock";

import type {
	TeamCitation,
	TeamMemberMessageData,
	TeamMemberRef,
	TeamMentorMessageData,
} from "./team-place-entries";

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
	answerUse: { observations: 2, decisions: 1, messages: 3 },
	createdAt: minutesBefore(110),
};
