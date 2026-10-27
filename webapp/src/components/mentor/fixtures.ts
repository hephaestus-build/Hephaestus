import type { MentorEntry, MentorTurn, WeeklyDigest, WeeklyDigestFeedback } from "@/api/types.gen";
import type { ReadyTimeline } from "@/components/mentor/HephTimeline";
import { daysBefore, earlierToday, minutesBefore } from "@/stories/story-clock";

import type { MessageAuthor } from "./HephMessage";

export const DEVELOPER: MessageAuthor = { name: "Ada Lovelace" };

export const WORKSPACE_SLUG = "aet";

const CONVERSATION_ID = "0a7c4f3e-6b1d-4e25-9c8a-1f2e3d4c5b6a";

let seq = 0;

export function entry(
	overrides: Partial<MentorEntry> & Pick<MentorEntry, "id" | "kind" | "text">,
): MentorEntry {
	seq += 1;
	return {
		conversationId: CONVERSATION_ID,
		seq,
		surface: "APP",
		createdAt: minutesBefore(30),
		deleted: false,
		links: [],
		...overrides,
	};
}

export const QUESTION = entry({
	id: "e1000000-0000-4000-8000-000000000001",
	kind: "USER",
	text: "Why did I get feedback on how I describe my pull requests?",
	turnId: "t1000000-0000-4000-8000-000000000001",
	createdAt: daysBefore(1),
});

export const ANSWER = entry({
	id: "e1000000-0000-4000-8000-000000000002",
	kind: "HEPH",
	text: [
		"Your team asks for pull request descriptions that **explain significant decisions**. A reviewer can read *what* changed from the diff, but not *why* this approach won.",
		"",
		"In your last three pull requests, the descriptions list the changes but leave the reasons out.",
	].join("\n"),
	turnId: QUESTION.turnId,
	createdAt: new Date(daysBefore(1).getTime() + 20_000),
	vote: "HELPFUL",
});

export const SLACK_QUESTION = entry({
	id: "e1000000-0000-4000-8000-000000000003",
	kind: "USER",
	surface: "SLACK",
	text: "Can you show me what a better description looks like?",
	turnId: "t1000000-0000-4000-8000-000000000003",
	createdAt: minutesBefore(70),
	thread: {
		conversationId: "0a7c4f3e-6b1d-4e25-9c8a-000000000002",
		parentEntryId: "e1000000-0000-4000-8000-000000000003",
		replyCount: 3,
		// An age whose phrase, "about 1 hour ago", holds while a story run lasts.
		lastReplyAt: minutesBefore(50),
		latestSeq: 3,
	},
});

export const SLACK_ANSWER = entry({
	id: "e1000000-0000-4000-8000-000000000004",
	kind: "HEPH",
	surface: "SLACK",
	text: [
		"Here is one way to shape it:",
		"",
		"## Why",
		"",
		"Loading the catalog on every request made the practices page slow for large workspaces.",
		"",
		"## Decision",
		"",
		"- Cache the catalog for **five minutes**.",
		"- Invalidating on write was simpler to reason about, but three services write to it.",
		"",
		"```ts",
		"const catalog = await cache.get(workspaceId, loadCatalog, { ttl: minutes(5) });",
		"```",
	].join("\n"),
	turnId: SLACK_QUESTION.turnId,
	createdAt: minutesBefore(69),
});

export const FOLLOW_UP = entry({
	id: "e1000000-0000-4000-8000-000000000005",
	kind: "USER",
	text: "And my latest one?",
	turnId: "t1000000-0000-4000-8000-000000000005",
	requestId: "r1000000-0000-4000-8000-000000000005",
	createdAt: earlierToday(5),
});

export const ANSWER_WITH_FEEDBACK = entry({
	id: "e1000000-0000-4000-8000-000000000006",
	kind: "HEPH",
	text: "I looked at your latest pull request, **Cache the practice catalog**. What made you pick the time-based cache?",
	turnId: FOLLOW_UP.turnId,
	createdAt: earlierToday(4),
	// As the server sends them: a problem's practice and work through its result, feedback's work and
	// its practice when it is about one, and neither once the developer can no longer see the work.
	links: [
		{
			id: "3f0c2b4e-8a1d-4c6e-9b7f-2d5e8a1c4b6f",
			kind: "PROBLEM",
			text: "Your description names **the decision**, a five-minute cache, but not why it beat invalidating on write.",
			practiceSlug: "explain-decisions",
			itemId: 421,
		},
		{
			id: "8b2e4d6f-1a3c-4e5b-9d7f-0c2e4a6b8d1f",
			kind: "FEEDBACK",
			text: "Before you merge, add one line on why the cache beats invalidating on write.",
			itemId: 433,
		},
		{
			id: "5d7f9b1c-3e5a-4c7e-8f1b-2d4f6a8c0e3b",
			kind: "RESULT",
			text: "Your earlier pull request explained its decision well.",
		},
	],
});

/** The server's refusal of a message while Heph has too many waiting. */
export const FOLLOW_UPS_FULL =
	"Heph is still answering. Wait for an answer before you send more messages.";

/** A follow-up whose turn failed: the question carries why. */
export const FAILED_FOLLOW_UP: MentorEntry = {
	...FOLLOW_UP,
	turnStatus: "FAILED",
	turnError: "Heph reached its time limit and stopped this reply. Try again.",
};

/** Monday 5 to Sunday 11 October 2026 in Berlin: the week the digest fixtures read. */
const WEEK = {
	weekStart: new Date("2026-10-05"),
	windowStart: new Date("2026-10-04T22:00:00Z"),
	windowEnd: new Date("2026-10-11T22:00:00Z"),
	timeZone: "Europe/Berlin",
} as const;

