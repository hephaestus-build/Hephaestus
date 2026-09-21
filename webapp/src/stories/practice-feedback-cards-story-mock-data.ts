/**
 * Feedback cards for the stories and the tests, which install no network: what
 * `useInAppFeedback` composes from the wire, written out by hand, on the reviewed work of
 * `practice-profile-story-mock-data`.
 */
import { EyeIcon, MessageCircleIcon, MessageSquareReplyIcon, PackageIcon } from "lucide-react";

import { text, work } from "@/components/common/feedback-text";
import type { PracticeFeedbackCardEntry } from "@/components/practice-vocabulary/PracticeFeedbackCard";

import { conversation, pullRequest } from "./practice-profile-story-mock-data";

const PACKAGING_GROUP = {
	groupSlug: "review-ready-work",
	groupName: "Packaging work for review",
	groupColor: "sky",
	groupIcon: PackageIcon,
} as const;

const CLEAN_CONDITION = [text("Ticks itself once three pieces of work in a row come back clean")];

const SCOPE_ONE_CONCERN = {
	...PACKAGING_GROUP,
	practiceSlug: "scope-one-reviewable-change",
	practiceName: "Scope the change to one concern",
	headline: "Merge requests bundle a fix with a refactor",
	body: "In #19 the fix for the retry loop travelled with a rename of the module it lives in, and #20 carried a dependency bump alongside a behaviour change. Reviewers had to follow two intentions in one diff.",
	nextStep:
		"Next time a fix and a refactor meet in the same branch, open the fix first as its own merge request, let it be reviewed on its own, and put the refactor on top of it once the fix is in. The reviewer then reads one intention at a time.",
	condition: CLEAN_CONDITION,
	cleanNeeded: 3,
	timestamp: "2026-09-09T14:10:00",
} satisfies Partial<PracticeFeedbackCardEntry>;

/** The page's first card: new, three problems in the strip, nothing clean yet. */
export const NEW_FEEDBACK_CARD: PracticeFeedbackCardEntry = {
	feedbackId: "scope-one-concern-new",
	...SCOPE_ONE_CONCERN,
	reviewedWork: [
		{ ref: pullRequest(17), date: "2026-08-28", outcome: "COMMISSION_PROBLEM" },
		{ ref: pullRequest(19), date: "2026-09-06", outcome: "COMMISSION_PROBLEM" },
		{ ref: pullRequest(20), date: "2026-09-03", outcome: "COMMISSION_PROBLEM" },
	],
	cleanWork: [],
	state: "new",
};

/**
 * Most useful first, as the page lists them; the page shows three and reveals the rest on request.
 */
