import type {
	MemberReviewReadiness,
	GroupRef,
	ItemRef,
	PracticeGroupStanding,
	PracticeProfileOverview,
	PracticeStanding,
	ProfileChange,
	ProfileReviewRef,
	TrendSupport,
} from "@/api/types.gen";
import { isSettledStanding } from "@/components/practice-vocabulary/practice-group-standing-defs";
import { ITEM_KIND } from "@/lib/item-kinds";

import { inStoryYear } from "./story-clock";

const PULL_REQUESTS = "https://github.com/HephaestusTest/practice-validation/pull/";
const ISSUES = "https://github.com/HephaestusTest/MaxTestRepo/issues/";

const item = (fields: Pick<ItemRef, "id" | "kind" | "label"> & Partial<ItemRef>): ItemRef => ({
	parentId: null,
	provider: null,
	title: null,
	url: null,
	container: null,
	path: null,
	removedAt: null,
	...fields,
});

export const pullRequest = (number: number): ItemRef =>
	item({
		id: String(1000 + number),
		kind: ITEM_KIND.pullRequest,
		provider: "GITHUB",
		label: `#${number}`,
		url: `${PULL_REQUESTS}${number}`,
		container: "HephaestusTest/practice-validation",
	});

export const issue = (number: number): ItemRef =>
	item({
		id: String(2000 + number),
		kind: ITEM_KIND.issue,
		provider: "GITHUB",
		label: `#${number}`,
		url: `${ISSUES}${number}`,
		container: "HephaestusTest/MaxTestRepo",
	});

const CHANNELS = ["#releases", "#incidents", "#backend-review"];

export const conversation = (label: string): ItemRef =>
	item({
		id: String(3000 + CHANNELS.indexOf(label) + 1),
		kind: ITEM_KIND.thread,
		provider: "SLACK",
		label,
	});

export const document = (id: number, title: string): ItemRef =>
	item({
		id: String(4000 + id),
		kind: ITEM_KIND.document,
		provider: "OUTLINE",
		label: title,
		title,
		url: `https://outline.example.com/doc/${id}`,
	});

/** Enough work on both sides of the comparison for a trend to be read. */
export const wellSupported: TrendSupport = {
	currentOpportunities: 4,
	previousOpportunities: 4,
	opportunities: 8,
	opportunitiesUntilComparable: 0,
	standingWork: 4,
	reviewedWork: 11,
	reviewedWorkKind: ITEM_KIND.pullRequest,
	bundleSize: 4,
	ropeHalfWidth: 0.15,
	credibilityThreshold: 0.9,
	calendarSpanDays: 12,
	comparablePractices: 2,
	eligiblePractices: 3,
	firstOpportunityAt: inStoryYear("08-28T10:00"),
	lastOpportunityAt: inStoryYear("09-09T14:10"),
};

const group = (slug: string, name: string, icon: string, color: string): GroupRef => ({
	slug,
	name,
	icon,
	color,
});

/** The group the detail levels open in their stories and tests: the first of the catalog. */
export const packagingGroup = group(
	"review-ready-work",
	"Packaging work for review",
	"Package",
	"sky",
);

/** The shipped catalog's groups, by slug, so the overview's sentences find their rows. */
export const groups: GroupRef[] = [
	packagingGroup,
	group("acting-on-review-feedback", "Acting on review feedback", "MessageSquareReply", "cyan"),
	group("communication", "Communicating in the open", "MessageCircle", "violet"),
	group("constructive-code-review", "Reviewing a teammate's work constructively", "Eye", "teal"),
	group("testing-discipline", "Testing your changes", "TestTube", "amber"),
];

const groupStanding = (
	slug: string,
	standing: PracticeGroupStanding["standing"],
	direction: PracticeGroupStanding["direction"] = null,
): PracticeGroupStanding => {
	const ref = groups.find((candidate) => candidate.slug === slug);
	return {
		groupSlug: slug,
		groupName: ref?.name ?? slug,
		groupDescription: null,
		groupIcon: ref?.icon ?? null,
		groupColor: ref?.color ?? null,
		standing,
		direction,
		trendSupport: direction ? wellSupported : null,
		results: [],
		sources: standing === "NOT_OBSERVED" ? [] : [{ itemKind: ITEM_KIND.pullRequest, count: 6 }],
		feedbackSince: standing === "NOT_OBSERVED" ? null : inStoryYear("07-30T09:00"),
		feedbackSpanDays: standing === "NOT_OBSERVED" ? null : 42,
		guidance: null,
		guidanceSource: null,
		basis: isSettledStanding(standing) ? "CURRENT_REVIEWS" : null,
	};
};

/** Where the developer stands in the group the detail levels open. */
export const packagingStanding = groupStanding(packagingGroup.slug, "DEVELOPING", "DECLINING");

export const groupStandings: Record<string, PracticeGroupStanding> = {
	[packagingGroup.slug]: packagingStanding,
	"acting-on-review-feedback": groupStanding("acting-on-review-feedback", "MIXED", "UNCERTAIN"),
	communication: groupStanding("communication", "DEVELOPING"),
	"constructive-code-review": groupStanding("constructive-code-review", "STRENGTH"),
	"testing-discipline": groupStanding("testing-discipline", "NOT_OBSERVED"),
};

