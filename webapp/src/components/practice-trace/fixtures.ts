import type {
	FeedbackCheck,
	ItemEvent,
	ItemRef,
	ItemTrace,
	OwnItemTrace,
	PracticeScriptRun,
	PracticeTraceEntry,
	ResultList,
	ReviewRef,
} from "@/api/types.gen";

/**
 * Typed as the generated views rather than as `Wire<…>` of them, with real `Date`s: these fixtures
 * are handed straight to props. Route tests still serve them through MSW, and `HttpResponse.json`
 * stringifies a `Date` back into the ISO string the wire carries — the same value the client's
 * transformer revives on the way in.
 */

const EVENT_LABELS: Record<string, string> = {
	"scm.pull_request.opened": "Opened",
	"scm.pull_request.ready": "Marked ready for review",
	"scm.pull_request.synchronized": "New commits pushed",
	"scm.pull_request.edited": "Edited",
	"scm.issue.opened": "Opened",
};

export function eventLabel(name: string): string {
	return EVENT_LABELS[name] ?? name;
}

const NO_RESULTS = { met: 0, notMet: 0, doesNotApply: 0, couldNotDetermine: 0 };

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

const PULL_REQUEST_1423 = item({
	id: "1423",
	kind: "scm.pull_request",
	provider: "GITHUB",
	label: "#1423",
	title: "Member-facing review activity: say why a practice stayed quiet",
	url: "https://github.com/ls1intum/Hephaestus/pull/1423",
	container: "ls1intum/Hephaestus",
});

const ISSUE_1430 = item({
	id: "1430",
	kind: "scm.issue",
	provider: "GITHUB",
	label: "#1430",
	title: "Define the practice-binding contract",
	url: "https://github.com/ls1intum/Hephaestus/issues/1430",
	container: "ls1intum/Hephaestus",
});

export const tracedItems: ItemRef[] = [
	PULL_REQUEST_1423,
	item({
		id: "1418",
		kind: "scm.pull_request",
		provider: "GITHUB",
		label: "#1418",
		title: "Scope review to the branches and repositories a workspace picks",
		url: "https://github.com/ls1intum/Hephaestus/pull/1418",
		container: "ls1intum/Hephaestus",
	}),
	ISSUE_1430,
	item({
		id: "88",
		kind: "chat.thread",
		label: "Conversation",
		removedAt: new Date("2026-08-05T08:30:00Z"),
	}),
	item({
		id: "512",
		kind: "docs.document",
		provider: "OUTLINE",
		label: "Onboarding: your first week",
		container: "Engineering handbook",
		url: "https://outline.example.com/doc/onboarding-your-first-week",
	}),
];

const READY_REVIEW = "11111111-1111-1111-1111-111111111111";
const PUSH_REVIEW = "22222222-2222-2222-2222-222222222222";

const readyReview: ReviewRef = {
	id: READY_REVIEW,
	kind: "ITEM",
	item: PULL_REQUEST_1423,
	itemVersion: "27f4e88c",
	origin: "EVENT",
	status: "COMPLETE",
	createdAt: new Date("2026-08-06T10:15:00Z"),
	completedAt: new Date("2026-08-06T10:21:00Z"),
};

const pushReview: ReviewRef = {
	id: PUSH_REVIEW,
	kind: "ITEM",
	item: PULL_REQUEST_1423,
	itemVersion: "b71d0a52",
	origin: "EVENT",
	status: "RUNNING",
	createdAt: new Date("2026-08-07T08:30:00Z"),
	completedAt: null,
};

export const traceReviews: ReviewRef[] = [readyReview, pushReview];