export const OPEN_FEEDBACK_CARDS: PracticeFeedbackCardEntry[] = [
	NEW_FEEDBACK_CARD,
	{
		// A second practice, not a second card for the first one: a practice carries one live card.
		feedbackId: "ready-and-traceable-two-clean",
		...PACKAGING_GROUP,
		practiceSlug: "ready-and-traceable-handoff",
		practiceName: "Mark the change ready and link its issue",
		headline: "Changes were marked ready while still carrying a draft label",
		body: "#17 and #19 were marked ready with a 'do not merge' label still on them, and #20 opened without a link to its issue. Reviewers had to ask whether the change was done and why it existed.",
		nextStep:
			"Before marking a change ready, take the draft label off and add a 'Closes #…' line, so the reviewer knows it is finished and which need it answers.",
		condition: CLEAN_CONDITION,
		cleanNeeded: SCOPE_ONE_CONCERN.cleanNeeded,
		timestamp: SCOPE_ONE_CONCERN.timestamp,
		reviewedWork: [
			{ ref: pullRequest(17), date: "2026-08-28", outcome: "COMMISSION_PROBLEM" },
			{ ref: pullRequest(19), date: "2026-09-06", outcome: "COMMISSION_PROBLEM" },
			{ ref: pullRequest(20), date: "2026-09-03", outcome: "COMMISSION_PROBLEM" },
		],
		cleanWork: [21, 22].map(pullRequest),
		state: "open",
	},
	{
		feedbackId: "describe-what-and-why",
		...PACKAGING_GROUP,
		practiceSlug: "describe-what-and-why",
		practiceName: "Describe what changed and why",
		headline: "Descriptions named the what, rarely the why",
		body: "#16 and #19 listed the files touched but not the problem behind them; the reviewer on #19 asked in the first comment what the change was for.",
		reviewedWork: [
			{ ref: pullRequest(16), date: "2026-08-24", outcome: "OMISSION_GAP" },
			{ ref: pullRequest(19), date: "2026-09-06", outcome: "OMISSION_GAP" },
		],
		cleanWork: [pullRequest(20)],
		nextStep: "Before the file list, write one paragraph on the problem and the decision you took.",
		condition: CLEAN_CONDITION,
		cleanNeeded: 3,
		state: "open",
		timestamp: "2026-09-09T14:10:00",
	},
	{
		feedbackId: "reviewable-diff-size",
		...PACKAGING_GROUP,
		practiceSlug: "reviewable-diff-size",
		practiceName: "Keep the diff reviewable in one sitting",
		headline: "Two merge requests grew past what one review can hold",
		body: "#21 touched 41 files and #22 38; both went through two rounds of review, and the second round of each reopened a file the first had already approved.",
		reviewedWork: [
			{
				ref: pullRequest(19),
				date: "2026-09-06",
				outcome: "DEMONSTRATED_STRENGTH",
			},
			{ ref: pullRequest(21), date: "2026-09-07", outcome: "COMMISSION_PROBLEM" },
			{ ref: pullRequest(22), date: "2026-09-09", outcome: "COMMISSION_PROBLEM" },
		],
		// A strength before the problems: the clean work it started was emptied, so nothing is clean
		// since.
		cleanWork: [],
		nextStep:
			"When a branch passes twenty files, stop and split it: land the shared groundwork first, then each behaviour change on top of it as its own merge request.",
		condition: CLEAN_CONDITION,
		cleanNeeded: 3,
		state: "open",
		timestamp: "2026-09-09T14:10:00",
	},
	{
		// The third of the three examples the cards are read against: the review dialogue, as the
		// author of the change. A practice from another group, so the page shows one.
		feedbackId: "unresolved-review-threads",
		groupSlug: "acting-on-review-feedback",
		groupName: "Acting on review feedback",
		groupColor: "cyan",
		groupIcon: MessageSquareReplyIcon,
		practiceSlug: "merged-past-unresolved-review-threads",
		practiceName: "Resolve open threads before merging",
		headline: "Merge requests were merged over open review threads",
		body: "#17 merged with two threads still open, and on #20 the reviewer's question about the retry limit was never answered; the reviewer had to reopen it in the next review.",
		reviewedWork: [
			{ ref: pullRequest(17), date: "2026-08-28", outcome: "COMMISSION_PROBLEM" },
			{ ref: pullRequest(20), date: "2026-09-03", outcome: "COMMISSION_PROBLEM" },
		],
		cleanWork: [21, 22].map(pullRequest),
		nextStep:
			"Before you merge, answer every open thread with a commit or a sentence and resolve it, so the reviewer sees what became of each comment.",
		condition: CLEAN_CONDITION,
		cleanNeeded: 3,
		state: "open",
		timestamp: "2026-09-04T09:32:00",
	},
	{
		feedbackId: "acceptance-criteria",
		...PACKAGING_GROUP,
		practiceSlug: "honours-linked-issue-acceptance-criteria",
		practiceName: "Say which acceptance criteria are done",
		headline: "Merge requests closed their issue without saying what was met",
		body: "#16 and #19 each closed an issue with three acceptance criteria and mentioned none of them; #13 was reopened a week later for the criterion the change had skipped.",
		reviewedWork: [
			{ ref: pullRequest(16), date: "2026-08-24", outcome: "OMISSION_GAP" },
			{ ref: pullRequest(19), date: "2026-09-06", outcome: "OMISSION_GAP" },
		],
		cleanWork: [pullRequest(22)],
		nextStep:
			"Copy the issue's acceptance criteria into the description and tick the ones the change meets, so the reviewer and the issue's author see the same list.",
		condition: CLEAN_CONDITION,
		cleanNeeded: 3,
		state: "open",
		timestamp: "2026-09-02T16:45:00",
	},
];

