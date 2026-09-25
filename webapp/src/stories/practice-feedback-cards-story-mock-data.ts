/**
 * Feedback cards for the stories and the tests, which install no network: what
 * `useInAppFeedback` composes from the wire, written out by hand, on the reviewed work of
 * `practice-profile-story-mock-data`.
 */
import { EyeIcon, MessageCircleIcon, MessageSquareReplyIcon, PackageIcon } from "lucide-react";

import { text, work } from "@/components/practice-vocabulary/feedback-text";
import type { PracticeFeedbackCardEntry } from "@/components/practice-vocabulary/PracticeFeedbackCard";

import { conversation, pullRequest } from "./practice-profile-story-mock-data";

/** One clean piece of work in a card's strip: the work, and the day it came back clean. */
const clean = (number: number, date: Date) => ({ ref: pullRequest(number), date });

/** The group three of the cards below belong to; the stories vary its colour from here. */
const PACKAGING_GROUP = {
	slug: "review-ready-work",
	name: "Packaging work for review",
	color: "sky",
	icon: PackageIcon,
} as const;

const CLEAN_CONDITION = [text("Ticks itself once three pieces of work in a row come back clean")];

const SCOPE_ONE_CONCERN = {
	group: PACKAGING_GROUP,
	practiceSlug: "scope-one-reviewable-change",
	practiceName: "Scope the change to one concern",
	headline: "Pull requests bundle a fix with a refactor",
	body: "In #19 the fix for the retry loop travelled with a rename of the module it lives in, and #20 carried a dependency bump alongside a behaviour change. Reviewers had to follow two intentions in one diff.",
	nextStep:
		"Next time a fix and a refactor meet in the same branch, open the fix first as its own pull request, let it be reviewed on its own, and put the refactor on top of it once the fix is in. The reviewer then reads one intention at a time.",
	condition: CLEAN_CONDITION,
	cleanNeeded: 3,
	timestamp: new Date("2026-09-09T14:10:00"),
} satisfies Partial<PracticeFeedbackCardEntry>;

/** The page's first card: new, three problems in the strip, nothing clean yet. */
export const NEW_FEEDBACK_CARD: PracticeFeedbackCardEntry = {
	feedbackId: "scope-one-concern-new",
	...SCOPE_ONE_CONCERN,
	reviewedWork: [
		{ ref: pullRequest(17), date: new Date("2026-08-28T00:00"), outcome: "COMMISSION_PROBLEM" },
		{ ref: pullRequest(19), date: new Date("2026-09-06T00:00"), outcome: "COMMISSION_PROBLEM" },
		{ ref: pullRequest(20), date: new Date("2026-09-03T00:00"), outcome: "COMMISSION_PROBLEM" },
	],
	cleanWork: [],
	state: "new",
};

/**
 * Most useful first, as the page lists them; the page shows three and reveals the rest on request.
 */