export const itemEvents: ItemEvent[] = [
	{
		id: "event-opened",
		itemId: "1423",
		name: "scm.pull_request.opened",
		itemVersion: "27f4e88c",
		occurredAt: new Date("2026-08-05T08:02:00Z"),
		foundBy: "WEBHOOK",
		state: "WITHHELD",
		reason: "GATE_WITHHELD",
		decidedAt: new Date("2026-08-05T08:02:00Z"),
		reviewId: null,
	},
	{
		id: "event-ready",
		itemId: "1423",
		name: "scm.pull_request.ready",
		itemVersion: "27f4e88c",
		occurredAt: new Date("2026-08-06T10:15:00Z"),
		foundBy: "WEBHOOK",
		state: "STARTED",
		reason: null,
		decidedAt: new Date("2026-08-06T10:15:00Z"),
		reviewId: READY_REVIEW,
	},
	{
		id: "event-sync-9ab3c410",
		itemId: "1423",
		name: "scm.pull_request.synchronized",
		itemVersion: "9ab3c410",
		occurredAt: new Date("2026-08-06T14:48:00Z"),
		foundBy: "SYNC",
		state: "WITHHELD",
		reason: "COOLDOWN_ACTIVE",
		decidedAt: new Date("2026-08-06T14:48:00Z"),
		reviewId: null,
	},
	{
		id: "event-sync-b71d0a52",
		itemId: "1423",
		name: "scm.pull_request.synchronized",
		itemVersion: "b71d0a52",
		occurredAt: new Date("2026-08-07T08:30:00Z"),
		foundBy: "WEBHOOK",
		state: "STARTED",
		reason: null,
		decidedAt: new Date("2026-08-07T08:30:00Z"),
		reviewId: PUSH_REVIEW,
	},
	{
		id: "event-edited",
		itemId: "1423",
		name: "scm.pull_request.edited",
		itemVersion: "b71d0a52",
		occurredAt: new Date("2026-08-07T09:12:00Z"),
		foundBy: "BACKFILL",
		state: "EXPIRED",
		reason: "PENDING_DEADLINE_EXCEEDED",
		decidedAt: new Date("2026-08-07T10:12:00Z"),
		reviewId: null,
	},
];

const practiceTraceEntries: PracticeTraceEntry[] = [
	{
		practiceSlug: "thin-controllers",
		feedbackMode: "AUTOMATIC",
		reviewOn: {
			events: ["scm.pull_request.ready", "scm.pull_request.synchronized"],
			state: {},
		},
		about: "AUTHOR",
		review: readyReview,
		results: { ...NO_RESULTS, notMet: 1, met: 2 },
		withheldReasons: [
			{
				reason: "REPEATS_DELIVERED_NOTE",
				description: "It repeated a point already made on this work.",
				count: 2,
			},
		],
	},
	{
		practiceSlug: "product-language",
		feedbackMode: "APPROVE_FIRST",
		reviewOn: { events: ["scm.pull_request.ready"], state: {} },
		about: "AUTHOR",
		review: readyReview,
		results: { ...NO_RESULTS, notMet: 2 },
		withheldReasons: [
			{
				reason: "PRACTICE_REQUIRES_APPROVAL",
				description: "This practice waits for an admin to approve its feedback.",
				count: 2,
			},
		],
	},
	{
		practiceSlug: "meaningful-commits",
		feedbackMode: "AUTOMATIC",
		reviewOn: { events: ["scm.pull_request.synchronized"], state: {} },
		about: "AUTHOR",
		review: pushReview,
		results: NO_RESULTS,
		withheldReasons: [],
	},
	{
		practiceSlug: "descriptive-pull-requests",
		feedbackMode: "OFF",
		reviewOn: { events: ["scm.pull_request.ready"], state: {} },
		about: null,
		review: readyReview,
		results: { ...NO_RESULTS, met: 1 },
		withheldReasons: [],
	},
	{
		practiceSlug: "timely-review-response",
		feedbackMode: "AUTOMATIC",
		reviewOn: { events: ["scm.pull_request_review.submitted"], state: {} },
		about: "REVIEWER",
		review: null,
		results: NO_RESULTS,
		withheldReasons: [],
	},
	{
		practiceSlug: "discussion-hygiene",
		feedbackMode: "AUTOMATIC",
		reviewOn: { events: ["chat.thread.settled"], state: {} },
		about: "AUTHOR",
		review: null,
		results: NO_RESULTS,
		withheldReasons: [],
	},
];

const script = (
	fields: Pick<PracticeScriptRun, "scriptKind" | "status"> & Partial<PracticeScriptRun>,
): PracticeScriptRun => ({
	jobId: "a1b2c3d4-0000-4000-8000-000000000001",
	attempt: 1,
	practiceId: "41",
	practiceVersionId: "112",
	stopReason: null,
	noResultCause: null,
	finishedAt: new Date("2026-08-06T10:20:00Z"),
	durationMs: 4200,
	error: null,
	reviewModelCalls: null,
	...fields,
});

export const practiceScriptRuns = {
	returned: script({ scriptKind: "CUSTOM", status: "OK" }),
	builtin: script({ scriptKind: "BUILTIN", status: "OK" }),
	withheld: script({ scriptKind: "CUSTOM", status: "WITHHELD", stopReason: "UNAVAILABLE" }),
	outOfTime: script({ scriptKind: "CUSTOM", status: "FAILED", stopReason: "DEADLINE" }),
	evidenceNotRead: script({
		scriptKind: "BUILTIN",
		status: "FAILED",
		stopReason: "OFF_FORMAT",
		noResultCause: "EVIDENCE_NOT_READ",
		reviewModelCalls: 6,
	}),
	notReached: script({ scriptKind: "CUSTOM", status: "NOT_REACHED", durationMs: null }),
	notFinished: script({ scriptKind: "CUSTOM", status: "NOT_FINISHED", durationMs: null }),
	failed: script({
		scriptKind: "CUSTOM",
		status: "FAILED",
		stopReason: "ERROR",
		error: "TypeError: cannot read properties of undefined",
	}),
} satisfies Record<string, PracticeScriptRun>;

