import type {
	ArtifactTrace,
	DeliveryPolicyTrace,
	PagedModelTracedArtifact,
	PracticeSignal,
	PracticeTraceEntry,
	ReviewedWorkRef,
	TracedArtifact,
	TracedSignal,
} from "@/api/types.gen";

/**
 * Typed as the generated views rather than as `Wire<…>` of them, with real `Date`s: these fixtures
 * are handed straight to props. Route tests still serve them through MSW, and `HttpResponse.json`
 * stringifies a `Date` back into the ISO string the wire carries — the same value the client's
 * transformer revives on the way in.
 */

const READY = {
	signal: "scm.pull_request.ready",
	displayName: "Marked ready for review",
} satisfies PracticeSignal;
const PUSHED = {
	signal: "scm.pull_request.synchronized",
	displayName: "New commits pushed",
} satisfies PracticeSignal;
const ASKED_BY_HAND = {
	signal: "scm.pull_request.manual_review",
	displayName: "Review requested by hand",
} satisfies PracticeSignal;
const REVIEW_SUBMITTED = {
	signal: "scm.pull_request.review_submitted",
	displayName: "Review submitted",
} satisfies PracticeSignal;
const REPLIED = {
	signal: "chat.conversation_thread.replied",
	displayName: "Replied",
} satisfies PracticeSignal;
const ISSUE_OPENED = { signal: "scm.issue.opened", displayName: "Opened" } satisfies PracticeSignal;

const PULL_REQUEST_1423 = {
	id: "1423",
	kind: "scm.pull_request",
	provider: "GITHUB",
	label: "#1423",
	title: "Member-facing review activity: say why a practice stayed quiet",
	url: "https://github.com/ls1intum/Hephaestus/pull/1423",
	container: "ls1intum/Hephaestus",
} satisfies ReviewedWorkRef;

const ISSUE_1430 = {
	id: "1430",
	kind: "scm.issue",
	provider: "GITHUB",
	label: "#1430",
	title: "Define the practice-binding contract",
	url: "https://github.com/ls1intum/Hephaestus/issues/1430",
	container: "ls1intum/Hephaestus",
} satisfies ReviewedWorkRef;

export const tracedArtifacts = [
	{
		artifactKind: "scm.pull_request",
		artifactId: 1423,
		reviewedWork: PULL_REQUEST_1423,
		lastSignalAt: new Date("2026-08-07T09:12:00Z"),
		signalCount: 6,
		reviewedSignalCount: 2,
	},
	{
		artifactKind: "scm.pull_request",
		artifactId: 1418,
		reviewedWork: {
			id: "1418",
			kind: "scm.pull_request",
			provider: "GITHUB",
			label: "#1418",
			title: "Scope review to the branches and repositories a workspace picks",
			url: "https://github.com/ls1intum/Hephaestus/pull/1418",
			container: "ls1intum/Hephaestus",
		} satisfies ReviewedWorkRef,
		lastSignalAt: new Date("2026-08-06T16:40:00Z"),
		signalCount: 3,
		reviewedSignalCount: 1,
	},
	{
		artifactKind: "scm.issue",
		artifactId: 1430,
		reviewedWork: ISSUE_1430,
		lastSignalAt: new Date("2026-08-05T11:02:00Z"),
		signalCount: 2,
		reviewedSignalCount: 0,
	},
	{
		// No number, no container, no upstream link: a deleted or unlinkable artifact still lists.
		artifactKind: "chat.conversation_thread",
		artifactId: 88,
		reviewedWork: {
			id: "88",
			kind: "chat.conversation_thread",
			label: "Conversation",
		} satisfies ReviewedWorkRef,
		lastSignalAt: new Date("2026-08-04T08:30:00Z"),
		signalCount: 1,
		reviewedSignalCount: 0,
	},
	{
		artifactKind: "docs.document",
		artifactId: 512,
		reviewedWork: {
			id: "512",
			kind: "docs.document",
			provider: "OUTLINE",
			label: "Onboarding: your first week",
			container: "Engineering handbook",
			url: "https://outline.example.com/doc/onboarding-your-first-week",
		} satisfies ReviewedWorkRef,
		lastSignalAt: new Date("2026-08-03T14:15:00Z"),
		signalCount: 2,
		reviewedSignalCount: 1,
	},
] satisfies TracedArtifact[];

/**
 * `scm.pull_request.synchronized` appears twice at different revisions. That collision is the
 * point: the name cannot say which push an answer rests on, so practice rows link through the ids.
 */