const OPEN_FEEDBACK_CARDS: PracticeFeedbackCardEntry[] = [
	NEW_FEEDBACK_CARD,
	{
		// A second practice, not a second card for the first one: a practice carries one live card.
		feedbackId: "ready-and-traceable-two-clean",
		group: PACKAGING_GROUP,
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
			{ ref: pullRequest(17), date: new Date("2026-08-28T00:00"), outcome: "COMMISSION_PROBLEM" },
			{ ref: pullRequest(19), date: new Date("2026-09-06T00:00"), outcome: "COMMISSION_PROBLEM" },
			{ ref: pullRequest(20), date: new Date("2026-09-03T00:00"), outcome: "COMMISSION_PROBLEM" },
		],
		cleanWork: [clean(21, new Date("2026-09-07T00:00")), clean(22, new Date("2026-09-09T00:00"))],
		state: "open",
	},
	{
		feedbackId: "describe-what-and-why",
		group: PACKAGING_GROUP,
		practiceSlug: "describe-what-and-why",
		practiceName: "Describe what changed and why",
		headline: "Descriptions name the files, not the problem",
		body: "#16 and #18 opened with the list of files touched and no sentence on the problem behind them; on #18 the first review comment asked what the change was for.",
		reviewedWork: [
			{ ref: pullRequest(16), date: new Date("2026-08-24T00:00"), outcome: "OMISSION_GAP" },
			{ ref: pullRequest(18), date: new Date("2026-09-01T00:00"), outcome: "OMISSION_GAP" },
		],
		cleanWork: [clean(20, new Date("2026-09-03T00:00"))],
		nextStep:
			"Open the description with the problem and the decision you took, then the file list.",
		condition: CLEAN_CONDITION,
		cleanNeeded: 3,
		state: "open",
		timestamp: new Date("2026-09-09T14:10:00"),
	},
	{
		feedbackId: "reviewable-diff-size",
		group: PACKAGING_GROUP,
		practiceSlug: "reviewable-diff-size",
		practiceName: "Keep the diff reviewable in one sitting",
		headline: "Two pull requests grew past what one review can hold",
		body: "#21 touched 41 files and #22 38; both went through two rounds of review, and the second round of each reopened a file the first had already approved.",
		reviewedWork: [
			{
				ref: pullRequest(19),
				date: new Date("2026-09-06T00:00"),
				outcome: "DEMONSTRATED_STRENGTH",
			},
			{ ref: pullRequest(21), date: new Date("2026-09-07T00:00"), outcome: "COMMISSION_PROBLEM" },
			{ ref: pullRequest(22), date: new Date("2026-09-09T00:00"), outcome: "COMMISSION_PROBLEM" },
		],
		// A strength before the problems: the clean work it started was emptied, so nothing is clean
		// since.
		cleanWork: [],
		nextStep:
			"When a branch passes twenty files, stop and split it: land the shared groundwork first, then each behaviour change on top of it as its own pull request.",
		condition: CLEAN_CONDITION,
		cleanNeeded: 3,
		state: "open",
		timestamp: new Date("2026-09-09T14:10:00"),
	},
	{
		// The third of the three examples the cards are read against: the review dialogue, as the
		// author of the change. A practice from another group, so the page shows one.
		feedbackId: "unresolved-review-threads",
		group: {
			slug: "acting-on-review-feedback",
			name: "Acting on review feedback",
			color: "cyan",
			icon: MessageSquareReplyIcon,
		},
		practiceSlug: "merged-past-unresolved-review-threads",
		practiceName: "Resolve open threads before merging",
		headline: "Pull requests were merged over open review threads",
		body: "#17 merged with two threads still open, and on #20 the reviewer's question about the retry limit was never answered; the reviewer had to reopen it in the next review.",
		reviewedWork: [
			{ ref: pullRequest(17), date: new Date("2026-08-28T00:00"), outcome: "COMMISSION_PROBLEM" },
			{ ref: pullRequest(20), date: new Date("2026-09-03T00:00"), outcome: "COMMISSION_PROBLEM" },
		],
		cleanWork: [clean(21, new Date("2026-09-07T00:00")), clean(22, new Date("2026-09-09T00:00"))],
		nextStep:
			"Before you merge, answer every open thread with a commit or a sentence and resolve it, so the reviewer sees what became of each comment.",
		condition: CLEAN_CONDITION,
		cleanNeeded: 3,
		state: "open",
		timestamp: new Date("2026-09-04T09:32:00"),
	},
	{
		feedbackId: "acceptance-criteria",
		group: PACKAGING_GROUP,
		practiceSlug: "honours-linked-issue-acceptance-criteria",
		practiceName: "Say which acceptance criteria are done",
		headline: "Pull requests closed their issue without saying what was met",
		body: "#16 and #19 each closed an issue with three acceptance criteria and mentioned none of them; #13 was reopened a week later for the criterion the change had skipped.",
		reviewedWork: [
			{ ref: pullRequest(16), date: new Date("2026-08-24T00:00"), outcome: "OMISSION_GAP" },
			{ ref: pullRequest(19), date: new Date("2026-09-06T00:00"), outcome: "OMISSION_GAP" },
			{ ref: pullRequest(22), date: new Date("2026-09-09T00:00"), outcome: "OMISSION_GAP" },
		],
		// Two clean pieces before #22 put the run at two of three; #22 emptied it again, which is
		// the fall back the overview's "What needs your attention" reports.
		cleanWork: [],
		nextStep:
			"Copy the issue's acceptance criteria into the description and tick the ones the change meets, so the reviewer and the issue's author see the same list.",
		condition: CLEAN_CONDITION,
		cleanNeeded: 3,
		state: "open",
		timestamp: new Date("2026-09-02T16:45:00"),
	},
];