/**
 * The resolved feedback, newest first, as the "Resolved feedback" level lists it: two the work
 * resolved, whose condition names the clean pieces, and one the reader marked addressed.
 */
export const RESOLVED_FEEDBACK_CARDS: PracticeFeedbackCardEntry[] = [
	{
		feedbackId: "describe-what-and-why-resolved",
		...PACKAGING_GROUP,
		practiceSlug: "describe-what-and-why",
		practiceName: "Describe what changed and why",
		headline: "Descriptions named the what, rarely the why",
		body: "#16 and #19 listed the files touched but not the problem behind them; the reviewer on #19 asked in the first comment what the change was for.",
		reviewedWork: [{ ref: pullRequest(19), date: "2026-09-06", outcome: "OMISSION_GAP" }],
		cleanWork: [20, 21, 22].map(pullRequest),
		nextStep: "Before the file list, write one paragraph on the problem and the decision you took.",
		condition: [
			text("Resolved by the work on 9 September · "),
			work(pullRequest(20)),
			text(", "),
			work(pullRequest(21)),
			text(" and "),
			work(pullRequest(22)),
			text(" came back clean"),
		],
		cleanNeeded: 3,
		state: "resolved",
		timestamp: "2026-09-09T14:10:00",
	},
	{
		feedbackId: "review-comments-specific-resolved",
		groupSlug: "constructive-code-review",
		groupName: "Reviewing a teammate's work constructively",
		groupColor: "teal",
		groupIcon: EyeIcon,
		practiceSlug: "leaves-useful-specific-review-comments",
		practiceName: "Leave specific, actionable review comments",
		headline: "Review comments said something was off, not what",
		body: 'On #2 the comments read "this looks wrong" and "can we do better here?", and the author replied to each one asking what to change.',
		reviewedWork: [{ ref: pullRequest(2), date: "2026-08-20", outcome: "COMMISSION_PROBLEM" }],
		cleanWork: [1, 4, 23].map(pullRequest),
		nextStep:
			"Name the line, say what is wrong with it and what you would do instead, so the author can act on the comment without asking back.",
		condition: [
			text("Resolved by the work on 2 September · "),
			work(pullRequest(1)),
			text(", "),
			work(pullRequest(4)),
			text(" and "),
			work(pullRequest(23)),
			text(" came back clean"),
		],
		cleanNeeded: 3,
		state: "resolved",
		timestamp: "2026-09-02T11:40:00",
	},
	{
		feedbackId: "status-updates-resolved",
		groupSlug: "communication",
		groupName: "Communicating in the open",
		groupColor: "violet",
		groupIcon: MessageCircleIcon,
		practiceSlug: "posts-clear-status-and-blocker-updates",
		practiceName: "Post clear status and blocker updates",
		headline: "Blockers surfaced in stand-up, not in the channel",
		body: "In #backend-review the runner outage that held the release for two days was first mentioned in the next morning's stand-up; the thread on it stayed silent.",
		reviewedWork: [
			{
				ref: conversation("#backend-review"),
				date: "2026-08-12",
				outcome: "OMISSION_GAP",
			},
		],
		// The one the reader resolved, before the work did: the clean work stays where the work
		// left it.
		cleanWork: [conversation("#releases"), conversation("#incidents")],
		nextStep:
			"When something blocks you for more than an hour, post it in the channel the work lives in, with what you have tried and what you need.",
		condition: [text("Marked as addressed on 27 August")],
		cleanNeeded: 3,
		state: "resolved",
		timestamp: "2026-08-27T16:05:00",
	},
];

/** Open and resolved together, as the route hands the page every readable card. */
export const ALL_FEEDBACK_CARDS: PracticeFeedbackCardEntry[] = [
	...OPEN_FEEDBACK_CARDS,
	...RESOLVED_FEEDBACK_CARDS,
];