export const tracedSignals = [
	{
		id: "sig-opened",
		signal: "scm.pull_request.opened",
		displayName: "Opened as a draft",
		revision: "27f4e88c",
		occurredAt: new Date("2026-08-05T08:02:00Z"),
		discoveredVia: "EVENT",
		state: "SUPPRESSED",
		stateReason: "GATE_SKIPPED",
		stateReasonDescription: "This workspace's review settings turned it away.",
	},
	{
		id: "sig-ready",
		signal: "scm.pull_request.ready",
		displayName: "Marked ready for review",
		revision: "27f4e88c",
		occurredAt: new Date("2026-08-06T10:15:00Z"),
		discoveredVia: "EVENT",
		state: "TRIGGERED",
		reviewId: "11111111-1111-1111-1111-111111111111",
	},
	{
		id: "sig-sync-9ab3c410",
		signal: "scm.pull_request.synchronized",
		displayName: "New commits pushed",
		revision: "9ab3c410",
		occurredAt: new Date("2026-08-06T14:48:00Z"),
		discoveredVia: "SYNC",
		state: "SUPPRESSED",
		stateReason: "COOLDOWN_ACTIVE",
		stateReasonDescription:
			"This work was already reviewed within the workspace's cooldown period.",
	},
	{
		id: "sig-review-requested",
		signal: "scm.pull_request.manual_review",
		displayName: "Review requested",
		revision: "9ab3c410",
		occurredAt: new Date("2026-08-07T08:30:00Z"),
		discoveredVia: "MANUAL",
		state: "PENDING",
	},
	{
		id: "sig-sync-b71d0a52",
		signal: "scm.pull_request.synchronized",
		displayName: "New commits pushed",
		revision: "b71d0a52",
		occurredAt: new Date("2026-08-07T09:12:00Z"),
		discoveredVia: "BACKFILL",
		state: "LAPSED",
		stateReason: "PENDING_DEADLINE_EXCEEDED",
		stateReasonDescription: "It waited too long to be picked up for review.",
	},
] satisfies TracedSignal[];

