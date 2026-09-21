/**
 * The developer's practice profile as the wire carries it, for the stories and the tests: the
 * reviewed work every practice-profile fixture names, the workspace's groups, the developer's
 * standing in each and on each practice, and one overview — the events behind the Heph card, the
 * group and practice sentences and the latest-run chip — on the same groups and practices as the
 * feedback card fixtures. The pages read the server.
 */
import type {
	PracticeGroup,
	PracticeGroupStanding,
	PracticeProfileOverview,
	PracticeStanding,
	ProfileChange,
	ReviewedWorkRef,
	TrendSupport,
} from "@/api/types.gen";
import { ARTIFACT_KIND } from "@/lib/artifact-kinds";

/**
 * Reviewed work as the wire names it, for every practice-profile fixture. The pull requests and
 * issues are real ones in the development workspace's test repositories, so every link lands on
 * the page it names; the prose around them is the mockup's. A conversation has no page there and
 * stays plain text until the wire contract carries an address for it.
 */
const PULL_REQUESTS = "https://github.com/HephaestusTest/practice-validation/pull/";
const ISSUES = "https://github.com/HephaestusTest/MaxTestRepo/issues/";

export const pullRequest = (number: number): ReviewedWorkRef => ({
	id: String(number),
	kind: ARTIFACT_KIND.pullRequest,
	provider: "GITHUB",
	label: `#${number}`,
	url: `${PULL_REQUESTS}${number}`,
	repositoryName: "HephaestusTest/practice-validation",
});

export const issue = (number: number): ReviewedWorkRef => ({
	id: String(number),
	kind: ARTIFACT_KIND.issue,
	provider: "GITHUB",
	label: `#${number}`,
	url: `${ISSUES}${number}`,
	repositoryName: "HephaestusTest/MaxTestRepo",
});

/** A Slack channel by name, "#releases"; the name is its id as well. */
export const conversation = (label: string): ReviewedWorkRef => ({
	id: label,
	kind: ARTIFACT_KIND.conversationThread,
	provider: "SLACK",
	label,
});

/** Enough work on both sides of the comparison for a trend to be read. */
export const wellSupported: TrendSupport = {
	currentOpportunities: 4,
	previousOpportunities: 4,
	opportunitiesUntilComparable: 0,
	bundleSize: 4,
	ropeHalfWidth: 0.15,
	credibilityThreshold: 0.9,
	calendarSpanDays: 12,
	comparablePractices: 2,
	eligiblePractices: 3,
};

function group(
	id: number,
	slug: string,
	name: string,
	visual: { icon: string; color: string; description: string },
): PracticeGroup {
	return {
		id,
		slug,
		name,
		description: visual.description,
		displayOrder: id,
		visibleInPracticeDashboards: true,
		autonomy: { effective: "AUTOMATIC", inherited: true, source: "WORKSPACE" },
		icon: visual.icon,
		color: visual.color,
		createdAt: new Date("2026-01-01T00:00:00Z"),
	};
}

/** The group the detail levels open in their stories and tests: the first of the catalog. */
export const packagingGroup = group(1, "review-ready-work", "Packaging work for review", {
	icon: "Package",
	color: "sky",
	description:
		"Make changes easy to review before asking for feedback: one concern per change, a description that says what moved and why, and evidence that it works.",
});

/** The shipped catalog's groups, by slug, so the overview's sentences find their rows. */
export const groups: PracticeGroup[] = [
	packagingGroup,
	group(2, "acting-on-review-feedback", "Acting on review feedback", {
		icon: "MessageSquareReply",
		color: "cyan",
		description: "Answer every review comment and close the threads before merging.",
	}),
	group(3, "communication", "Communicating in the open", {
		icon: "MessageCircle",
		color: "violet",
		description: "Ask and answer so a teammate can act on it.",
	}),
	group(4, "constructive-code-review", "Reviewing a teammate's work constructively", {
		icon: "Eye",
		color: "teal",
		description: "Give review that moves the work forward.",
	}),
	group(5, "testing-discipline", "Testing your changes", {
		icon: "TestTube",
		color: "amber",
		description: "Ship tests with the change and keep the suite honest.",
	}),
];

