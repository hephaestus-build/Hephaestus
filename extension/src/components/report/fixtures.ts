import type { ItemRef, OwnItemTrace } from "~/api/types.gen";
import type { ReadyContext, ResultPage, ResultRow, WorkFeedback } from "~/shared/review-context";
import { minutesBefore } from "~/stories/story-clock";

export const WEB_APP = "https://hephaestus.example.test";

const GITLAB_ITEM = {
	id: "4009",
	kind: "scm.pull_request",
	parentId: "4001",
	label: "!1",
	provider: "GITLAB",
	title: "Add the login screen",
	container: "hephaestustest/introcourse/demo",
	path: null,
	url: "https://gitlab.example.test/hephaestustest/introcourse/demo/-/merge_requests/1",
	removedAt: null,
} satisfies ItemRef;

export const OWN_TRACE: OwnItemTrace = {
	item: GITLAB_ITEM,
	review: {
		id: "0b7c1a52-0d3e-4c55-8a0b-1d2e3f4a5b6c",
		kind: "ITEM",
		item: GITLAB_ITEM,
		itemVersion: "4f2a9c1e8b7d6a5f4e3d2c1b0a9f8e7d6c5b4a39",
		origin: "EVENT",
		status: "COMPLETE",
		createdAt: minutesBefore(50),
		completedAt: minutesBefore(42),
	},
	events: [
		{
			name: "scm.pull_request.ready",
			displayName: "Marked ready for review",
			occurredAt: minutesBefore(50),
		},
	],
	results: [],
};

export const READY: ReadyContext = {
	status: "ready",
	instanceHost: "hephaestus.example.test",
	workspace: { slug: "intro-course", displayName: "Intro Course 2026" },
	alternatives: [],
	item: GITLAB_ITEM,
	requestAllowed: true,
	canInspectReviewDetails: false,
	trace: OWN_TRACE,
	links: { trace: `${WEB_APP}/w/intro-course/feedback/4009` },
	pageUrl: "https://gitlab.example.test/hephaestustest/introcourse/demo/-/merge_requests/1",
	view: "overview",
	fetchedAt: minutesBefore(1),
};

export const READY_ADMIN: ReadyContext = {
	...READY,
	canInspectReviewDetails: true,
	links: {
		...READY.links,
		reviewDetails: `${WEB_APP}/w/intro-course/admin/practices/reviews/?itemId=4009&detail=item%3A4009`,
	},
};

const GITHUB_ITEM = {
	...GITLAB_ITEM,
	label: "#1",
	provider: "GITHUB",
	title: "Add admin API",
	container: "HephaestusTest/lifecycle-validation",
	url: "https://github.com/HephaestusTest/lifecycle-validation/pull/1",
} satisfies ItemRef;

/** The same work as {@link READY}, as a pull request on GitHub. */
export const READY_ON_GITHUB: ReadyContext = {
	...READY,
	item: GITHUB_ITEM,
	trace: { ...OWN_TRACE, item: GITHUB_ITEM, review: { ...OWN_TRACE.review, item: GITHUB_ITEM } },
	pageUrl: "https://github.com/HephaestusTest/lifecycle-validation/pull/1",
};

export const NOT_MET_ROW: ResultRow = {
	id: "1a2b3c4d-0000-4000-8000-000000000001",
	practiceName: "Descriptive merge request",
	practiceSlug: "descriptive-merge-request",
	summary:
		"The description lists the changed files but never says what problem the login screen solves or which issue it closes.",
	outcome: "NOT_MET",
	severity: "MAJOR",
	currentness: "CURRENT",
	recordedAt: minutesBefore(42),
	problems: [
		{
			id: "5e6f7a8b-0000-4000-8000-000000000001",
			severity: "MAJOR",
			summary: "The description does not say which issue the change closes.",
		},
		{
			id: "5e6f7a8b-0000-4000-8000-000000000002",
			severity: "MINOR",
			summary: "The description lists the changed files instead of what the login screen does.",
		},
	],
};

export const MET_ROW: ResultRow = {
	id: "1a2b3c4d-0000-4000-8000-000000000002",
	practiceName: "Small, focused changes",
	practiceSlug: "small-focused-changes",
	summary: "The change touches one feature and stays under two hundred lines.",
	outcome: "MET",
	severity: null,
	currentness: "CURRENT",
	recordedAt: minutesBefore(42),
	problems: [],
};

export const COULD_NOT_DETERMINE_ROW: ResultRow = {
	id: "1a2b3c4d-0000-4000-8000-000000000003",
	practiceName: "Tests cover the change",
	practiceSlug: "tests-cover-the-change",
	summary:
		"The diff adds a form but it is unclear whether the existing end-to-end suite reaches it.",
	outcome: "COULD_NOT_DETERMINE",
	severity: null,
	currentness: "CURRENT",
	recordedAt: minutesBefore(42),
	problems: [],
};

export const DOES_NOT_APPLY_ROW: ResultRow = {
	...MET_ROW,
	id: "1a2b3c4d-0000-4000-8000-000000000005",
	practiceName: "Explain breaking changes",
	practiceSlug: "explain-breaking-changes",
	outcome: "DOES_NOT_APPLY",
	summary: "The change does not alter a public interface.",
};

export const HISTORICAL_ROW: ResultRow = {
	...NOT_MET_ROW,
	id: "1a2b3c4d-0000-4000-8000-000000000004",
	practiceName: "Commit messages explain why",
	severity: "MINOR",
	summary: "Two commits are titled only “fix”.",
	currentness: "UNVERIFIABLE",
	problems: [],
};

export const OWN_PAGE: ResultPage = {
	rows: [NOT_MET_ROW, HISTORICAL_ROW, MET_ROW, COULD_NOT_DETERMINE_ROW, DOES_NOT_APPLY_ROW],
	total: 5,
	fetchedAt: minutesBefore(1),
};

/** A long first page of a longer list: 25 shown of 40 recorded. */
export const LONG_PAGE: ResultPage = {
	rows: Array.from({ length: 25 }, (_, index) => ({
		...(index < 3 ? NOT_MET_ROW : MET_ROW),
		id: `1a2b3c4d-0000-4000-8000-${String(100 + index).padStart(12, "0")}`,
		practiceName: `${index < 3 ? "Needs work" : "Followed"}: practice ${index + 1} with a name long enough to wrap on a narrow window`,
	})),
	total: 40,
	fetchedAt: minutesBefore(1),
};

const PR_URL = READY_ON_GITHUB.pageUrl;

export const WORK_FEEDBACK: WorkFeedback = {
	comments: [
		{
			kind: "SUMMARY",
			practices: ["Descriptive merge request", "Small, focused changes"],
			postedAt: minutesBefore(40),
			permalink: `${PR_URL}#issuecomment-2211`,
		},
		{
			kind: "LINE_NOTE",
			path: "src/login/LoginScreen.tsx",
			startLine: 12,
			endLine: 18,
			practices: ["Small, focused changes", "Tests cover the change"],
			postedAt: minutesBefore(41),
			permalink: `${PR_URL}#discussion_r3301`,
		},
		// Posted while the rest of its feedback failed: no practice is a posted fact, and no link was recorded.
		{
			kind: "LINE_NOTE",
			path: "src/login/login.css",
			startLine: 4,
			practices: [],
			postedAt: minutesBefore(41),
		},
	],
	fetchedAt: minutesBefore(1),
};

export const NO_FEEDBACK: WorkFeedback = { comments: [], fetchedAt: minutesBefore(1) };