const practiceTraceEntries = [
	{
		practiceSlug: "thin-controllers",
		practiceName: "Thin controllers",
		groupSlug: "review-ready-work",
		groupName: "Packaging work for review",
		autonomy: "AUTOMATIC",
		outcome: "REVIEWED",
		explanation:
			"Reviewed on the commits pushed at 14:48. Three observations were recorded and one was raised with you; the rest repeated a point already made on this pull request.",
		watches: [READY, PUSHED],
		occasionedBy: READY,
		occasionedById: "sig-ready",
		decidedAt: new Date("2026-08-06T10:19:00Z"),
		reviewId: "11111111-1111-1111-1111-111111111111",
		observationCount: 3,
		deliveredCount: 1,
		withheldReasons: ["COMPOSER_DEDUPED"],
	},
	{
		practiceSlug: "product-language",
		practiceName: "Product language",
		groupSlug: "communication",
		groupName: "Communicating in the open",
		autonomy: "HUMAN_APPROVAL",
		outcome: "REVIEWED",
		explanation:
			"Reviewed, and two observations were recorded. This practice is set to Review before sending, so nothing was said to you; raise its autonomy to Send automatically to hear about results like these.",
		watches: [READY],
		occasionedBy: READY,
		occasionedById: "sig-ready",
		decidedAt: new Date("2026-08-06T10:19:00Z"),
		reviewId: "11111111-1111-1111-1111-111111111111",
		observationCount: 2,
		deliveredCount: 0,
		withheldReasons: ["PRACTICE_REQUIRES_APPROVAL"],
	},
	{
		practiceSlug: "meaningful-commits",
		practiceName: "Meaningful commit history",
		groupSlug: "review-ready-work",
		groupName: "Packaging work for review",
		autonomy: "AUTOMATIC",
		outcome: "RUNNING",
		explanation:
			"A review started when the last commits landed and has not finished yet. Check back in a few minutes.",
		watches: [PUSHED],
		occasionedBy: PUSHED,
		occasionedById: "sig-sync-b71d0a52",
		reviewId: "22222222-2222-2222-2222-222222222222",
		observationCount: 0,
		deliveredCount: 0,
		withheldReasons: [],
	},
	{
		practiceSlug: "tests-accompany-behaviour",
		practiceName: "Tests accompany behaviour changes",
		groupSlug: "testing-discipline",
		groupName: "Testing your changes",
		autonomy: "AUTOMATIC",
		outcome: "PENDING",
		explanation:
			"Queued behind the reviews already running for this workspace. It will start on its own; nothing is needed from you.",
		// Only lifecycle moments are watched: asking by hand is not one of them, and it still ran this
		// review, because such a request reviews every practice on the work type.
		watches: [READY],
		occasionedBy: ASKED_BY_HAND,
		occasionedById: "sig-review-requested",
		observationCount: 0,
		deliveredCount: 0,
		withheldReasons: [],
	},
	{
		practiceSlug: "small-changes",
		practiceName: "Small, reviewable changes",
		groupSlug: "review-ready-work",
		groupName: "Packaging work for review",
		autonomy: "AUTOMATIC",
		outcome: "SKIPPED",
		explanation:
			"Skipped because this pull request was reviewed 40 minutes ago and the workspace's cooldown is one hour. The next push after that window will be reviewed.",
		watches: [PUSHED],
		occasionedBy: PUSHED,
		occasionedById: "sig-sync-9ab3c410",
		decidedAt: new Date("2026-08-06T14:48:00Z"),
		observationCount: 0,
		deliveredCount: 0,
		withheldReasons: [],
	},
	{
		practiceSlug: "clear-ownership",
		practiceName: "Clear ownership",
		groupSlug: "acting-on-review-feedback",
		groupName: "Acting on review feedback",
		autonomy: "AUTOMATIC",
		outcome: "NOT_REACHED",
		explanation: "The review ended before reaching this practice.",
		watches: [READY],
		occasionedBy: READY,
		occasionedById: "sig-ready",
		decidedAt: new Date("2026-08-06T10:19:00Z"),
		reviewId: "11111111-1111-1111-1111-111111111111",
		observationCount: 0,
		deliveredCount: 0,
		withheldReasons: [],
	},
	{
		practiceSlug: "migration-safety",
		practiceName: "Migration safety",
		groupSlug: "testing-discipline",
		groupName: "Testing your changes",
		autonomy: "AUTOMATIC",
		outcome: "NOT_ASSESSABLE",
		explanation:
			"The diff for these commits could not be read, so this practice could not be judged either way. Re-run once the provider serves the diff again.",
		watches: [READY],
		occasionedBy: READY,
		occasionedById: "sig-ready",
		decidedAt: new Date("2026-08-06T10:19:00Z"),
		reviewId: "11111111-1111-1111-1111-111111111111",
		observationCount: 0,
		deliveredCount: 0,
		withheldReasons: [],
	},
	{
		practiceSlug: "descriptive-pull-requests",
		practiceName: "Descriptive pull requests",
		groupSlug: "review-ready-work",
		groupName: "Packaging work for review",
		autonomy: "OFF",
		outcome: "TURNED_OFF",
		explanation:
			"This workspace has turned this practice off, so it was not run. A workspace admin can turn it back on in the practice settings.",
		watches: [READY],
		observationCount: 0,
		deliveredCount: 0,
		withheldReasons: [],
	},
	{
		practiceSlug: "timely-review-response",
		practiceName: "Timely review response",
		groupSlug: "acting-on-review-feedback",
		groupName: "Acting on review feedback",
		autonomy: "AUTOMATIC",
		outcome: "NOT_OCCASIONED",
		explanation:
			"Nothing this practice watches for has happened on this pull request yet. It reacts when a review is submitted.",
		watches: [REVIEW_SUBMITTED],
		observationCount: 0,
		deliveredCount: 0,
		withheldReasons: [],
	},
	{
		practiceSlug: "discussion-hygiene",
		practiceName: "Discussion hygiene",
		groupSlug: "communication",
		groupName: "Communicating in the open",
		autonomy: "AUTOMATIC",
		outcome: "DORMANT",
		explanation:
			"This practice needs a chat integration, and this workspace has none connected. It will start answering once one is.",
		watches: [REPLIED],
		observationCount: 0,
		deliveredCount: 0,
		withheldReasons: [],
	},
	{
		practiceSlug: "draft-not-left-open",
		practiceName: "Drafts are not left open",
		groupSlug: "acting-on-review-feedback",
		groupName: "Acting on review feedback",
		autonomy: "AUTOMATIC",
		outcome: "LAPSED",
		explanation:
			"This one waited longer than the workspace allows before a reviewer was free, so the question expired unanswered. A new push will ask it again.",
		watches: [PUSHED],
		occasionedBy: PUSHED,
		occasionedById: "sig-sync-b71d0a52",
		decidedAt: new Date("2026-08-07T09:42:00Z"),
		observationCount: 0,
		deliveredCount: 0,
		withheldReasons: [],
	},
	{
		practiceSlug: "dependency-risk",
		practiceName: "Dependency risk",
		autonomy: "AUTOMATIC",
		outcome: "FAILED",
		explanation:
			"The review of this practice failed with an error and produced nothing. It will be retried on the next push; tell a workspace admin if it keeps failing.",
		watches: [READY],
		occasionedBy: READY,
		occasionedById: "sig-ready",
		decidedAt: new Date("2026-08-06T10:21:00Z"),
		reviewId: "33333333-3333-3333-3333-333333333333",
		observationCount: 0,
		deliveredCount: 0,
		withheldReasons: [],
	},
] satisfies PracticeTraceEntry[];