const groupStanding = (
	slug: string,
	standing: PracticeGroupStanding["standing"],
	direction?: PracticeGroupStanding["direction"],
): PracticeGroupStanding => ({
	groupSlug: slug,
	groupName: groups.find((candidate) => candidate.slug === slug)?.name ?? slug,
	standing,
	direction,
	trendSupport: direction ? wellSupported : undefined,
	observations: [],
	sources: standing === "NOT_OBSERVED" ? [] : [{ workKind: ARTIFACT_KIND.pullRequest, count: 6 }],
	feedbackSpanDays: standing === "NOT_OBSERVED" ? undefined : 42,
});

/** Where the developer stands in the group the detail levels open. */
export const packagingStanding = groupStanding(packagingGroup.slug, "DEVELOPING", "DECLINING");

export const groupStandings: Record<string, PracticeGroupStanding> = {
	[packagingGroup.slug]: packagingStanding,
	"acting-on-review-feedback": groupStanding("acting-on-review-feedback", "MIXED", "UNCERTAIN"),
	communication: groupStanding("communication", "DEVELOPING"),
	"constructive-code-review": groupStanding("constructive-code-review", "STRENGTH"),
	"testing-discipline": groupStanding("testing-discipline", "NOT_OBSERVED"),
};

const practice = (
	groupSlug: string,
	slug: string,
	name: string,
	standing: PracticeStanding["standing"],
): PracticeStanding => ({
	slug,
	name,
	groupSlug,
	groupName: groups.find((candidate) => candidate.slug === groupSlug)?.name,
	standing,
	strengths: [],
	toWorkOn: [],
});

/** Every practice with a standing, grouped as the tables list them. */
export const practicesByGroup: Record<string, PracticeStanding[]> = {
	"review-ready-work": [
		practice(
			"review-ready-work",
			"scope-one-reviewable-change",
			"Scope the change to one concern",
			"DEVELOPING",
		),
		practice(
			"review-ready-work",
			"reviewable-diff-size",
			"Keep the diff reviewable in one sitting",
			"DEVELOPING",
		),
		practice(
			"review-ready-work",
			"describe-what-and-why",
			"Describe what changed and why",
			"MIXED",
		),
		practice(
			"review-ready-work",
			"ready-and-traceable-handoff",
			"Mark the change ready and link its issue",
			"STRENGTH",
		),
		practice(
			"review-ready-work",
			"commit-subjects-explain-each-change",
			"Write commit subjects a reviewer can follow",
			"STRENGTH",
		),
	],
	"acting-on-review-feedback": [
		practice(
			"acting-on-review-feedback",
			"engaging-with-inline-review-comments",
			"Respond to each review comment",
			"MIXED",
		),
		practice(
			"acting-on-review-feedback",
			"merged-past-unresolved-review-threads",
			"Resolve open threads before merging",
			"STRENGTH",
		),
	],
	communication: [
		practice(
			"communication",
			"asks-answerable-questions",
			"Ask questions a teammate can answer",
			"DEVELOPING",
		),
		practice(
			"communication",
			"gives-actionable-answers",
			"Give answers people can act on",
			"STRENGTH",
		),
		practice(
			"communication",
			"posts-clear-status-and-blocker-updates",
			"Post clear status and blocker updates",
			"STRENGTH",
		),
	],
	"constructive-code-review": [
		practice(
			"constructive-code-review",
			"leaves-useful-specific-review-comments",
			"Leave specific, actionable review comments",
			"STRENGTH",
		),
		practice(
			"constructive-code-review",
			"reviews-respectfully-asks-rather-than-demands",
			"Comment on the code, not the person",
			"STRENGTH",
		),
		practice(
			"constructive-code-review",
			"reviews-substantively-with-understanding",
			"Read the change before approving it",
			"STRENGTH",
		),
	],
	"testing-discipline": [
		practice(
			"testing-discipline",
			"ships-tests-with-the-change",
			"Include tests with the change",
			"NOT_OBSERVED",
		),
		practice(
			"testing-discipline",
			"keeps-the-test-suite-honest",
			"Keep the test suite honest",
			"NOT_OBSERVED",
		),
	],
};

export const practiceStandings: PracticeStanding[] = Object.values(practicesByGroup).flat();

const LATEST_RUN_AT = new Date("2026-09-09T14:10:00");

const PACKAGING = { groupSlug: "review-ready-work", groupName: "Packaging work for review" };
const COMMUNICATION = { groupSlug: "communication", groupName: "Communicating in the open" };
const REVIEWING = {
	groupSlug: "constructive-code-review",
	groupName: "Reviewing a teammate's work constructively",
};

