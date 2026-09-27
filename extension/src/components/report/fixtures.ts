import type { PracticeTraceEntry, TracedSignal } from "~/api/types.gen";
import type {
	ObservationPage,
	ObservationRow,
	ReadyContext,
	WorkFeedback,
} from "~/shared/review-context";
import { minutesBefore } from "~/stories/story-clock";

export const WEB_APP = "https://heph.example.test";

/**
 * A value a newer server may send and this build has no words for. Typed as the union it is not,
 * because that is exactly the lie a stale generated client tells at runtime.
 */
// oxlint-disable-next-line typescript/no-unnecessary-type-parameters -- The caller names the union the value pretends to belong to.
export function fromNewerServer<T extends string>(value: string): T {
	// oxlint-disable-next-line typescript/no-unsafe-type-assertion -- The point: a runtime value outside the generated union.
	return value as T;
}

export const DESCRIPTIVE_PRACTICE: PracticeTraceEntry = {
	autonomy: "AUTOMATIC",
	decidedAt: minutesBefore(42),
	deliveredCount: 1,
	explanation: "A review measured this practice on the latest changes.",
	observationCount: 2,
	outcome: "REVIEWED",
	practiceName: "Descriptive merge request",
	practiceSlug: "descriptive-merge-request",
	watches: ["ready_for_review"],
	withheldReasons: [],
	reviewId: "0b7c1a52-0d3e-4c55-8a0b-1d2e3f4a5b6c",
};

export const PRACTICES: PracticeTraceEntry[] = [
	DESCRIPTIVE_PRACTICE,
	{
		autonomy: "HUMAN_APPROVAL",
		decidedAt: minutesBefore(42),
		deliveredCount: 0,
		explanation: "A review measured this practice; its feedback waits for approval.",
		observationCount: 1,
		outcome: "REVIEWED",
		practiceName: "Small, focused changes",
		practiceSlug: "small-focused-changes",
		watches: ["ready_for_review"],
		withheldReasons: ["APPROVAL_STALE"],
		reviewId: "0b7c1a52-0d3e-4c55-8a0b-1d2e3f4a5b6c",
	},
	{
		autonomy: "AUTOMATIC",
		deliveredCount: 0,
		explanation: "Nothing happened to this work that this practice watches for.",
		observationCount: 0,
		outcome: "NOT_OCCASIONED",
		practiceName: "Merge hygiene",
		practiceSlug: "merge-hygiene",
		watches: ["merged"],
		withheldReasons: [],
	},
];

export const SIGNALS: TracedSignal[] = [
	{
		id: "s1",
		discoveredVia: "EVENT",
		displayName: "Marked ready for review",
		occurredAt: minutesBefore(50),
		revision: "4f2a9c1",
		signal: "ready_for_review",
		state: "TRIGGERED",
		reviewId: "0b7c1a52-0d3e-4c55-8a0b-1d2e3f4a5b6c",
	},
	{
		id: "s2",
		discoveredVia: "MANUAL",
		displayName: "Review requested",
		occurredAt: minutesBefore(20),
		revision: "4f2a9c1",
		signal: "manual",
		state: "SUPPRESSED",
		stateReason: "REQUEST_COOLDOWN_ACTIVE",
	},
];

export const READY: ReadyContext = {
	status: "ready",
	instanceHost: "heph.example.test",
	workspace: { slug: "intro-course", displayName: "Intro Course 2026" },
	alternatives: [],
	work: {
		id: "4009",
		kind: "scm.pull_request",
		label: "!1",
		provider: "GITLAB",
		title: "Add the login screen",
		repositoryName: "hephaestustest/introcourse/demo",
		url: "https://gitlab.example.test/hephaestustest/introcourse/demo/-/merge_requests/1",
	},
	canRequestReview: true,
	canInspectReviewDetails: false,
	trace: {
		artifactId: 4009,
		artifactKind: "scm.pull_request",
		title: "Add the login screen",
		practices: PRACTICES,
		signals: SIGNALS,
	},
	links: { trace: `${WEB_APP}/w/intro-course/reviews/scm.pull_request/4009` },
	pageUrl: "https://gitlab.example.test/hephaestustest/introcourse/demo/-/merge_requests/1",
	view: "overview",
	fetchedAt: minutesBefore(1),
};