export const deniedDeliveryPolicyEvaluation: DeliveryPolicyTrace = {
	reviewId: "22222222-2222-2222-2222-222222222222",
	admittedRevision: 4,
	evaluatedRevision: 5,
	facts: {
		artifactKind: "scm.pull_request",
		repository: "ls1intum/Hephaestus",
		baseBranch: "develop",
		subject: "RESOLVED_LINKED_HUMAN",
		repositoryMode: "SELECTED",
		personMode: "SELECTED",
		repositoryMatched: true,
		branchMatched: true,
		personMatched: true,
		recipientConsent: false,
		deliveryStatus: "ACTIVE",
		triggerMode: "AUTO",
		contributingPractices: [{ slug: "review-feedback", autonomy: "AUTOMATIC" }],
	},
	resolverVersion: "1",
	surface: "ARTIFACT",
	stage: "EGRESS",
	allowed: false,
	decisiveReason: "RECIPIENT_OPTED_OUT",
	evaluatedAt: new Date("2026-08-06T10:20:30Z"),
	checks: [
		{ check: "INSTANCE_SILENT_MODE", status: "PASSED" },
		{ check: "WORKSPACE_ENABLED", status: "PASSED" },
		{ check: "ROLLOUT_REVISION", status: "PASSED" },
		{ check: "WORKSPACE_DELIVERY", status: "PASSED" },
		{ check: "CURRENT_COVERAGE", status: "PASSED" },
		{ check: "PRACTICE_AUTHORITY", status: "PASSED" },
		{ check: "RECIPIENT_CONSENT", status: "DENIED" },
		{ check: "ARTIFACT_ELIGIBILITY", status: "NOT_REACHED" },
	],
};

export const artifactTrace = {
	artifactKind: "scm.pull_request",
	artifactId: 1423,
	reviewedWork: PULL_REQUEST_1423,
	signals: tracedSignals,
	practices: practiceTraceEntries,
} satisfies ArtifactTrace;

export const untouchedArtifactTrace = {
	artifactKind: "scm.issue",
	artifactId: 1430,
	reviewedWork: ISSUE_1430,
	signals: [
		{
			id: "sig-issue-opened",
			signal: "scm.issue.opened",
			displayName: "Opened",
			revision: "1",
			occurredAt: new Date("2026-08-05T11:02:00Z"),
			discoveredVia: "SYNC",
			state: "SUPPRESSED",
			stateReason: "NO_ACTIVE_PRACTICE",
			stateReasonDescription: "No practice was watching for this when it happened.",
		},
	],
	practices: [
		{
			practiceSlug: "issue-hygiene",
			practiceName: "Issue hygiene",
			autonomy: "OFF",
			outcome: "TURNED_OFF",
			explanation:
				"This workspace has turned this practice off, so it was not run. A workspace admin can turn it back on in the practice settings.",
			watches: [ISSUE_OPENED],
			observationCount: 0,
			deliveredCount: 0,
			withheldReasons: [],
		},
	],
} satisfies ArtifactTrace;

export function tracedArtifact(artifactId: number) {
	const match = tracedArtifacts.find((candidate) => candidate.artifactId === artifactId);
	if (!match) {
		throw new Error(`No traced-artifact fixture with id ${artifactId}`);
	}
	return match;
}

export function tracedArtifactPage(
	content: TracedArtifact[] = tracedArtifacts,
	size = 20,
): PagedModelTracedArtifact {
	return {
		content,
		page: {
			number: 0,
			size,
			totalElements: content.length,
			totalPages: Math.max(1, Math.ceil(content.length / size)),
		},
	};
}