const change = (
	type: ProfileChange["type"],
	inGroup: { groupSlug: string; groupName: string },
	onPractice: { practiceSlug: string; practiceName: string } | undefined,
	rest: Partial<ProfileChange> = {},
): ProfileChange => ({
	type,
	at: LATEST_RUN_AT,
	...inGroup,
	...onPractice,
	evidence: [],
	...rest,
});

const PACKAGING_CHANGES: ProfileChange[] = [
	change(
		"FEEDBACK_RESOLVED",
		PACKAGING,
		{ practiceSlug: "describe-what-and-why", practiceName: "Describe what changed and why" },
		{
			feedbackId: "describe-what-and-why",
			resolvedBy: "WORK",
			evidence: [22, 21, 20].map(pullRequest),
		},
	),
	change(
		"FEEDBACK_NEW",
		PACKAGING,
		{
			practiceSlug: "scope-one-reviewable-change",
			practiceName: "Scope the change to one concern",
		},
		{ feedbackId: "scope-one-concern-new", evidence: [20, 22].map(pullRequest) },
	),
	change(
		"STANDING_MOVED",
		PACKAGING,
		{
			practiceSlug: "reviewable-diff-size",
			practiceName: "Keep the diff reviewable in one sitting",
		},
		{ from: "MIXED", to: "DEVELOPING", evidence: [21, 22].map(pullRequest) },
	),
	change(
		"STANDING_MOVED",
		PACKAGING,
		{
			practiceSlug: "ready-and-traceable-handoff",
			practiceName: "Mark the change ready and link its issue",
		},
		{ from: "MIXED", to: "STRENGTH", evidence: [21, 22].map(pullRequest) },
	),
	change(
		"TREND_TURNED",
		PACKAGING,
		{
			practiceSlug: "commit-subjects-explain-each-change",
			practiceName: "Write commit subjects a reviewer can follow",
		},
		{ from: "UNCERTAIN", to: "IMPROVING", evidence: [20, 21, 22].map(pullRequest) },
	),
];

const COMMUNICATION_CHANGES: ProfileChange[] = [
	change(
		"FEEDBACK_NEW",
		COMMUNICATION,
		{
			practiceSlug: "asks-answerable-questions",
			practiceName: "Ask questions a teammate can answer",
		},
		{
			feedbackId: "asks-answerable-questions",
			evidence: [conversation("#backend-review")],
		},
	),
	change(
		"FIRST_OBSERVED",
		COMMUNICATION,
		{
			practiceSlug: "posts-clear-status-and-blocker-updates",
			practiceName: "Post clear status and blocker updates",
		},
		{
			to: "STRENGTH",
			evidence: [conversation("#releases"), conversation("#incidents")],
		},
	),
	change("GROUP_MOVED", COMMUNICATION, undefined, {
		from: "DEVELOPING",
		to: "MIXED",
		evidence: [conversation("#releases")],
	}),
];

const REVIEWING_CHANGES: ProfileChange[] = [
	change(
		"FEEDBACK_RESOLVED",
		REVIEWING,
		{
			practiceSlug: "leaves-useful-specific-review-comments",
			practiceName: "Leave specific, actionable review comments",
		},
		{
			at: new Date("2026-09-02T11:40:00"),
			feedbackId: "review-comments-specific-resolved",
			resolvedBy: "DEVELOPER",
			evidence: [],
		},
	),
	change(
		"STANDING_MOVED",
		REVIEWING,
		{ practiceSlug: "reviews-promptly", practiceName: "Review within a working day" },
		{ from: "MIXED", to: "STRENGTH", evidence: [4, 23].map(pullRequest) },
	),
	change(
		"STANDING_MOVED",
		REVIEWING,
		{ practiceSlug: "points-at-the-line", practiceName: "Point at the line, not the file" },
		{ from: "DEVELOPING", to: "MIXED", evidence: [23].map(pullRequest) },
	),
];

/**
 * A run in which three practices of one group made the same move and a fourth made its own: what
 * the practices table's group cell has to say in three bullets rather than six. One group moved
 * where a practice under it moved, so it rides on that practice's bullet; the other moved where
 * none of its practices did, so it is a bullet of its own.
 */