export const deniedFeedbackCheck: FeedbackCheck = {
	stage: "EGRESS",
	allowed: false,
	reason: "REVIEWED_VERSION_CHANGED",
	checkedAt: new Date("2026-08-06T10:20:30Z"),
	facts: {
		admittedRolloutRevision: "4",
		evaluatedRolloutRevision: "5",
		inScope: true,
		evidenceAllowed: true,
		modelAllowed: true,
		deliveryEnabled: true,
		incorrectBasis: false,
		currentVersion: false,
		approvalCurrent: true,
		writeStarted: false,
	},
};

export const itemTrace: ItemTrace = {
	item: PULL_REQUEST_1423,
	events: itemEvents,
	reviews: traceReviews,
	practices: practiceTraceEntries,
};

export const untouchedItemTrace: ItemTrace = {
	item: ISSUE_1430,
	events: [
		{
			id: "event-issue-opened",
			itemId: "1430",
			name: "scm.issue.opened",
			itemVersion: "1",
			occurredAt: new Date("2026-08-05T11:02:00Z"),
			foundBy: "SYNC",
			state: "WITHHELD",
			reason: "NO_ACTIVE_PRACTICE",
			decidedAt: new Date("2026-08-05T11:02:00Z"),
			reviewId: null,
		},
	],
	reviews: [],
	practices: [
		{
			practiceSlug: "issue-hygiene",
			feedbackMode: "OFF",
			reviewOn: { events: ["scm.issue.opened"], state: {} },
			about: "AUTHOR",
			review: null,
			results: NO_RESULTS,
			withheldReasons: [],
		},
	],
};

const ownResult = (
	fields: Pick<ResultList, "id" | "practiceSlug" | "practiceName" | "outcome"> &
		Partial<ResultList>,
): ResultList => ({
	reviewId: READY_REVIEW,
	item: PULL_REQUEST_1423,
	severity: null,
	summary: null,
	origin: "EVENT",
	recordedAt: new Date("2026-08-06T10:19:00Z"),
	currentness: "CURRENT",
	markedIncorrectAt: null,
	acceptedAt: null,
	problems: [],
	...fields,
});

export const notMetOwnResult = ownResult({
	id: "00000000-0000-0000-0000-0000000001a1",
	practiceSlug: "thin-controllers",
	practiceName: "Thin controllers",
	outcome: "NOT_MET",
	severity: "MAJOR",
	summary: "Two controllers query the database directly",
	problems: [
		{
			id: "00000000-0000-0000-0000-0000000002a1",
			severity: "MAJOR",
			summary: "OrderController builds its own SQL",
		},
		{
			id: "00000000-0000-0000-0000-0000000002a2",
			severity: "MINOR",
			summary: "InvoiceController reads the repository inline",
		},
	],
});

export const metOwnResult = ownResult({
	id: "00000000-0000-0000-0000-0000000001a2",
	practiceSlug: "descriptive-pull-requests",
	practiceName: "Descriptive pull requests",
	outcome: "MET",
});

export const reusedOwnResult = ownResult({
	id: "00000000-0000-0000-0000-0000000001a3",
	practiceSlug: "product-language",
	practiceName: "Product language",
	outcome: "DOES_NOT_APPLY",
	summary: "The change touches no copy",
	reviewId: "00000000-0000-4000-8000-0000000000e1",
});

export const ownResults: ResultList[] = [notMetOwnResult, metOwnResult, reusedOwnResult];

export const ownItemTrace: OwnItemTrace = {
	item: PULL_REQUEST_1423,
	review: readyReview,
	events: [
		{
			name: "scm.pull_request.opened",
			displayName: "Opened",
			occurredAt: new Date("2026-08-05T08:02:00Z"),
		},
		{
			name: "scm.pull_request.ready",
			displayName: "Marked ready for review",
			occurredAt: new Date("2026-08-06T10:15:00Z"),
		},
	],
	results: ownResults,
};

export function tracedItem(itemId: string) {
	const match = tracedItems.find((candidate) => candidate.id === itemId);
	if (!match) {
		throw new Error(`No traced-item fixture with id ${itemId}`);
	}
	return match;
}