export const READY_ADMIN: ReadyContext = {
	...READY,
	canInspectReviewDetails: true,
	links: {
		...READY.links,
		reviewDetails: `${WEB_APP}/w/intro-course/admin/practices/reviews/targets/pull-request/4009`,
	},
};

/** The same work as {@link READY}, as a pull request on GitHub. */
export const READY_ON_GITHUB: ReadyContext = {
	...READY,
	work: {
		...READY.work,
		label: "#1",
		provider: "GITHUB",
		title: "Add admin API",
		repositoryName: "HephaestusTest/lifecycle-validation",
		url: "https://github.com/HephaestusTest/lifecycle-validation/pull/1",
	},
	trace: READY.trace === null ? null : { ...READY.trace, title: "Add admin API" },
	pageUrl: "https://github.com/HephaestusTest/lifecycle-validation/pull/1",
};

export const NEGATIVE_ROW: ObservationRow = {
	id: "1a2b3c4d-0000-4000-8000-000000000001",
	practiceName: "Descriptive merge request",
	practiceSlug: "descriptive-merge-request",
	summary:
		"The description lists the changed files but never says what problem the login screen solves or which issue it closes.",
	outcome: "NEGATIVE",
	severity: "MAJOR",
	assessmentStatus: "ASSESSED",
	claimCurrentness: "CURRENT",
	observedAt: minutesBefore(42),
};

export const POSITIVE_ROW: ObservationRow = {
	id: "1a2b3c4d-0000-4000-8000-000000000002",
	practiceName: "Small, focused changes",
	practiceSlug: "small-focused-changes",
	summary: "The change touches one feature and stays under two hundred lines.",
	outcome: "POSITIVE",
	assessmentStatus: "ASSESSED",
	claimCurrentness: "CURRENT",
	observedAt: minutesBefore(42),
};

export const UNDETERMINED_ROW: ObservationRow = {
	id: "1a2b3c4d-0000-4000-8000-000000000003",
	practiceName: "Tests cover the change",
	practiceSlug: "tests-cover-the-change",
	summary:
		"The diff adds a form but it is unclear whether the existing end-to-end suite reaches it.",
	assessmentStatus: "UNDETERMINED",
	claimCurrentness: "CURRENT",
	observedAt: minutesBefore(42),
};

export const HISTORICAL_ROW: ObservationRow = {
	...NEGATIVE_ROW,
	id: "1a2b3c4d-0000-4000-8000-000000000004",
	practiceName: "Commit messages explain why",
	severity: "MINOR",
	summary: "Two commits are titled only “fix”.",
	claimCurrentness: "STALE",
};

export const OWN_PAGE: ObservationPage = {
	rows: [NEGATIVE_ROW, HISTORICAL_ROW, POSITIVE_ROW, UNDETERMINED_ROW],
	total: 4,
	fetchedAt: minutesBefore(1),
};

/** A long first page of a longer list: 25 shown of 40 recorded. */
export const LONG_PAGE: ObservationPage = {
	rows: Array.from({ length: 25 }, (_, index) => ({
		...(index < 3 ? NEGATIVE_ROW : POSITIVE_ROW),
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
			deliveredAt: minutesBefore(40),
			permalink: `${PR_URL}#issuecomment-2211`,
		},
		{
			kind: "INLINE",
			path: "src/login/LoginScreen.tsx",
			startLine: 12,
			endLine: 18,
			practices: ["Small, focused changes", "Tests cover the change"],
			deliveredAt: minutesBefore(41),
			permalink: `${PR_URL}#discussion_r3301`,
		},
		// Posted while the rest of its feedback failed: no practice is a posted fact, and no link was recorded.
		{ kind: "INLINE", path: "src/login/login.css", startLine: 4, practices: [] },
	],
	more: false,
	fetchedAt: minutesBefore(1),
};

export const NO_FEEDBACK: WorkFeedback = { comments: [], more: false, fetchedAt: minutesBefore(1) };