export const practiceStandingOf = (
	fields: Pick<PracticeStanding, "slug" | "name" | "standing"> & Partial<PracticeStanding>,
): PracticeStanding => ({
	groupSlug: null,
	groupName: null,
	direction: null,
	doesNotApplyOnly: false,
	trendSupport: null,
	whyItMatters: null,
	whatGoodLooksLike: null,
	strengths: [],
	toWorkOn: [],
	// A verdict reads from current reviews unless the story says otherwise.
	basis: isSettledStanding(fields.standing) ? "CURRENT_REVIEWS" : null,
	...fields,
});

const practice = (
	groupSlug: string,
	slug: string,
	name: string,
	standing: PracticeStanding["standing"],
): PracticeStanding =>
	practiceStandingOf({
		slug,
		name,
		groupSlug,
		groupName: groups.find((candidate) => candidate.slug === groupSlug)?.name ?? null,
		standing,
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

const LATEST_REVIEW_AT = inStoryYear("09-09T14:10");

export const LATEST_REVIEW: ProfileReviewRef = {
	reviewId: "5d1e2a3b-0000-4000-8000-000000000909",
	at: LATEST_REVIEW_AT,
	item: pullRequest(22),
	status: "COMPLETE",
};

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
	at: LATEST_REVIEW_AT,
	practiceSlug: null,
	practiceName: null,
	...inGroup,
	...onPractice,
	cleanNeeded: null,
	direction: null,
	feedbackId: null,
	from: null,
	to: null,
	resolvedBy: null,
	evidence: [],
	...rest,
});

const PACKAGING_CHANGES: ProfileChange[] = [
	change(
		"FEEDBACK_RESOLVED",
		PACKAGING,
		{ practiceSlug: "describe-what-and-why", practiceName: "Describe what changed and why" },
		{
			feedbackId: "describe-what-and-why-resolved",
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
		"FEEDBACK_RESET",
		PACKAGING,
		{
			practiceSlug: "honours-linked-issue-acceptance-criteria",
			practiceName: "Say which acceptance criteria are done",
		},
		{ feedbackId: "acceptance-criteria", cleanNeeded: 3, evidence: [pullRequest(22)] },
	),
	change(
		"STANDING_MOVED",
		PACKAGING,
		{
			practiceSlug: "reviewable-diff-size",
			practiceName: "Keep the diff reviewable in one sitting",
		},
		{ from: "MIXED", to: "DEVELOPING", direction: "DOWN", evidence: [21, 22].map(pullRequest) },
	),
	change(
		"STANDING_MOVED",
		PACKAGING,
		{
			practiceSlug: "ready-and-traceable-handoff",
			practiceName: "Mark the change ready and link its issue",
		},
		{ from: "MIXED", to: "STRENGTH", direction: "UP", evidence: [21, 22].map(pullRequest) },
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
		"FIRST_RECORDED",
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
		direction: "UP",
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
			at: inStoryYear("09-02T11:40"),
			feedbackId: "review-comments-specific-resolved",
			resolvedBy: "WORK",
			evidence: [1, 4, 23].map(pullRequest),
		},
	),
	change(
		"STANDING_MOVED",
		REVIEWING,
		{
			practiceSlug: "reviews-respectfully-asks-rather-than-demands",
			practiceName: "Comment on the code, not the person",
		},
		{ from: "MIXED", to: "STRENGTH", direction: "UP", evidence: [4, 23].map(pullRequest) },
	),
	change(
		"STANDING_MOVED",
		REVIEWING,
		{
			practiceSlug: "reviews-substantively-with-understanding",
			practiceName: "Read the change before approving it",
		},
		{ from: "DEVELOPING", to: "MIXED", direction: "UP", evidence: [23].map(pullRequest) },
	),
];

/** A member whose work Hephaestus reviews when they open a pull request or mark it ready. */
export const READY_REVIEW_READINESS: MemberReviewReadiness = {
	blocker: null,
	events: ["scm.pull_request.opened", "scm.pull_request.ready"],
	approvalBeforePosting: false,
};

export const SHARED_TRANSITION_OVERVIEW: PracticeProfileOverview = {
	latestReview: LATEST_REVIEW,
	activeReview: null,
	reviewReadiness: READY_REVIEW_READINESS,
	reviewedItems: [pullRequest(22)],
	window: { since: inStoryYear("09-02T09:00"), until: LATEST_REVIEW_AT },
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
			"FIRST_RECORDED",
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
			{ from: "MIXED", to: "STRENGTH", direction: "UP", evidence: [pullRequest(22)] },
		),
		// The move the practice above made, so the group rides on that bullet rather than taking one.
		change("GROUP_MOVED", PACKAGING, undefined, {
			from: "MIXED",
			to: "STRENGTH",
			direction: "UP",
			evidence: [pullRequest(22)],
		}),
		// A move no practice in the group made, so the group keeps a bullet of its own.
		change("GROUP_MOVED", COMMUNICATION, undefined, {
			from: "DEVELOPING",
			to: "MIXED",
			direction: "UP",
			evidence: [pullRequest(21)],
		}),
	],
};

export const OVERVIEW_FIXTURE: PracticeProfileOverview = {
	latestReview: LATEST_REVIEW,
	activeReview: null,
	reviewReadiness: READY_REVIEW_READINESS,
	reviewedItems: [pullRequest(22)],
	window: { since: inStoryYear("09-02T09:00"), until: LATEST_REVIEW_AT },
	changes: [...PACKAGING_CHANGES, ...COMMUNICATION_CHANGES, ...REVIEWING_CHANGES],
};