const ON_THE_WORK: WeeklyDigestFeedback = {
	feedbackId: "f1000000-0000-4000-8000-000000000001",
	practiceSlug: "explain-decisions",
	practiceName: "Explain significant decisions",
	groupSlug: "pull-requests",
	groupName: "Pull requests",
	destination: "ON_WORK",
	item: {
		id: "421",
		kind: "scm.pull_request",
		provider: "GITHUB",
		label: "#421",
		title: "Cache the practice catalog",
		container: "ls1intum/Hephaestus",
		parentId: null,
		path: null,
		removedAt: null,
		url: "https://github.com/ls1intum/Hephaestus/pull/421",
	},
	deliveredAt: new Date("2026-10-06T09:12:00Z"),
	withdrawn: false,
	replaced: false,
};

// The server never lists Practice page feedback in a digest; its waiting count covers those cards.
const ON_OTHER_WORK: WeeklyDigestFeedback = {
	feedbackId: "f1000000-0000-4000-8000-000000000002",
	practiceSlug: "link-issues",
	practiceName: "Link the issue the change resolves",
	groupSlug: "pull-requests",
	groupName: "Pull requests",
	destination: "ON_WORK",
	item: {
		id: "433",
		kind: "scm.pull_request",
		provider: "GITHUB",
		label: "#433",
		title: "Retry failed deliveries",
		container: "ls1intum/Hephaestus",
		parentId: null,
		path: null,
		removedAt: null,
		url: "https://github.com/ls1intum/Hephaestus/pull/433",
	},
	deliveredAt: new Date("2026-10-07T14:30:00Z"),
	withdrawn: false,
	replaced: false,
};

/** Withdrawn feedback names no work: the server sends neither its item nor its message. */
const WITHDRAWN_ON_WORK: WeeklyDigestFeedback = {
	feedbackId: "f1000000-0000-4000-8000-000000000004",
	practiceSlug: "link-issues",
	practiceName: "Link the issue the change resolves",
	groupSlug: "pull-requests",
	groupName: "Pull requests",
	destination: "ON_WORK",
	deliveredAt: new Date("2026-10-07T14:30:00Z"),
	withdrawn: true,
	replaced: false,
};

const IN_THE_CONVERSATION: WeeklyDigestFeedback = {
	feedbackId: "f1000000-0000-4000-8000-000000000003",
	practiceSlug: "share-blockers",
	practiceName: "Share blockers early",
	destination: "CONVERSATION",
	mentorEntryId: ANSWER_WITH_FEEDBACK.id,
	deliveredAt: new Date("2026-10-08T10:00:00Z"),
	withdrawn: false,
	replaced: false,
};

/** A week in which feedback reached the developer on their work and in conversation, their profile moved, and cards wait. */
export const WEEKLY_DIGEST: WeeklyDigest = {
	...WEEK,
	feedback: [ON_THE_WORK, ON_OTHER_WORK, IN_THE_CONVERSATION],
	changes: [
		{
			type: "STANDING_MOVED",
			at: new Date("2026-10-09T08:00:00Z"),
			practiceSlug: "explain-decisions",
			practiceName: "Explain significant decisions",
			groupSlug: "pull-requests",
			groupName: "Pull requests",
			from: "DEVELOPING",
			to: "STRENGTH",
			direction: "UP",
			evidence: [],
			cleanNeeded: null,
			feedbackId: null,
			resolvedBy: null,
		},
	],
	waiting: 2,
};

/** A week whose only news is unread cards on the Practice profile. */
export const WAITING_ONLY_DIGEST: WeeklyDigest = { ...WEEK, feedback: [], changes: [], waiting: 1 };

/**
 * Nothing left to show: the developer has read the week's Practice page cards since, or its
 * feedback no longer shows. A withdrawal does not empty a digest; it stays as a withdrawn row.
 */
export const NOTHING_TO_SHOW_DIGEST: WeeklyDigest = {
	...WEEK,
	feedback: [],
	changes: [],
	waiting: 0,
};

/** A workspace admin withdrew one piece of feedback, and newer feedback replaced a comment. */
export const WITHDRAWN_DIGEST: WeeklyDigest = {
	...WEEK,
	feedback: [{ ...ON_THE_WORK, replaced: true }, WITHDRAWN_ON_WORK],
	changes: [],
	waiting: 0,
};

export const DIGEST = entry({
	id: "e1000000-0000-4000-8000-000000000007",
	kind: "DIGEST",
	surface: "SYSTEM",
	text: "Weekly digest for 5–11 October 2026. Read inputs/context/weekly_digest.json for it.",
	createdAt: earlierToday(3),
	digest: WEEKLY_DIGEST,
});

export const CONVERSATION: MentorEntry[] = [
	QUESTION,
	ANSWER,
	SLACK_QUESTION,
	SLACK_ANSWER,
	FOLLOW_UP,
	ANSWER_WITH_FEEDBACK,
];

export function turn(
	overrides: Partial<MentorTurn> & Pick<MentorTurn, "id" | "status">,
): MentorTurn {
	return {
		conversationId: CONVERSATION_ID,
		requestId: `request-${overrides.id}`,
		surface: "APP",
		createdAt: minutesBefore(1),
		attempt: overrides.status === "QUEUED" ? 0 : 1,
		...overrides,
	};
}

export function ready(overrides: Partial<ReadyTimeline> = {}): ReadyTimeline {
	return {
		status: "ready",
		entries: CONVERSATION,
		pending: [],
		turns: [],
		earlier: { status: "none" },
		...overrides,
	};
}