export const SHARED_TRANSITION_OVERVIEW: PracticeProfileOverview = {
	latestRun: { jobId: "run-2026-09-09", at: LATEST_RUN_AT, reviewedWork: pullRequest(22) },
	window: { since: new Date("2026-09-02T09:00:00"), until: LATEST_RUN_AT },
	holdingUp: [],
	changes: [
		change(
			"TREND_TURNED",
			PACKAGING,
			{
				practiceSlug: "scope-one-reviewable-change",
				practiceName: "Scope the change to one concern",
			},
			{ from: "UNCERTAIN", to: "IMPROVING", evidence: [pullRequest(22)] },
		),
		change(
			"TREND_TURNED",
			PACKAGING,
			{
				practiceSlug: "commit-subjects-explain-each-change",
				practiceName: "Write commit subjects a reviewer can follow",
			},
			{ from: "UNCERTAIN", to: "IMPROVING", evidence: [pullRequest(22)] },
		),
		change(
			"TREND_TURNED",
			PACKAGING,
			{
				practiceSlug: "reviewable-diff-size",
				practiceName: "Keep the diff reviewable in one sitting",
			},
			{ from: "UNCERTAIN", to: "IMPROVING", evidence: [pullRequest(22)] },
		),
		change(
			"FIRST_OBSERVED",
			PACKAGING,
			{
				practiceSlug: "ready-and-traceable-handoff",
				practiceName: "Mark the change ready and link its issue",
			},
			{ evidence: [pullRequest(21), pullRequest(22)] },
		),
		change(
			"STANDING_MOVED",
			PACKAGING,
			{ practiceSlug: "describe-what-and-why", practiceName: "Describe what changed and why" },
			{ from: "MIXED", to: "STRENGTH", evidence: [pullRequest(22)] },
		),
		// The move the practice above made, so the group rides on that bullet rather than taking one.
		change("GROUP_MOVED", PACKAGING, undefined, {
			from: "MIXED",
			to: "STRENGTH",
			evidence: [pullRequest(22)],
		}),
		// A move no practice in the group made, so the group keeps a bullet of its own.
		change("GROUP_MOVED", COMMUNICATION, undefined, {
			from: "DEVELOPING",
			to: "MIXED",
			evidence: [pullRequest(21)],
		}),
	],
	reviewedWork: [pullRequest(21), pullRequest(22)],
};

/** The overview the stories show: one run, three groups moved, nine changes in all. */
export const OVERVIEW_FIXTURE: PracticeProfileOverview = {
	latestRun: { jobId: "run-2026-09-09", at: LATEST_RUN_AT, reviewedWork: pullRequest(22) },
	window: { since: new Date("2026-09-02T09:00:00"), until: LATEST_RUN_AT },
	holdingUp: [
		{
			practiceSlug: "ready-and-traceable-handoff",
			practiceName: "Mark the change ready and link its issue",
			groupSlug: PACKAGING.groupSlug,
			holdsAs: "every merge request names its issue",
			cleanWork: 9,
			workKind: ARTIFACT_KIND.pullRequest,
			since: new Date("2026-07-14T10:00:00"),
		},
		{
			practiceSlug: "engaging-with-inline-review-comments",
			practiceName: "Respond to each review comment",
			groupSlug: "acting-on-review-feedback",
			holdsAs: "every comment got an answer before the next push",
			cleanWork: 4,
			workKind: ARTIFACT_KIND.pullRequest,
			since: new Date("2026-08-20T10:00:00"),
		},
		{
			practiceSlug: "commit-subjects-explain-each-change",
			practiceName: "Write commit subjects a reviewer can follow",
			groupSlug: PACKAGING.groupSlug,
			holdsAs: "subjects name the change, not the file",
			cleanWork: 6,
			workKind: ARTIFACT_KIND.pullRequest,
			since: new Date("2026-08-05T10:00:00"),
		},
	],
	changes: [...PACKAGING_CHANGES, ...COMMUNICATION_CHANGES, ...REVIEWING_CHANGES],
	reviewedWork: [
		...[19, 20, 21, 22].map(pullRequest),
		conversation("#releases"),
		conversation("#incidents"),
		conversation("#backend-review"),
		issue(13),
		issue(14),
		{ id: "doc-7", kind: ARTIFACT_KIND.document, label: "Queue retry policy" },
	],
};