/**
 * The resolved feedback, newest first, as the "Resolved feedback" level lists it: two the work
 * resolved, whose condition names the clean pieces, and one the reader marked addressed.
 */
const RESOLVED_FEEDBACK_CARDS: PracticeFeedbackCardEntry[] = [
	{
		feedbackId: "describe-what-and-why-resolved",
		group: PACKAGING_GROUP,
		practiceSlug: "describe-what-and-why",
		practiceName: "Describe what changed and why",
		headline: "Descriptions named the what, rarely the why",
		body: "#16 and #19 listed the files touched but not the problem behind them; the reviewer on #19 asked in the first comment what the change was for.",
		reviewedWork: [
			{ ref: pullRequest(19), date: new Date("2026-09-06T00:00"), outcome: "OMISSION_GAP" },
		],
		cleanWork: [
			clean(20, new Date("2026-09-07T00:00")),
			clean(21, new Date("2026-09-08T00:00")),
			clean(22, new Date("2026-09-09T00:00")),
		],
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
		resolvedBy: "WORK",
		timestamp: new Date("2026-09-09T14:10:00"),
	},
	{
		feedbackId: "review-comments-specific-resolved",
		group: {
			slug: "constructive-code-review",
			name: "Reviewing a teammate's work constructively",
			color: "teal",
			icon: EyeIcon,
		},
		practiceSlug: "leaves-useful-specific-review-comments",
		practiceName: "Leave specific, actionable review comments",
		headline: "Review comments said something was off, not what",
		body: 'On #2 the comments read "this looks wrong" and "can we do better here?", and the author replied to each one asking what to change.',
		reviewedWork: [
			{ ref: pullRequest(2), date: new Date("2026-08-20T00:00"), outcome: "COMMISSION_PROBLEM" },
		],
		cleanWork: [
			clean(1, new Date("2026-08-25T00:00")),
			clean(4, new Date("2026-08-29T00:00")),
			clean(23, new Date("2026-09-02T00:00")),
		],
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
		resolvedBy: "WORK",
		timestamp: new Date("2026-09-02T11:40:00"),
	},
	{
		feedbackId: "status-updates-resolved",
		group: {
			slug: "communication",
			name: "Communicating in the open",
			color: "violet",
			icon: MessageCircleIcon,
		},
		practiceSlug: "posts-clear-status-and-blocker-updates",
		practiceName: "Post clear status and blocker updates",
		headline: "Blockers surfaced in stand-up, not in the channel",
		body: "In #backend-review the runner outage that held the release for two days was first mentioned in the next morning's stand-up; the thread on it stayed silent.",
		reviewedWork: [
			{
				ref: conversation("#backend-review"),
				date: new Date("2026-08-12T00:00"),
				outcome: "OMISSION_GAP",
			},
		],
		// The one the reader resolved, before the work did: the clean work stays where the work
		// left it.
		cleanWork: [
			{ ref: conversation("#releases"), date: new Date("2026-08-20T00:00") },
			{ ref: conversation("#incidents"), date: new Date("2026-08-25T00:00") },
		],
		nextStep:
			"When something blocks you for more than an hour, post it in the channel the work lives in, with what you have tried and what you need.",
		condition: [text("Marked as addressed on 27 August")],
		cleanNeeded: 3,
		state: "resolved",
		resolvedBy: "DEVELOPER",
		timestamp: new Date("2026-08-27T16:05:00"),
	},
];

/** Open and resolved together, as the route hands the page every readable card. */
export const ALL_FEEDBACK_CARDS: PracticeFeedbackCardEntry[] = [
	...OPEN_FEEDBACK_CARDS,
	...RESOLVED_FEEDBACK_CARDS,
];
