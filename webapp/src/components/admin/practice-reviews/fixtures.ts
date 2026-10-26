import { addDays } from "date-fns";
import type {
	AccountRef,
	EvidenceCitation,
	FeedbackBasis,
	FeedbackCheck,
	FeedbackCheckFacts,
	FeedbackCopy,
	FeedbackCopyCounts,
	FeedbackDecision,
	FeedbackDisagreement,
	FeedbackResponse,
	FeedbackSend,
	ItemEvent,
	ItemFacts,
	ItemRef,
	ItemTrace,
	OpenQuestion,
	Practice,
	PracticeGroup,
	PracticeScriptRun,
	PracticeVersion,
	Problem,
	ReasonCount,
	ResultExample,
	ResultMark,
	ReviewBoundFeedback,
	ReviewBoundResult,
	ReviewBucket,
	ReviewDetail,
	ReviewDeveloper,
	ReviewFeedback,
	ReviewFeedbackCounts,
	ReviewFeedbackDetail,
	ReviewJob,
	ReviewOverview,
	ReviewPractice,
	ReviewPracticeCounts,
	ReviewProblem,
	ReviewProblemDetail,
	ReviewQueue,
	ReviewQueueItem,
	ReviewRef,
	ReviewResult,
	ReviewResultCounts,
	ReviewResultDetail,
	ReviewSummary,
	ReviewTiming,
	ReviewUsage,
	WorkspaceMembership,
} from "@/api/types.gen";
import { definitionOf } from "@/components/admin/practice-editor/fixtures";
import {
	chosenFeedbackMode,
	mockPracticeOf,
	OWN_ORIGIN,
	practiceVersion,
} from "@/components/admin/practices/fixtures";

import {
	REVIEW_RANGE_DEFS,
	reviewRangeStart,
} from "@/components/admin/practice-reviews/review-range";
import { deniedFeedbackCheck, itemTrace } from "@/components/practice-trace/fixtures";
import { ADMIN_WITHHELD_TEXT } from "@/components/practice-vocabulary/feedback-destination-defs";
import { FEEDBACK_LOCATION_DEFS } from "@/components/practice-vocabulary/feedback-location-defs";
import { withholdingReasonSentence } from "@/components/practice-vocabulary/withholding-defs";
import { toDayParam } from "@/lib/date-range-search";
import { ITEM_KIND } from "@/lib/item-kinds";
import type { FeedbackMode } from "@/lib/practice-feedback-mode";
import { hasText } from "@/lib/text";
import { minutesAfter, minutesBefore, STORY_NOW } from "@/stories/story-clock";

import type { OutcomeScope } from "./review-outcomes";
import type { OverviewRegionState } from "./review-states";

type Provider = AccountRef["identities"][number]["provider"];

function account(
	id: string,
	displayName: string,
	identities: readonly (readonly [Provider, string])[],
	status: AccountRef["status"] = "ACTIVE",
): AccountRef {
	return {
		id,
		displayName,
		avatarUrl: null,
		status,
		identities: identities.map(([provider, login]) => ({
			provider,
			login,
			displayName,
			avatarUrl: null,
		})),
	};
}

export const reviewAccounts = {
	ada: account("7", "Ada Lovelace", [
		["GITHUB", "ada"],
		["GITLAB", "ada.lovelace"],
	]),
	grace: account("9", "Grace Hopper", [["GITHUB", "grace"]]),
	alan: account("11", "Alan Turing", [["GITLAB", "alan"]]),
	katherine: account("14", "Katherine Johnson", [["GITHUB", "katherine"]]),
	barbara: account("18", "Barbara Liskov", [["GITHUB", "barbara"]]),
	margaret: account("23", "Margaret Hamilton", [["GITHUB", "mhamilton"]], "UNCLAIMED"),
} satisfies Record<string, AccountRef>;

function developerOf(owner: AccountRef): ReviewDeveloper {
	return {
		accountId: owner.id,
		displayName: owner.displayName,
		avatarUrl: null,
		providerLogin: owner.identities[0]?.login ?? null,
	};
}

export const reviewDevelopers = {
	ada: developerOf(reviewAccounts.ada),
	grace: developerOf(reviewAccounts.grace),
	alan: developerOf(reviewAccounts.alan),
	katherine: developerOf(reviewAccounts.katherine),
	barbara: developerOf(reviewAccounts.barbara),
	margaret: developerOf(reviewAccounts.margaret),
} satisfies Record<keyof typeof reviewAccounts, ReviewDeveloper>;

const { ada, grace, alan, katherine, barbara } = reviewDevelopers;

const ADMIN_ROLE: Partial<Record<keyof typeof reviewAccounts, WorkspaceMembership["role"]>> = {
	grace: "ADMIN",
};

export const workspaceMembers: WorkspaceMembership[] = Object.entries(reviewAccounts).map(
	([key, member]) => ({
		account: member,
		accountId: member.id,
		role: key in ADMIN_ROLE ? "ADMIN" : "MEMBER",
		joinedAt: new Date("2026-01-01T00:00:00Z"),
		leftAt: null,
		hidden: false,
		reviewSelected: true,
		eligibleForPracticeReview: true,
	}),
);

export function itemRef(
	fields: Pick<ItemRef, "id" | "kind" | "label"> & Partial<ItemRef>,
): ItemRef {
	return {
		parentId: null,
		provider: null,
		title: null,
		url: null,
		container: null,
		path: null,
		removedAt: null,
		...fields,
	};
}

// Every list row and every detail header draws its glyph from the provider and its words from the
// kind, so a fixture on a single provider would let a wrong mark or a missing label ship.

export const reviewItem = itemRef({
	id: "42",
	kind: ITEM_KIND.pullRequest,
	provider: "GITHUB",
	label: "#1423",
	title: "Cache the workspace member lookup on the review path",
	container: "ls1intum/Hephaestus",
	url: "https://github.com/ls1intum/Hephaestus/pull/1423",
});

export const gitlabMergeRequest = itemRef({
	id: "43",
	kind: ITEM_KIND.pullRequest,
	provider: "GITLAB",
	label: "!88",
	title: "Move invoice numbering behind the billing boundary",
	container: "platform/billing-service",
	url: "https://gitlab.example.com/platform/billing-service/-/merge_requests/88",
});

export const slackConversation = itemRef({
	id: "81",
	kind: ITEM_KIND.thread,
	provider: "SLACK",
	label: "#engineering",
	title: "How should we roll back the pricing migration?",
	url: "https://example.slack.com/archives/C01/p1721400000",
});

export const outlineDocument = itemRef({
	id: "96",
	kind: ITEM_KIND.document,
	provider: "OUTLINE",
	label: "Runbook: restoring a workspace from backup",
	url: "https://docs.example.com/doc/runbook-restore",
});

export const trackerIssue = itemRef({
	id: "204",
	kind: ITEM_KIND.issue,
	provider: "GITHUB",
	label: "#204",
	title: "Reviews of documents do not record which version they read",
	container: "ls1intum/Hephaestus",
	url: "https://github.com/ls1intum/Hephaestus/issues/204",
});

export const pullRequestReviewItem = itemRef({
	id: "47",
	kind: ITEM_KIND.pullRequestReview,
	provider: "GITHUB",
	parentId: reviewItem.id,
	label: "Review on #1423",
	container: "ls1intum/Hephaestus",
	url: "https://github.com/ls1intum/Hephaestus/pull/1423#pullrequestreview-2481001",
});

export const scannedRepository = itemRef({
	id: "300",
	kind: ITEM_KIND.repository,
	provider: "GITHUB",
	label: "ls1intum/Hephaestus",
	url: "https://github.com/ls1intum/Hephaestus",
});

const scannedFile = (id: string, path: string) =>
	itemRef({
		id,
		kind: ITEM_KIND.file,
		provider: "GITHUB",
		parentId: scannedRepository.id,
		label: path.slice(path.lastIndexOf("/") + 1),
		path,
		container: "ls1intum/Hephaestus",
		url: `https://github.com/ls1intum/Hephaestus/blob/main/${path}`,
	});

export const scannedFiles = [
	scannedFile(
		"301",
		"server/application/src/main/java/de/tum/cit/aet/hephaestus/integration/core/webhook/WebhookRetryPolicy.java",
	),
	scannedFile(
		"302",
		"server/application/src/main/java/de/tum/cit/aet/hephaestus/practices/reviewoutput/ReviewQueryService.java",
	),
] as const;

const webhookRetryPullRequest = itemRef({
	id: "44",
	kind: ITEM_KIND.pullRequest,
	provider: "GITHUB",
	label: "#1431",
	title: "Retry webhook deliveries with backoff instead of dropping them",
	container: "ls1intum/Hephaestus",
	url: "https://github.com/ls1intum/Hephaestus/pull/1431",
});

const leagueColumnsPullRequest = itemRef({
	id: "45",
	kind: ITEM_KIND.pullRequest,
	provider: "GITHUB",
	label: "#1436",
	title: "Drop the unused league columns",
	container: "ls1intum/Hephaestus",
	url: "https://github.com/ls1intum/Hephaestus/pull/1436",
});

const invoiceBackfillMergeRequest = itemRef({
	id: "46",
	kind: ITEM_KIND.pullRequest,
	provider: "GITLAB",
	label: "!91",
	title: "Backfill the invoice sequence table",
	container: "platform/billing-service",
	url: "https://gitlab.example.com/platform/billing-service/-/merge_requests/91",
});

const groupNames = {
	"code-quality": "Code quality",
	architecture: "Architecture",
	testing: "Testing",
	documentation: "Documentation",
	collaboration: "Collaboration",
} as const;

type GroupSlug = keyof typeof groupNames;

export const practiceGroups: PracticeGroup[] = Object.entries(groupNames).map(
	([slug, name], position) => ({
		id: String(position + 1),
		slug,
		name,
		description: null,
		icon: null,
		color: null,
		shownToDevelopers: true,
		position,
		feedbackMode: chosenFeedbackMode("AUTOMATIC", "GROUP"),
		catalogOrigin: OWN_ORIGIN,
		createdAt: new Date("2026-01-01T00:00:00Z"),
		updatedAt: null,
	}),
);

interface PracticeSpec {
	id: string;
	slug: string;
	name: string;
	kind: string;
	group: GroupSlug;
	criteria: string;
	whyItMatters: string;
	whatGoodLooksLike: string;
	feedbackMode: FeedbackMode;
}

function practiceFixture(spec: PracticeSpec, position: number): Practice {
	return mockPracticeOf({
		id: spec.id,
		slug: spec.slug,
		kind: spec.kind,
		groupSlug: spec.group,
		position,
		feedbackMode: chosenFeedbackMode(spec.feedbackMode),
		definition: definitionOf({
			name: spec.name,
			criteria: spec.criteria,
			guidance: {
				whyItMatters: spec.whyItMatters,
				whatGoodLooksLike: spec.whatGoodLooksLike,
				goingWell: null,
				visual: null,
				guide: null,
			},
		}),
	});
}

const PRACTICE_SPECS: PracticeSpec[] = [
	{
		id: "1",
		slug: "thin-controllers",
		name: "Thin controllers",
		kind: ITEM_KIND.pullRequest,
		group: "code-quality",
		criteria: "A controller validates its input, delegates, and maps the result. Nothing else.",
		whyItMatters:
			"Logic that lives in a controller can only be tested through HTTP, so it tends not to be tested at all.",
		whatGoodLooksLike:
			"The controller method reads as a list of three steps and names a service that does the work.",
		feedbackMode: "APPROVE_FIRST",
	},
	{
		id: "2",
		slug: "product-language",
		name: "Product language",
		kind: ITEM_KIND.pullRequest,
		group: "architecture",
		criteria: "Names in the code are the names the people using it would use.",
		whyItMatters:
			"A boundary named after its storage stops matching the product the day the storage changes, and every reader after that has to translate.",
		whatGoodLooksLike:
			"A route, a class or a column could be read aloud in a planning meeting without explanation.",
		feedbackMode: "AUTOMATIC",
	},
	{
		id: "3",
		slug: "errors-carry-context",
		name: "Errors carry their context",
		kind: ITEM_KIND.pullRequest,
		group: "code-quality",
		criteria:
			"A failure reports which operation failed and on what, not only that something went wrong.",
		whyItMatters:
			"An error that says only what went wrong sends the next person to the logs to work out where, and at three in the morning that is the whole incident.",
		whatGoodLooksLike:
			"The message names the thing being acted on and the action attempted, and two different causes do not share one status code.",
		feedbackMode: "AUTOMATIC",
	},
	{
		id: "4",
		slug: "tests-name-the-behaviour",
		name: "Tests name the behaviour",
		kind: ITEM_KIND.pullRequest,
		group: "testing",
		criteria: "A test's name states the behaviour it pins down, not the method it calls.",
		whyItMatters:
			"A red build should say what broke before anyone opens the file; a name like testCache2 makes the reader run the test to find out.",
		whatGoodLooksLike: "The failure line of a broken build reads as a sentence about the system.",
		feedbackMode: "APPROVE_FIRST",
	},
	{
		id: "5",
		slug: "the-change-explains-itself",
		name: "The change explains itself",
		kind: ITEM_KIND.pullRequest,
		group: "documentation",
		criteria: "The description says why the change was made, not only what it does.",
		whyItMatters:
			"Six months later the diff still says what changed. Nothing else records why, so the next person reverses the decision without knowing there was one.",
		whatGoodLooksLike:
			"A reviewer who was not in the room can tell what problem the change is answering.",
		feedbackMode: "APPROVE_FIRST",
	},
	{
		id: "6",
		slug: "decisions-are-written-down",
		name: "Decisions are written down",
		kind: ITEM_KIND.thread,
		group: "collaboration",
		criteria:
			"A decision reached in a discussion ends up somewhere durable before the thread ends.",
		whyItMatters:
			"A choice that only exists in a chat thread is re-argued every time somebody new joins, and nobody can tell a decision from an opinion.",
		whatGoodLooksLike:
			"The thread ends with what was chosen, who chose it, and a link to where it now lives.",
		feedbackMode: "AUTOMATIC",
	},
	{
		id: "7",
		slug: "review-comments-explain",
		name: "Review comments explain the ask",
		kind: ITEM_KIND.pullRequestReview,
		group: "collaboration",
		criteria: "Each review comment says what to change and why.",
		whyItMatters:
			"A comment that only says what to change starts an argument; one that says why lets the author agree or push back on the reason.",
		whatGoodLooksLike: "Every requested change names the risk it removes.",
		feedbackMode: "OFF",
	},
];

export const workspacePractices: Practice[] = PRACTICE_SPECS.map(practiceFixture);

function practiceOf(slug: string): Practice {
	const practice = workspacePractices.find((entry) => entry.slug === slug);
	if (!practice) {
		throw new Error(`No practice named ${slug} in the fixture catalog`);
	}
	return practice;
}

export function practiceNameOf(slug: string): string {
	const { currentVersion } = practiceOf(slug);
	return currentVersion.name ?? currentVersion.definition?.name ?? slug;
}

function groupRefOf(practiceSlug: string) {
	const practice = practiceOf(practiceSlug);
	const found = practiceGroups.find((entry) => entry.slug === practice.groupSlug);
	return found === undefined
		? null
		: { slug: found.slug, name: found.name, icon: found.icon, color: found.color };
}

type FeedbackState = ReviewFeedback["state"];
type StopReason = NonNullable<ReviewFeedback["stopReason"]>;
type Severity = Problem["severity"];

interface CopySpec {
	kind: FeedbackCopy["kind"];
	state: FeedbackCopy["state"];
	body?: string;
	at?: { path: string; startLine: number; endLine: number };
	lastError?: string;
}

interface DecisionSpec {
	action: FeedbackDecision["action"];
	by: AccountRef | null;
	at: string;
	reasonCode?: NonNullable<FeedbackDecision["reasonCode"]>;
	note?: string;
}

interface ResponseSpec {
	resolution: FeedbackResponse["resolution"];
	usefulness?: FeedbackResponse["usefulness"];
	comment?: string;
	at: string;
}

interface FeedbackSpec {
	id: string;
	body?: string;
	destination: ReviewFeedback["destination"];
	conversation?: "HEPH" | "SLACK";
	state: FeedbackState;
	stopReason?: StopReason;
	composedAt: string;
	deliveredAt?: string;
	/** The feedback this one took the place of. */
	replaces?: string;
	anchoredAt?: { path: string; startLine: number; endLine: number };
	from: string[];
	fromProblem?: Partial<Record<string, number>>;
	copies?: CopySpec[];
	decisions?: DecisionSpec[];
	response?: ResponseSpec;
	withdrawn?: boolean;
}

interface ProblemSpec {
	severity: Severity;
	summary: string;
	explanation: string;
	citations: EvidenceCitation[];
	nextStep?: string;
	basis?: Problem["basis"];
}

interface MarkSpec {
	kind: ResultMark["kind"];
	reason: string;
	by: AccountRef | null;
	at: string;
	removed?: { by: AccountRef | null; at: string; reason: string };
}

interface ResultSpec {
	id: string;
	outcome: ReviewResult["outcome"];
	summary: string | null;
	explanation: string;
	practiceSlug: string;
	currentness?: ReviewResult["currentness"];
	recordedAt: string;
	item?: ItemRef;
	aboutTheWork?: true;
	/** Denied evidence hides text and citations without changing problem counts. */
	evidenceHidden?: true;
	unknownProof?: true;
	evidence?: EvidenceCitation[];
	problems?: ProblemSpec[];
	openQuestions?: OpenQuestion[];
	example?: ResultExample;
	coverage?: ReviewResultDetail["coverage"];
	inapplicability?: { prerequisite: string; ruledOutBy: string; consulted: string[] };
	marks?: MarkSpec[];
}

interface ReviewSpec {
	id: string;
	item: ItemRef;
	kind?: ReviewSummary["kind"];
	status: ReviewSummary["status"];
	stopReason?: NonNullable<ReviewSummary["stopReason"]>;
	startedAt: string;
	developer: ReviewDeveloper | null;
	origin?: ReviewSummary["origin"];
	event?: string;
	requestedBy?: AccountRef;
	batchId?: string;
	pending?: string[];
	withheld?: { practiceSlug: string; reason: string }[];
	jobs?: number;
	runtimeExpired?: true;
	results: ResultSpec[];
	feedback: FeedbackSpec[];
}

const HEAD_COMMIT = "27f4e88c9f5aaaef81d7b2d4521929579f3541e6";

/**
 * A pull request's version as the server's `ScmVersion` writes it: base commit, head commit, and a
 * SHA-256 of the title and description. Pages show it whole, so it is this long here too.
 */
const ITEM_VERSION = `scm.pull_request:v1:1405df66cbe219b0bf6355bc3d60361a8376b6b4:${HEAD_COMMIT}:d6910fe822015c9d16408bdafddf90c65c1f2f586875d17d247bba40cbfd0c32`;

const diff = (
	path: string,
	startLine: number,
	endLine: number,
	quote: string,
): EvidenceCitation => ({
	sourceKind: "scm.pull-request.diff",
	sourcePath: "inputs/context/change.json",
	path,
	side: "NEW",
	commit: HEAD_COMMIT,
	startLine,
	endLine,
	quote,
	verification: {
		status: "VERIFIED",
		itemSha256: "8f14e45fceea167a5a36dedd4bea2543",
		quoteSha256: "c9f0f895fb98ab9159f51fd0297e236d",
	},
});

const cited = (sourceKind: string, path: string, quote: string, line = 1): EvidenceCitation => ({
	sourceKind,
	sourcePath: `inputs/context/${sourceKind.replaceAll(".", "-")}.json`,
	path,
	side: null,
	commit: null,
	startLine: line,
	endLine: line,
	quote,
	verification: null,
});

export const POSTED_SUMMARY_COMMENT = [
	"<!-- hephaestus:practice-review:774b9e9b-2c40-4d6b-9e76-7094dda3a7a2 -->",
	"The description needs its purpose and coverage. Use the description to name the need behind this change and identify which of the linked item's Done-when points it covers or defers.",
	"",
	"The title names the app-problem and user-story task, but the authored description only references issue #3 and gives no reason.",
	"",
	"---",
	"<sub>Practice review &middot; gpt-6-luna. This feedback is AI-generated and can be inaccurate. Answer or disagree in [Hephaestus](https://hephaestus.example/w/aet/feedback/42).</sub>",
	"<sub>[Why you see this and how to stop it](https://hephaestus.example/settings#practice-feedback)</sub>",
	"",
].join("\n");

const LONG_BODY = [
	"2 issues to tighten in this change, plus one thing worth keeping.",
	"",
	"**🔴 A cache miss and a permission failure come back as the same 404** · `ReviewQueryService.java:118`",
	"",
	"You wrote:",
	"",
	"```java",
	"return repository.findVisible(workspaceId, id)",
	"    .orElseThrow(() -> new NotFoundException());",
	"```",
	"",
	"`findVisible` returns empty both when the row is not there and when the caller may not see it.",
	"Callers get one 404 for two very different situations, so an admin debugging a support ticket",
	"cannot tell a stale link from a missing grant without reading the query.",
	"",
	"Separate the lookup from the check: fetch the row, then decide whether this caller may have it, and",
	"answer 403 when they may not. The extra query costs a millisecond and buys a diagnosable error.",
	"",
	"_Why this matters:_ an error that says only what went wrong sends the next person to the logs to",
	"work out where.",
	"",
	"---",
	"",
	"**🟡 Three of the new tests are named after the method they call** · `ReviewQueryServiceTest.java:44`",
	"",
	"`testCache1`, `testCache2` and `testCache3` pin down three different behaviours — a cold read, a",
	"warm read and an eviction after a membership change — and none of the three names says so. When one",
	"of them goes red in CI the failure line tells the reader nothing they can act on.",
	"",
	"Try `returnsTheCachedMembersOnASecondRead` and its siblings. The build output then reads as a",
	"sentence about the system.",
	"",
	"---",
	"",
	"**Worth keeping:** the controller stays three lines long — parse, delegate, map — and the caching",
	"decision sits entirely inside the service. That is the shape that lets the rest of this be tested",
	"without HTTP.",
].join("\n");

// Include private practice-page text to prove admin surfaces withhold it.
export const PRIVATE_FEEDBACK_TEXT =
	"Private to Barbara: the runbook's first step is the one a tired reader most needs.";

const DISAGREEMENT = "The two cases share a cause, so one status is right here.";

export const REVIEW_FIXTURE: ReviewSpec[] = [
	{
		id: "11111111-1111-1111-1111-111111111111",
		item: reviewItem,
		status: "COMPLETE",
		startedAt: "2026-07-28T13:35:00Z",
		developer: ada,
		event: "scm.pull_request.ready",
		withheld: [
			{
				practiceSlug: "product-language",
				reason: "The practice reads the review threads, and the provider did not return them.",
			},
		],
		results: [
			{
				id: "55555555-5555-5555-5555-555555555555",
				summary: "The controller delegates before it does anything else",
				explanation:
					"The handler is three statements long: it binds the request, calls ReviewQueryService and maps the result. The caching decision that arrived with this change sits entirely inside the service, so it can be exercised without standing up HTTP.",
				practiceSlug: "thin-controllers",
				outcome: "MET",
				recordedAt: "2026-07-28T13:40:00Z",
				evidence: [
					diff(
						"server/application/src/main/java/de/tum/cit/aet/hephaestus/practices/reviewoutput/ReviewQueryController.java",
						61,
						64,
						"var page = reviewQueryService.results(workspaceSlug, filter, pageable);\nreturn ResponseEntity.ok(page.map(ReviewResultDTO::from));",
					),
				],
			},
			{
				id: "66666666-6666-6666-6666-666666666666",
				summary: "A cache miss and a permission failure come back as the same 404",
				explanation:
					"findVisible returns an empty Optional both when the row does not exist and when the caller has no grant on the workspace, and the only caller turns either into a NotFoundException. Two situations that need different answers — retry the link, or ask for access — arrive as one.",
				practiceSlug: "errors-carry-context",
				outcome: "NOT_MET",
				recordedAt: "2026-07-28T13:39:00Z",
				coverage: {
					source: "HostCoverage",
					complete: true,
					reads: 4,
					failedReads: 0,
					truncatedReads: 0,
					omitted: [],
					receipts: [
						{
							sourceKind: "scm.pull-request.diff",
							path: "ReviewQueryService.java",
							status: "COMPLETE",
							version: HEAD_COMMIT,
						},
					],
				},
				evidence: [
					cited(
						"scm.review-threads",
						"Review thread on ReviewQueryService.java",
						"Do we lose the difference between 'gone' and 'not yours' here?",
					),
				],
				problems: [
					{
						severity: "MAJOR",
						summary: "A missing row and a missing grant both become a 404",
						explanation:
							"`findVisible` returns an empty `Optional` in both cases, and `orElseThrow` turns either into a `NotFoundException`.",
						citations: [
							diff(
								"server/application/src/main/java/de/tum/cit/aet/hephaestus/practices/reviewoutput/ReviewQueryService.java",
								116,
								119,
								"return repository.findVisible(workspaceId, id)\n    .orElseThrow(() -> new NotFoundException());",
							),
						],
						nextStep:
							"Fetch the row first, then check the grant, and answer 403 when the caller may not read it.",
						basis: {
							type: "IntroducedBasis",
							explanation: "The change added this lookup.",
						},
					},
					{
						severity: "MINOR",
						summary: "The guard’s 403 never reaches this endpoint",
						explanation:
							"`WorkspaceGuard` already throws a `ForbiddenException` with the workspace in its message, but this lookup runs before the guard is asked.",
						citations: [
							cited(
								"scm.repository.tree",
								"server/application/src/main/java/de/tum/cit/aet/hephaestus/core/security/WorkspaceGuard.java",
								'throw new ForbiddenException("No grant on workspace " + slug);',
								88,
							),
							diff(
								"server/application/src/main/java/de/tum/cit/aet/hephaestus/practices/reviewoutput/ReviewQueryService.java",
								112,
								112,
								"var workspaceId = workspaces.idOf(workspaceSlug);",
							),
						],
					},
					{
						severity: "TRIVIAL",
						summary: "The NotFoundException carries no message",
						explanation: "The log line says only that something was not found, not which review.",
						citations: [
							diff(
								"server/application/src/main/java/de/tum/cit/aet/hephaestus/practices/reviewoutput/ReviewQueryService.java",
								119,
								119,
								"    .orElseThrow(() -> new NotFoundException());",
							),
						],
						nextStep: "Name the review id in the exception.",
					},
				],
				example: {
					note: "The controller maps every error in one handler, so the split costs one new branch there.",
					citation: diff(
						"server/application/src/main/java/de/tum/cit/aet/hephaestus/practices/reviewoutput/ReviewQueryController.java",
						88,
						90,
						"@ExceptionHandler(NotFoundException.class)\nResponseEntity<ProblemDetail> notFound(NotFoundException e) {",
					),
				},
			},
			{
				id: "77777777-7777-7777-7777-777777777777",
				summary: "Three of the new tests are named after the method they call",
				explanation:
					"testCache1, testCache2 and testCache3 pin down a cold read, a warm read and an eviction after a membership change. The names carry none of that, so a red build names a file and a number rather than the behaviour that broke.",
				practiceSlug: "tests-name-the-behaviour",
				outcome: "NOT_MET",
				recordedAt: "2026-07-28T13:38:00Z",
				coverage: {
					source: "ClaimedCoverage",
					complete: false,
					checked: 2,
					selected: 3,
					total: 3,
					omitted: [{ reason: "budget", what: "test files", count: 1 }],
				},
				marks: [
					{
						kind: "INCORRECT",
						reason: "The names follow the module's convention for cache tests.",
						by: reviewAccounts.grace,
						at: "2026-07-28T14:10:00Z",
						removed: {
							by: reviewAccounts.grace,
							at: "2026-07-28T15:02:00Z",
							reason: "The convention was retired last month; the observation stands.",
						},
					},
					{
						kind: "ACCEPTED",
						reason: "Checked against the three tests; each name hides its behaviour.",
						by: reviewAccounts.grace,
						at: "2026-07-28T15:03:00Z",
					},
				],
				problems: [
					{
						severity: "MINOR",
						summary: "testCache1 and testCache2 name the method, not the read they pin down",
						explanation:
							"One is a cold read and one a warm read. A red build names a number, not the behaviour that broke.",
						citations: [
							diff(
								"server/application/src/test/java/de/tum/cit/aet/hephaestus/practices/reviewoutput/ReviewQueryServiceTest.java",
								44,
								46,
								"@Test\nvoid testCache1() {",
							),
						],
						nextStep: "Rename them for the read: `returnsTheCachedMembersOnASecondRead`.",
					},
					{
						severity: "TRIVIAL",
						summary: "testCache3 does not say that a membership change evicts",
						explanation:
							"The eviction is the most surprising rule of the three, and its name hides it.",
						citations: [
							diff(
								"server/application/src/test/java/de/tum/cit/aet/hephaestus/practices/reviewoutput/ReviewQueryServiceTest.java",
								71,
								72,
								"@Test\nvoid testCache3() {",
							),
						],
					},
				],
			},
			{
				id: "88888888-8888-8888-8888-888888888888",
				summary: "The description lists the files touched and stops there",
				explanation:
					"The body of the pull request is a bullet per changed file. Nothing in it says what was slow, how slow, or why a cache was the answer rather than a narrower query — the questions a reviewer who was not in that conversation has to ask before they can agree.",
				practiceSlug: "the-change-explains-itself",
				outcome: "NOT_MET",
				recordedAt: "2026-07-28T13:37:00Z",
				marks: [
					{
						kind: "INCORRECT",
						reason: "The linked issue carries the reasoning, and the description links it.",
						by: reviewAccounts.grace,
						at: "2026-07-28T13:58:00Z",
					},
				],
				problems: [
					{
						severity: "TRIVIAL",
						summary: "The description does not say what was slow",
						explanation:
							"It lists the changed files. A reviewer cannot tell why a cache was the answer rather than a narrower query.",
						citations: [
							cited(
								"scm.pull-request.core",
								"Pull request description",
								"- ReviewQueryService.java\n- ReviewQueryController.java\n- ReviewQueryServiceTest.java",
							),
						],
						nextStep: "Add one sentence on what was slow and how slow.",
					},
				],
			},
		],
		feedback: [
			{
				id: "33333333-3333-3333-3333-333333333333",
				body: "You kept the controller focused and moved the caching decision into the service, which is what lets the rest of this be tested without HTTP.",
				destination: "ON_WORK",
				state: "DELIVERED",
				composedAt: "2026-07-28T13:42:00Z",
				deliveredAt: "2026-07-28T13:43:00Z",
				from: ["55555555-5555-5555-5555-555555555555"],
				decisions: [{ action: "APPROVE", by: reviewAccounts.grace, at: "2026-07-28T13:42:40Z" }],
				response: { resolution: null, usefulness: "HELPFUL", at: "2026-07-28T15:20:00Z" },
			},
			{
				id: "44444444-4444-4444-4444-444444444444",
				body: LONG_BODY,
				destination: "ON_WORK",
				state: "DELIVERED",
				composedAt: "2026-07-28T13:41:00Z",
				deliveredAt: "2026-07-28T13:41:30Z",
				replaces: "99999999-3333-3333-3333-333333333333",
				from: ["66666666-6666-6666-6666-666666666666", "77777777-7777-7777-7777-777777777777"],
				copies: [
					{ kind: "SUMMARY", state: "POSTED" },
					{
						kind: "LINE_NOTE",
						state: "FAILED",
						body: "Fetch the row first, then check the grant, and answer 403 when the caller may not read it.",
						at: { path: "ReviewQueryService.java", startLine: 116, endLine: 119 },
						lastError: "GitHub answered 422: the line is no longer part of the diff.",
					},
					{
						kind: "LINE_NOTE",
						state: "FAILED",
						body: "Rename this for the read it pins down: `returnsTheCachedMembersOnASecondRead`.",
						at: { path: "ReviewQueryServiceTest.java", startLine: 44, endLine: 46 },
						lastError: "GitHub answered 422: the line is no longer part of the diff.",
					},
				],
				response: {
					resolution: "DISAGREE",
					comment: DISAGREEMENT,
					at: "2026-07-28T16:05:00Z",
				},
			},
			{
				id: "99999999-3333-3333-3333-333333333333",
				body: "The lookup collapses two different failures into one 404. Worth splitting before this merges.",
				destination: "ON_WORK",
				state: "REPLACED",
				composedAt: "2026-07-28T09:12:00Z",
				deliveredAt: "2026-07-28T09:12:20Z",
				from: ["66666666-6666-6666-6666-666666666666"],
			},
			{
				id: "aaaaaaaa-3333-3333-3333-333333333333",
				body: "The description could say what was slow before the cache went in.",
				destination: "ON_WORK",
				state: "WITHHELD",
				stopReason: "NOTHING_NEW",
				composedAt: "2026-07-28T13:41:00Z",
				from: ["88888888-8888-8888-8888-888888888888"],
			},
			{
				id: "bbbbbbbb-3333-3333-3333-333333333333",
				body: "Two failures share one status code here; the card names the split.",
				destination: "PRACTICE_PAGE",
				state: "DELIVERED",
				composedAt: "2026-07-28T13:42:10Z",
				deliveredAt: "2026-07-29T08:14:00Z",
				from: ["66666666-6666-6666-6666-666666666666"],
				withdrawn: true,
				decisions: [
					{
						action: "WITHDRAW",
						by: reviewAccounts.grace,
						at: "2026-07-29T10:00:00Z",
						reasonCode: "DUPLICATE",
						note: "The comment on the pull request already says this.",
					},
				],
			},
			{
				id: "cccccccc-3333-3333-3333-333333333333",
				body: "Three test names hide the behaviour they pin down.",
				destination: "PRACTICE_PAGE",
				state: "DELIVERED",
				composedAt: "2026-07-28T13:42:20Z",
				deliveredAt: "2026-07-29T08:14:00Z",
				from: ["77777777-7777-7777-7777-777777777777"],
				decisions: [
					{
						action: "WITHDRAW",
						by: reviewAccounts.grace,
						at: "2026-07-28T14:10:30Z",
						reasonCode: "INCORRECT",
					},
					{
						action: "RESTORE",
						by: reviewAccounts.grace,
						at: "2026-07-28T15:02:30Z",
						note: "The observation stands after all, so the card can show again.",
					},
				],
			},
			{
				id: "dddddddd-3333-3333-3333-333333333333",
				body: "Rename the three cache tests for what they check.",
				destination: "ON_WORK",
				state: "REJECTED",
				composedAt: "2026-07-28T13:41:20Z",
				from: ["77777777-7777-7777-7777-777777777777"],
				decisions: [
					{
						action: "REJECT",
						by: reviewAccounts.grace,
						at: "2026-07-28T14:05:00Z",
						reasonCode: "UNHELPFUL",
						note: "The long comment already covers the test names.",
					},
				],
			},
			{
				id: "eeeeeeee-3333-3333-3333-333333333333",
				body: "The description lists files without saying why.",
				destination: "PRACTICE_PAGE",
				state: "WITHHELD",
				stopReason: "RESULT_MARKED_INCORRECT",
				composedAt: "2026-07-28T13:42:30Z",
				from: ["88888888-8888-8888-8888-888888888888"],
			},
		],
	},
	{
		id: "22222222-2222-2222-2222-222222222222",
		item: gitlabMergeRequest,
		status: "COMPLETE",
		startedAt: "2026-07-27T09:10:00Z",
		developer: alan,
		origin: "BACKFILL",
		batchId: "0b0b0b0b-1111-4111-8111-111111111111",
		results: [
			{
				id: "bbbbbbbb-2222-2222-2222-222222222222",
				summary: "Invoice numbering leaks the ledger’s table name into the public API",
				explanation:
					"The response field is called ledgerSeqNo, which is the column the number is stored in. Callers outside billing have to learn the storage layout to read an invoice, and the day the ledger is replaced the field is either wrong or frozen.",
				practiceSlug: "product-language",
				outcome: "NOT_MET",
				currentness: "STALE",
				recordedAt: "2026-07-27T09:15:00Z",
				problems: [
					{
						severity: "CRITICAL",
						summary: "The public invoice response names a ledger column",
						explanation:
							"Callers outside billing must learn the storage layout to read an invoice.",
						citations: [
							diff(
								"src/main/java/platform/billing/api/InvoiceResponse.java",
								22,
								22,
								"private Long ledgerSeqNo;",
							),
						],
						nextStep: "Call the field `invoiceNumber` before any caller depends on it.",
					},
					{
						severity: "MINOR",
						summary: "The changelog repeats the column name",
						explanation:
							"The release note tells callers to read `ledgerSeqNo`, which fixes the name in their code.",
						citations: [diff("CHANGELOG.md", 7, 7, "- Invoices now expose `ledgerSeqNo`.")],
					},
				],
				evidence: [
					cited(
						"outline.documents",
						"Naming guideline",
						"A field on a public response is product copy. It is read, shared and quoted more often than a heading.",
						22,
					),
				],
			},
			{
				id: "cccccccc-2222-2222-2222-222222222222",
				summary: "The change touches no controller",
				explanation:
					"Everything in the merge request sits under the billing domain package. There is no request handler in the diff, so this practice has nothing to look at here.",
				practiceSlug: "thin-controllers",
				outcome: "DOES_NOT_APPLY",
				recordedAt: "2026-07-27T09:14:00Z",
				inapplicability: {
					prerequisite: "The change adds or edits a request handler.",
					ruledOutBy: "No file in the diff declares a controller.",
					consulted: ["scm.pull-request.diff", "scm.repository.tree"],
				},
				evidence: [
					cited(
						"scm.repository.tree",
						"src/main/java/platform/billing/",
						"InvoiceNumbering.java, InvoiceResponse.java, LedgerSequence.java",
						1,
					),
				],
			},
		],
		feedback: [
			{
				id: "dddddddd-2222-2222-2222-222222222222",
				body: "The public response exposes a name from the ledger schema. Renaming it now costs one migration; later it costs every caller, and the callers are other teams.",
				destination: "ON_WORK",
				state: "WITHHELD",
				stopReason: "BACKFILL_QUIET",
				composedAt: "2026-07-27T09:20:00Z",
				from: ["bbbbbbbb-2222-2222-2222-222222222222"],
			},
		],
	},
	{
		id: "33333333-3333-3333-3333-333333333333",
		item: slackConversation,
		status: "COMPLETE",
		startedAt: "2026-07-26T16:00:00Z",
		developer: grace,
		origin: "REQUEST",
		requestedBy: reviewAccounts.grace,
		results: [
			{
				id: "eeeeeeee-3333-3333-3333-333333333333",
				summary: "The thread ends without naming what was chosen",
				explanation:
					"Four people weighed two rollback strategies over eleven messages and the last one is a thumbs-up. Nothing in the thread states which strategy won, so a reader arriving tomorrow cannot tell agreement from the end of the working day.",
				practiceSlug: "decisions-are-written-down",
				outcome: "NOT_MET",
				currentness: "UNVERIFIABLE",
				recordedAt: "2026-07-26T16:02:00Z",
				problems: [
					{
						severity: "MINOR",
						summary: "The last message leaves the choice to the on-call",
						explanation: "Nobody names the strategy that won, so the thread reads as unfinished.",
						citations: [
							cited(
								"slack.conversation.thread",
								"Message from Grace Hopper, 16:41",
								"either works honestly, whatever the on-call prefers",
								11,
							),
						],
						nextStep: "Post the chosen strategy and who chose it.",
					},
				],
				evidence: [
					cited(
						"hephaestus.result-history",
						"Earlier observation on this practice",
						"An earlier thread in #engineering also closed without a written decision.",
					),
				],
			},
			{
				id: "ffffffff-3333-3333-3333-333333333333",
				summary: null,
				explanation:
					"Every message names the customer-visible effect — prices reverting, invoices reissuing — rather than the tables involved. Somebody paged at two in the morning could act on this thread without opening the schema.",
				practiceSlug: "product-language",
				outcome: "MET",
				recordedAt: "2026-07-26T16:01:00Z",
				evidence: [
					cited(
						"slack.conversation.thread",
						"Message from Alan Turing, 16:12",
						"customers on the old plan would see their price revert at the next invoice, not immediately",
						4,
					),
				],
			},
		],
		feedback: [
			{
				id: "11111111-4444-4444-4444-444444444444",
				body: "Before this thread scrolls away, drop the chosen strategy and who chose it into the runbook — a reader tomorrow cannot tell the thumbs-up from the end of the day.",
				destination: "CONVERSATION",
				state: "READY",
				composedAt: "2026-07-26T16:05:00Z",
				from: ["eeeeeeee-3333-3333-3333-333333333333"],
			},
			{
				id: "22222222-4444-4444-4444-444444444444",
				body: "You described the rollback in terms of what a customer would see rather than which tables move. That is the version somebody paged at 2am can act on.",
				destination: "CONVERSATION",
				conversation: "SLACK",
				state: "DELIVERED",
				composedAt: "2026-07-26T16:06:00Z",
				deliveredAt: "2026-07-26T18:40:00Z",
				from: ["ffffffff-3333-3333-3333-333333333333"],
			},
			{
				id: "33333333-4444-4444-4444-444444444444",
				body: "The thread reads like the incident it is about, which makes it easy to follow.",
				destination: "CONVERSATION",
				state: "UNCONFIRMED",
				stopReason: "DELIVERY_UNCONFIRMED",
				composedAt: "2026-07-26T16:07:00Z",
				from: ["ffffffff-3333-3333-3333-333333333333"],
			},
		],
	},
	{
		id: "44444444-4444-4444-4444-444444444444",
		item: outlineDocument,
		status: "COMPLETE",
		startedAt: "2026-07-25T11:25:00Z",
		developer: barbara,
		event: "docs.document.published",
		results: [
			{
				id: "11111111-5555-5555-5555-555555555555",
				summary: "The runbook opens with the one step that cannot be undone",
				explanation:
					"Step one puts the workspace into maintenance before anything is restored, and the page says so in the first line rather than in a note at the bottom. A reader following the page top to bottom does the irreversible thing at the point where it is still safe.",
				practiceSlug: "the-change-explains-itself",
				outcome: "MET",
				recordedAt: "2026-07-25T11:30:00Z",
				unknownProof: true,
				evidence: [
					cited(
						"docs.document.core",
						"Runbook: restoring a workspace from backup",
						"Before anything else: put the workspace into maintenance. Restores started while writes are landing cannot be replayed.",
						3,
					),
				],
			},
			{
				id: "22222222-5555-5555-5555-555555555555",
				summary: "Whether the restore order was agreed cannot be told from the page",
				explanation:
					"The runbook states an order of steps, and nothing on the page or linked from it records who chose that order or why.",
				practiceSlug: "decisions-are-written-down",
				outcome: "COULD_NOT_DETERMINE",
				recordedAt: "2026-07-25T11:29:00Z",
				openQuestions: [
					{
						openQuestion: "Was the restore order agreed with the on-call team?",
						wouldSettleIt: "A link to the discussion or incident that chose this order.",
						citation: cited(
							"docs.document.core",
							"Runbook: restoring a workspace from backup",
							"1. Put the workspace into maintenance.",
							5,
						),
					},
					{
						openQuestion: "Does the runbook replace the older restore page?",
						wouldSettleIt: "A note on either page that names the one in force.",
						citation: null,
					},
				],
			},
		],
		feedback: [
			{
				id: "33333333-5555-5555-5555-555555555555",
				body: "The runbook puts the irreversible step first and says why, which is the order somebody under pressure will actually follow.",
				destination: "ON_WORK",
				state: "FAILED",
				composedAt: "2026-07-25T11:32:00Z",
				from: ["11111111-5555-5555-5555-555555555555"],
			},
			{
				// Supply private text deliberately; the story must prove it stays hidden.
				id: "44444444-5555-5555-5555-555555555555",
				body: PRIVATE_FEEDBACK_TEXT,
				destination: "PRACTICE_PAGE",
				state: "DELIVERED",
				composedAt: "2026-07-25T11:33:00Z",
				deliveredAt: "2026-07-25T11:33:00Z",
				from: ["11111111-5555-5555-5555-555555555555"],
			},
		],
	},
	{
		id: "55555555-6666-6666-6666-666666666666",
		item: webhookRetryPullRequest,
		status: "COMPLETE",
		startedAt: "2026-07-29T08:05:00Z",
		developer: katherine,
		event: "scm.pull_request.synchronized",
		results: [
			{
				id: "44444444-6666-6666-6666-666666666666",
				summary: "A dropped delivery is logged at debug and never counted",
				explanation:
					"When the backoff gives up, the handler writes a debug line and returns. Nothing increments a counter and nothing reaches the dead-letter queue, so a provider outage looks identical to a quiet afternoon on every dashboard the team has.",
				practiceSlug: "errors-carry-context",
				outcome: "NOT_MET",
				recordedAt: "2026-07-29T08:12:00Z",
				problems: [
					{
						severity: "MAJOR",
						summary: "Giving up on a delivery increments no counter",
						explanation: "A provider outage looks like a quiet afternoon on every dashboard.",
						citations: [
							diff(
								"server/application/src/main/java/de/tum/cit/aet/hephaestus/integration/core/webhook/WebhookRetryPolicy.java",
								74,
								75,
								'log.debug("giving up on {} after {} attempts", event, attempts);\nreturn;',
							),
						],
						nextStep: "Count the dropped delivery with a Micrometer counter.",
					},
					{
						severity: "MINOR",
						summary: "The dropped delivery is logged at debug",
						explanation:
							"Production logs at info, so the line is never written where anyone reads it.",
						citations: [
							diff(
								"server/application/src/main/java/de/tum/cit/aet/hephaestus/integration/core/webhook/WebhookRetryPolicy.java",
								74,
								74,
								'log.debug("giving up on {} after {} attempts", event, attempts);',
							),
						],
					},
				],
				evidence: [
					cited(
						"workspace.project-inventory",
						"Observability stack",
						"Micrometer counters are exported to Prometheus; there is no log-based alerting.",
					),
				],
			},
			{
				id: "55555555-7777-7777-7777-777777777777",
				summary: "The retry tests read as sentences about the backoff",
				explanation:
					"Each of the four new tests is named for the behaviour it fixes in place — that the delay doubles, that it stops at the ceiling, that a success resets it, that a 4xx is not retried. A red build points straight at which of the four rules broke.",
				practiceSlug: "tests-name-the-behaviour",
				outcome: "MET",
				recordedAt: "2026-07-29T08:11:00Z",
				evidence: [
					diff(
						"server/application/src/test/java/de/tum/cit/aet/hephaestus/integration/core/webhook/WebhookRetryPolicyTest.java",
						31,
						32,
						"@Test\nvoid doublesTheDelayUntilItReachesTheCeiling() {",
					),
				],
			},
			{
				id: "66666666-7777-7777-7777-777777777777",
				summary: "The queue is called the outbox everywhere except in the config",
				explanation:
					"The class, the metric and the log lines all say outbox. The property is hephaestus.webhook.retry-buffer.*, so an admin reading a dashboard and an admin editing configuration are looking for two different words for one thing.",
				practiceSlug: "product-language",
				outcome: "NOT_MET",
				recordedAt: "2026-07-29T08:10:00Z",
				evidenceHidden: true,
				problems: [
					{
						severity: "MINOR",
						summary: "The property says retry-buffer where everything else says outbox",
						explanation:
							"An admin reading a dashboard and one editing configuration look for two words.",
						citations: [
							diff(
								"server/application/src/main/resources/application.yml",
								118,
								118,
								"retry-buffer:\n  capacity: 5000",
							),
						],
						nextStep: "Rename the property to `hephaestus.webhook.outbox.*`.",
					},
				],
			},
		],
		feedback: [
			{
				id: "77777777-6666-6666-6666-666666666666",
				body: "Giving up on a delivery is the one moment worth counting. A debug line means a provider outage and a quiet afternoon look the same on every dashboard you have.",
				destination: "ON_WORK",
				state: "WITHHELD",
				stopReason: "ITEM_MERGED",
				composedAt: "2026-07-29T08:20:00Z",
				from: ["44444444-6666-6666-6666-666666666666"],
			},
			{
				id: "88888888-6666-6666-6666-666666666666",
				body: "Each retry test is named for the rule it pins down, so a red build points at which of the four broke.",
				destination: "ON_WORK",
				state: "WITHHELD",
				stopReason: "PERSON_SUPPRESSED",
				composedAt: "2026-07-29T08:19:00Z",
				from: ["55555555-7777-7777-7777-777777777777"],
			},
			{
				id: "99999999-6666-6666-6666-666666666666",
				body: "The dashboard says outbox and the config says retry-buffer. Picking one and using it in both places would save the next admin a search.",
				destination: "ON_WORK",
				state: "DELIVERED",
				composedAt: "2026-07-29T08:18:00Z",
				deliveredAt: "2026-07-29T08:18:40Z",
				anchoredAt: {
					path: "server/application/src/main/resources/application.yml",
					startLine: 118,
					endLine: 120,
				},
				from: ["66666666-7777-7777-7777-777777777777"],
				fromProblem: { "66666666-7777-7777-7777-777777777777": 0 },
			},
			{
				id: "aaaaaaaa-6666-6666-6666-666666666666",
				body: "The four retry tests are named for the rules they pin down. Keep that shape for the next ones.",
				destination: "ON_WORK",
				state: "AWAITING_APPROVAL",
				stopReason: "PRACTICE_REQUIRES_APPROVAL",
				composedAt: "2026-07-29T08:21:00Z",
				from: ["55555555-7777-7777-7777-777777777777"],
				copies: [
					{ kind: "SUMMARY", state: "PLANNED" },
					{
						kind: "LINE_NOTE",
						state: "PLANNED",
						body: "This name says the delay doubles, which is the rule a reader needs when the test goes red.",
						at: {
							path: "server/application/src/test/java/de/tum/cit/aet/hephaestus/integration/core/webhook/WebhookRetryPolicyTest.java",
							startLine: 31,
							endLine: 32,
						},
					},
					{
						kind: "FILE_NOTE",
						state: "PLANNED",
						body: "Every test in this file states its rule in its name.",
						at: {
							path: "server/application/src/test/java/de/tum/cit/aet/hephaestus/integration/core/webhook/WebhookRetryPolicyTest.java",
							startLine: 1,
							endLine: 1,
						},
					},
				],
			},
			{
				id: "bbbbbbbb-6666-6666-6666-666666666666",
				body: "The outbox naming split also shows up in the metric tags.",
				destination: "ON_WORK",
				state: "READY",
				composedAt: "2026-07-29T08:22:00Z",
				from: ["66666666-7777-7777-7777-777777777777"],
				copies: [{ kind: "SUMMARY", state: "WRITE_STARTED" }],
			},
		],
	},
	{
		id: "aaaaaaaa-8888-8888-8888-888888888888",
		item: leagueColumnsPullRequest,
		status: "RUNNING",
		startedAt: "2026-07-29T09:40:00Z",
		developer: grace,
		event: "scm.pull_request.opened",
		pending: ["thin-controllers", "errors-carry-context"],
		results: [],
		feedback: [],
	},
	{
		id: "bbbbbbbb-8888-8888-8888-888888888888",
		item: invoiceBackfillMergeRequest,
		status: "FAILED",
		stopReason: "EXECUTION_FAILED",
		startedAt: "2026-07-24T15:02:00Z",
		developer: alan,
		event: "scm.pull_request.ready",
		pending: ["product-language"],
		runtimeExpired: true,
		results: [],
		feedback: [],
	},
	{
		id: "cccccccc-8888-8888-8888-888888888888",
		item: pullRequestReviewItem,
		status: "COMPLETE",
		startedAt: "2026-07-28T17:05:00Z",
		developer: grace,
		event: "scm.pull_request_review.submitted",
		results: [
			{
				id: "dddddddd-8888-8888-8888-888888888888",
				summary: "Each comment says what to change and why",
				explanation:
					"All four comments name the risk the change removes, so Ada can agree with the reason or argue with it.",
				practiceSlug: "review-comments-explain",
				outcome: "MET",
				recordedAt: "2026-07-28T17:08:00Z",
				evidence: [
					cited(
						"scm.review-threads",
						"Comment on ReviewQueryService.java",
						"Splitting these lets support tell a stale link from a missing grant.",
					),
				],
			},
		],
		feedback: [
			{
				id: "eeeeeeee-8888-8888-8888-888888888888",
				body: "Your review explains why each change matters.",
				destination: "PRACTICE_PAGE",
				state: "WITHHELD",
				stopReason: "FEEDBACK_OFF",
				composedAt: "2026-07-28T17:09:00Z",
				from: ["dddddddd-8888-8888-8888-888888888888"],
			},
		],
	},
	{
		id: "dddddddd-9999-9999-9999-999999999999",
		item: scannedRepository,
		kind: "SCAN",
		status: "PARTIAL",
		stopReason: "BUDGET",
		startedAt: "2026-07-27T20:00:00Z",
		developer: null,
		origin: "REQUEST",
		requestedBy: reviewAccounts.grace,
		jobs: 4,
		results: [
			{
				id: "eeeeeeee-9999-9999-9999-999999999999",
				summary: "Giving up on a delivery names neither the event nor the provider",
				explanation:
					"The retry policy's last log line names the attempt count and nothing that would let an admin find the delivery it dropped.",
				practiceSlug: "errors-carry-context",
				outcome: "NOT_MET",
				recordedAt: "2026-07-27T20:12:00Z",
				item: scannedFiles[0],
				aboutTheWork: true,
				problems: [
					{
						severity: "MAJOR",
						summary: "The give-up line names no event",
						explanation: "An admin cannot tell which delivery was dropped.",
						citations: [
							cited(
								"scm.repository.file",
								scannedFiles[0].path ?? "",
								'log.debug("giving up after {} attempts", attempts);',
								74,
							),
						],
					},
				],
			},
			{
				id: "ffffffff-9999-9999-9999-999999999999",
				summary: "Every lookup failure names the review it was looking for",
				explanation: "Each exception in the file is built with the id it failed on.",
				practiceSlug: "errors-carry-context",
				outcome: "MET",
				recordedAt: "2026-07-27T20:14:00Z",
				item: scannedFiles[1],
				aboutTheWork: true,
			},
		],
		feedback: [],
	},
];

// Preview bounds must match the server so bodyTruncated agrees with bodyPreview.
const BODY_PREVIEW_LENGTH = 320;

const MINUTE = 60_000;

function adminBody(spec: FeedbackSpec): string | null {
	return ADMIN_WITHHELD_TEXT[spec.destination] === undefined ? (spec.body ?? null) : null;
}

function preview(spec: FeedbackSpec) {
	const body = adminBody(spec);
	return {
		bodyPreview: body?.slice(0, BODY_PREVIEW_LENGTH) ?? null,
		bodyTruncated: (body?.length ?? 0) > BODY_PREVIEW_LENGTH,
		textHidden: ADMIN_WITHHELD_TEXT[spec.destination] !== undefined,
		unverifiedHistory: false,
	};
}

const at = (iso: string | undefined): Date | null => (hasText(iso) ? new Date(iso) : null);

const allReviews = REVIEW_FIXTURE;
const allResultSpecs = allReviews.flatMap((review) =>
	review.results.map((result) => ({ review, result })),
);
const allFeedbackSpecs = allReviews.flatMap((review) =>
	review.feedback.map((item) => ({ review, item })),
);

function reviewSpec(reviewId: string): ReviewSpec {
	const found = allReviews.find((entry) => entry.id === reviewId);
	if (!found) {
		throw new Error(`No review ${reviewId} in the fixture`);
	}
	return found;
}

function resultSpec(resultId: string) {
	const found = allResultSpecs.find(({ result }) => result.id === resultId);
	if (!found) {
		throw new Error(`No result ${resultId} in the fixture`);
	}
	return found;
}

function feedbackSpec(feedbackId: string) {
	const found = allFeedbackSpecs.find(({ item }) => item.id === feedbackId);
	if (!found) {
		throw new Error(`No feedback ${feedbackId} in the fixture`);
	}
	return found;
}

function feedbackFor(resultId: string) {
	return allFeedbackSpecs
		.filter(({ item }) => item.from.includes(resultId))
		.sort((a, b) => b.item.composedAt.localeCompare(a.item.composedAt));
}

export function feedbackIdsFrom(resultId: string): ReadonlySet<string> {
	return new Set(feedbackFor(resultId).map(({ item }) => item.id));
}

const SEVERITY_RANK: Record<Severity, number> = { CRITICAL: 0, MAJOR: 1, MINOR: 2, TRIVIAL: 3 };

function severityOf(spec: ResultSpec): Severity | null {
	const [worst] = (spec.problems ?? [])
		.map((problem) => problem.severity)
		.sort((a, b) => SEVERITY_RANK[a] - SEVERITY_RANK[b]);
	return worst ?? null;
}

export function problemIdOf(resultId: string, position: number): string {
	return `${resultId.slice(0, -2)}${String(position).padStart(2, "0")}`;
}

const COUNT_OF: Record<FeedbackState, keyof ReviewFeedbackCounts> = {
	AWAITING_APPROVAL: "awaitingApproval",
	READY: "ready",
	DELIVERED: "delivered",
	UNCONFIRMED: "unconfirmed",
	WITHHELD: "withheld",
	REJECTED: "rejected",
	REPLACED: "replaced",
	FAILED: "failed",
};

export function feedbackCounts(states: readonly FeedbackState[]): ReviewFeedbackCounts {
	const counts: ReviewFeedbackCounts = {
		awaitingApproval: 0,
		ready: 0,
		delivered: 0,
		unconfirmed: 0,
		withheld: 0,
		rejected: 0,
		replaced: 0,
		failed: 0,
	};
	for (const state of states) {
		counts[COUNT_OF[state]] += 1;
	}
	return counts;
}

const ZERO_FEEDBACK = feedbackCounts([]);

export function resultCounts(outcomes: readonly ReviewResult["outcome"][]): ReviewResultCounts {
	return {
		met: outcomes.filter((outcome) => outcome === "MET").length,
		notMet: outcomes.filter((outcome) => outcome === "NOT_MET").length,
		doesNotApply: outcomes.filter((outcome) => outcome === "DOES_NOT_APPLY").length,
		couldNotDetermine: outcomes.filter((outcome) => outcome === "COULD_NOT_DETERMINE").length,
	};
}

export function reasonCounts(rows: readonly ReviewFeedback[]): ReasonCount[] {
	const counts = new Map<StopReason, number>();
	for (const row of rows) {
		if (row.stopReason !== null) {
			counts.set(row.stopReason, (counts.get(row.stopReason) ?? 0) + 1);
		}
	}
	return [...counts].map(([reason, count]) => ({
		reason,
		description: withholdingReasonSentence(reason),
		count,
	}));
}

const LOCATION_ORDER: ReviewFeedback["location"][] = [
	"GITHUB",
	"GITLAB",
	"OTHER_WORK",
	"HEPH",
	"SLACK",
	"NEXT_CONVERSATION",
	"PRACTICE_PAGES",
];

export function locationCounts(rows: readonly ReviewFeedback[]): ReasonCount[] {
	return LOCATION_ORDER.flatMap((location) => {
		const count = rows.filter((row) => row.location === location).length;
		return count === 0
			? []
			: [{ reason: location, description: FEEDBACK_LOCATION_DEFS[location].label, count }];
	});
}

function markId(resultId: string, index: number): string {
	return `${resultId.slice(0, -3)}a${String(index).padStart(2, "0")}`;
}

function marksOf(resultId: string, spec: ResultSpec): ResultMark[] {
	return (spec.marks ?? []).map((mark, index) => ({
		id: markId(resultId, index),
		resultId,
		kind: mark.kind,
		reason: mark.reason,
		createdBy: mark.by,
		createdAt: new Date(mark.at),
		removedBy: mark.removed?.by ?? null,
		removedAt: at(mark.removed?.at),
		removalReason: mark.removed?.reason ?? null,
	}));
}

export function resultMarks(resultId: string): ResultMark[] {
	return marksOf(resultId, resultSpec(resultId).result);
}

function activeMarkAt(spec: ResultSpec, kind: ResultMark["kind"]): Date | null {
	const active = (spec.marks ?? []).find(
		(mark) => mark.kind === kind && mark.removed === undefined,
	);
	return at(active?.at);
}

function disagreementsOf(resultId: string): FeedbackDisagreement[] {
	return feedbackFor(resultId).flatMap(({ item }) =>
		item.response?.resolution === "DISAGREE"
			? [
					{
						feedbackId: item.id,
						destination: item.destination,
						comment: item.response.comment ?? "",
						respondedAt: new Date(item.response.at),
					},
				]
			: [],
	);
}

export function disagreedResultIds(): ReadonlySet<string> {
	return new Set(
		allResultSpecs
			.filter(({ result }) => disagreementsOf(result.id).length > 0)
			.map(({ result }) => result.id),
	);
}

function disagreedAt(resultId: string): Date | null {
	return disagreementsOf(resultId)[0]?.respondedAt ?? null;
}

function itemOf(review: ReviewSpec, spec: ResultSpec): ItemRef {
	return spec.item ?? review.item;
}

function originOf(review: ReviewSpec): ReviewSummary["origin"] {
	return review.origin ?? "EVENT";
}

function toResult(review: ReviewSpec, spec: ResultSpec): ReviewResult {
	const problems = spec.problems ?? [];
	return {
		id: spec.id,
		reviewId: review.id,
		item: itemOf(review, spec),
		developer: spec.aboutTheWork === true ? null : review.developer,
		practiceSlug: spec.practiceSlug,
		practiceName: practiceNameOf(spec.practiceSlug),
		outcome: spec.outcome,
		severity: severityOf(spec),
		summary: spec.summary,
		origin: originOf(review),
		recordedAt: new Date(spec.recordedAt),
		currentness: spec.currentness ?? "CURRENT",
		markedIncorrectAt: activeMarkAt(spec, "INCORRECT"),
		acceptedAt: activeMarkAt(spec, "ACCEPTED"),
		problemCount: problems.length,
		problems: problems.map((problem, position) => ({
			id: problemIdOf(spec.id, position),
			severity: problem.severity,
			summary: problem.summary,
		})),
		feedback: feedbackCounts(feedbackFor(spec.id).map(({ item }) => item.state)),
	};
}

export function boundResultOf(result: ReviewBoundResult): ReviewBoundResult {
	return {
		id: result.id,
		reviewId: result.reviewId,
		item: result.item,
		developer: result.developer,
		practiceSlug: result.practiceSlug,
		practiceName: result.practiceName,
		outcome: result.outcome,
		severity: result.severity,
		summary: result.summary,
		recordedAt: result.recordedAt,
		currentness: result.currentness,
	};
}

function toProblems(spec: ResultSpec): Problem[] {
	const hidden = spec.evidenceHidden === true;
	return (spec.problems ?? []).map((problem, position) => ({
		id: problemIdOf(spec.id, position),
		resultId: spec.id,
		position,
		key: `${spec.practiceSlug}:${position}`,
		severity: problem.severity,
		summary: problem.summary,
		explanation: hidden ? null : problem.explanation,
		citations: hidden ? [] : problem.citations,
		basis: problem.basis ?? null,
		nextStep: hidden ? null : (problem.nextStep ?? null),
	}));
}

function toProblemRows(review: ReviewSpec, spec: ResultSpec): ReviewProblem[] {
	const result = toResult(review, spec);
	const hidden = spec.evidenceHidden === true;
	return (spec.problems ?? []).map((problem, position) => {
		const [place] = problem.citations;
		return {
			id: problemIdOf(spec.id, position),
			resultId: result.id,
			reviewId: result.reviewId,
			item: result.item,
			developer: result.developer,
			practiceSlug: result.practiceSlug,
			practiceName: result.practiceName,
			group: groupRefOf(result.practiceSlug),
			severity: problem.severity,
			summary: problem.summary,
			path: hidden ? null : (place?.path ?? null),
			startLine: hidden ? null : (place?.startLine ?? null),
			endLine: hidden ? null : (place?.endLine ?? null),
			side: hidden ? null : (place?.side ?? null),
			sourceKind: hidden ? null : (place?.sourceKind ?? null),
			evidenceHidden: hidden,
			currentness: result.currentness,
			markedIncorrectAt: result.markedIncorrectAt,
			acceptedAt: result.acceptedAt,
			disagreedAt: disagreedAt(result.id),
			recordedAt: result.recordedAt,
		};
	});
}

export const reviewResults: ReviewResult[] = allResultSpecs
	.map(({ review, result }) => toResult(review, result))
	.sort((a, b) => b.recordedAt.getTime() - a.recordedAt.getTime());

export const reviewProblems: ReviewProblem[] = allResultSpecs
	.flatMap(({ review, result }) => toProblemRows(review, result))
	.sort((a, b) => b.recordedAt.getTime() - a.recordedAt.getTime());

function locationOf(review: ReviewSpec, spec: FeedbackSpec): ReviewFeedback["location"] {
	switch (spec.destination) {
		case "PRACTICE_PAGE": {
			return "PRACTICE_PAGES";
		}
		case "ON_WORK": {
			const { provider } = review.item;
			return provider === "GITHUB" || provider === "GITLAB" ? provider : "OTHER_WORK";
		}
		case "CONVERSATION": {
			return spec.state === "DELIVERED" || spec.state === "UNCONFIRMED"
				? (spec.conversation ?? "HEPH")
				: "NEXT_CONVERSATION";
		}
	}
}

const COPY_STATE_OF: Record<FeedbackState, FeedbackCopy["state"]> = {
	AWAITING_APPROVAL: "PLANNED",
	READY: "PLANNED",
	DELIVERED: "POSTED",
	REPLACED: "POSTED",
	UNCONFIRMED: "WRITE_STARTED",
	WITHHELD: "WITHHELD",
	REJECTED: "WITHHELD",
	FAILED: "FAILED",
};

function copySpecsOf(spec: FeedbackSpec): CopySpec[] {
	if (spec.copies) {
		return spec.copies;
	}
	const state =
		spec.withdrawn === true && spec.state === "DELIVERED" ? "RETRACTED" : COPY_STATE_OF[spec.state];
	switch (spec.destination) {
		case "CONVERSATION": {
			return [{ kind: "CHAT_TURN", state }];
		}
		case "PRACTICE_PAGE": {
			return [{ kind: "SUMMARY", state }];
		}
		case "ON_WORK": {
			return spec.anchoredAt
				? [{ kind: "LINE_NOTE", state, body: spec.body, at: spec.anchoredAt }]
				: [
						{
							kind: "SUMMARY",
							state,
							lastError:
								state === "FAILED"
									? "The provider answered 403: Resource not accessible by integration."
									: undefined,
						},
					];
		}
	}
}

function copiesOf(review: ReviewSpec, spec: FeedbackSpec): FeedbackCopy[] {
	const composed = new Date(spec.composedAt);
	const delivered = at(spec.deliveredAt) ?? new Date(composed.getTime() + 30_000);
	return copySpecsOf(spec).map((copy, position) => {
		const started = copy.state !== "PLANNED" && copy.state !== "WITHHELD";
		const posted = copy.state === "POSTED" || copy.state === "RETRACTED";
		const onWork = spec.destination === "ON_WORK";
		const ref = String(2_481_933 + position);
		return {
			id: `${spec.id.slice(0, -3)}c${String(position).padStart(2, "0")}`,
			kind: copy.kind,
			position,
			body: onWork ? (copy.body ?? (copy.kind === "SUMMARY" ? (spec.body ?? null) : null)) : null,
			path: copy.at?.path ?? null,
			startLine: copy.at?.startLine ?? null,
			endLine: copy.at?.endLine ?? null,
			side: copy.at === undefined ? null : "NEW",
			state: copy.state,
			writeStartedAt: started ? composed : null,
			postedAt: posted ? delivered : null,
			externalRef: posted && onWork ? ref : null,
			externalUrl:
				posted && onWork && hasText(review.item.url)
					? `${review.item.url}#issuecomment-${ref}`
					: null,
			mentorEntryId:
				copy.kind === "CHAT_TURN" && copy.state !== "PLANNED"
					? `${spec.id.slice(0, -3)}f${String(position).padStart(2, "0")}`
					: null,
			stopReason: copy.state === "WITHHELD" ? (spec.stopReason ?? null) : null,
			lastError: copy.lastError ?? null,
			readback: null,
		};
	});
}

function copyCountsOf(copies: readonly FeedbackCopy[]): FeedbackCopyCounts {
	const count = (state: FeedbackCopy["state"]) =>
		copies.filter((copy) => copy.state === state).length;
	return {
		planned: count("PLANNED"),
		writeStarted: count("WRITE_STARTED"),
		posted: count("POSTED"),
		failed: count("FAILED"),
		withheld: count("WITHHELD"),
		retracted: count("RETRACTED"),
	};
}

function sendsOf(spec: FeedbackSpec, copies: readonly FeedbackCopy[]): FeedbackSend[] {
	if (spec.destination !== "ON_WORK") {
		return [];
	}
	const createdAt = new Date(spec.composedAt);
	const base = {
		feedbackId: spec.id,
		createdAt,
		nextAttemptAt: new Date(createdAt.getTime() + 10 * MINUTE),
		lastError: null,
	};
	const has = (state: FeedbackCopy["state"]) => copies.some((copy) => copy.state === state);
	if (spec.withdrawn === true) {
		return [{ ...base, action: "RETRACT", state: "DONE", attemptCount: 1, updatedAt: createdAt }];
	}
	if (has("WRITE_STARTED")) {
		return [
			{
				...base,
				action: "POST",
				state: "UNCERTAIN",
				attemptCount: 1,
				updatedAt: new Date(createdAt.getTime() + MINUTE),
				lastError: "The connection closed before GitHub answered.",
			},
		];
	}
	if (has("FAILED") && has("POSTED")) {
		return [
			{
				...base,
				action: "POST",
				state: "PENDING",
				attemptCount: 2,
				updatedAt: new Date(createdAt.getTime() + 2 * MINUTE),
				lastError: "GitHub answered 422 for both line notes.",
			},
		];
	}
	if (has("FAILED")) {
		return [
			{
				...base,
				action: "POST",
				state: "DONE",
				attemptCount: 5,
				updatedAt: new Date(createdAt.getTime() + 40 * MINUTE),
				lastError: "The provider answered 403: Resource not accessible by integration.",
			},
		];
	}
	if (has("POSTED")) {
		return [
			{
				...base,
				action: "POST",
				state: "DONE",
				attemptCount: 1,
				updatedAt: at(spec.deliveredAt) ?? createdAt,
			},
		];
	}
	if (spec.state === "READY") {
		return [{ ...base, action: "POST", state: "PENDING", attemptCount: 0, updatedAt: createdAt }];
	}
	return [];
}

function contentDigestOf(spec: FeedbackSpec): string | null {
	return spec.destination === "CONVERSATION" ? null : `sha256:${spec.id.slice(0, 8)}`;
}

function decisionsOf(spec: FeedbackSpec): FeedbackDecision[] {
	return (spec.decisions ?? []).map((decision, index) => ({
		id: `${spec.id.slice(0, -3)}d${String(index).padStart(2, "0")}`,
		feedbackId: spec.id,
		action: decision.action,
		actor: decision.by,
		reasonCode: decision.reasonCode ?? null,
		note: decision.note ?? null,
		contentDigest: contentDigestOf(spec),
		decidedAt: new Date(decision.at),
	}));
}

export function feedbackDecisions(feedbackId: string): FeedbackDecision[] {
	return decisionsOf(feedbackSpec(feedbackId).item);
}

const ALLOWED_FACTS: FeedbackCheckFacts = {
	admittedRolloutRevision: "5",
	evaluatedRolloutRevision: "5",
	inScope: true,
	evidenceAllowed: true,
	modelAllowed: true,
	deliveryEnabled: true,
	incorrectBasis: false,
	currentVersion: true,
	approvalCurrent: true,
	writeStarted: false,
};

function checksOf(spec: FeedbackSpec): FeedbackCheck[] {
	const composed = new Date(spec.composedAt);
	const check = (
		stage: FeedbackCheck["stage"],
		offsetSeconds: number,
		reason: StopReason | null = null,
		facts: Partial<FeedbackCheckFacts> = {},
	): FeedbackCheck => ({
		stage,
		allowed: reason === null,
		reason,
		checkedAt: new Date(composed.getTime() + offsetSeconds * 1000),
		facts: { ...ALLOWED_FACTS, ...facts },
	});
	const checks = [check("COMPOSITION", 0)];
	if (spec.state === "WITHHELD" && spec.stopReason !== undefined) {
		checks.push(
			check("AUTOMATIC", 5, spec.stopReason, {
				incorrectBasis: spec.stopReason === "RESULT_MARKED_INCORRECT",
				deliveryEnabled: spec.stopReason !== "FEEDBACK_OFF",
			}),
		);
		return checks;
	}
	if (spec.state === "AWAITING_APPROVAL") {
		checks.push(check("AUTOMATIC", 5, "PRACTICE_REQUIRES_APPROVAL"));
		return checks;
	}
	checks.push(check("AUTOMATIC", 5));
	if (spec.decisions?.some((decision) => decision.action === "APPROVE") === true) {
		checks.push(check("APPROVED", 40));
	}
	if (spec.state !== "READY" && spec.state !== "REJECTED") {
		checks.push(check("EGRESS", 50, null, { writeStarted: true }));
	}
	return checks;
}

function responseOf(spec: FeedbackSpec): FeedbackResponse | null {
	return spec.response === undefined
		? null
		: {
				resolution: spec.response.resolution,
				usefulness: spec.response.usefulness ?? null,
				comment: spec.response.comment ?? null,
				createdAt: new Date(spec.response.at),
			};
}

function developerOfFeedback(review: ReviewSpec): ReviewDeveloper {
	if (review.developer === null) {
		throw new Error(`Review ${review.id} has no developer, so it cannot write feedback`);
	}
	return review.developer;
}

function toFeedback(review: ReviewSpec, spec: FeedbackSpec): ReviewFeedback {
	return {
		id: spec.id,
		reviewId: review.id,
		item: review.item,
		developer: developerOfFeedback(review),
		destination: spec.destination,
		state: spec.state,
		stopReason: spec.stopReason ?? null,
		location: locationOf(review, spec),
		replacesId: spec.replaces ?? null,
		createdAt: new Date(spec.composedAt),
		deliveredAt: at(spec.deliveredAt),
		...preview(spec),
		resultCount: spec.from.length,
		copyCounts: copyCountsOf(copiesOf(review, spec)),
		withdrawn: spec.withdrawn === true,
		disagreedAt: spec.response?.resolution === "DISAGREE" ? new Date(spec.response.at) : null,
	};
}

export const reviewFeedback: ReviewFeedback[] = allFeedbackSpecs
	.map(({ review, item }) => toFeedback(review, item))
	.sort((a, b) => b.createdAt.getTime() - a.createdAt.getTime());

export function feedbackPracticeSlugs(feedbackId: string): string[] {
	const found = allFeedbackSpecs.find(({ item }) => item.id === feedbackId);
	return (found?.item.from ?? []).map((resultId) => resultSpec(resultId).result.practiceSlug);
}

export function practiceGroupSlug(practiceSlug: string): string | null {
	return practiceOf(practiceSlug).groupSlug;
}

export function feedbackDetail(
	input: string | Partial<ReviewFeedbackDetail> = {},
): ReviewFeedbackDetail {
	const feedbackId = typeof input === "string" ? input : "dddddddd-2222-2222-2222-222222222222";
	const { review, item } = feedbackSpec(feedbackId);
	const copies = copiesOf(review, item);
	const basis: FeedbackBasis[] = item.from.map((resultId, ordinal) => {
		const source = resultSpec(resultId);
		const position = item.fromProblem?.[resultId];
		return {
			resultId,
			problemId: position === undefined ? null : problemIdOf(resultId, position),
			ordinal,
			result: boundResultOf(toResult(source.review, source.result)),
		};
	});
	return {
		...toFeedback(review, item),
		body: adminBody(item),
		basis,
		copies,
		sends: sendsOf(item, copies),
		decisions: decisionsOf(item),
		response: responseOf(item),
		checks: checksOf(item),
		contentDigest: contentDigestOf(item),
		...(typeof input === "string" ? {} : input),
	};
}

export const reviewFeedbackDetail = feedbackDetail();

export const longFeedbackDetail: ReviewFeedbackDetail = feedbackDetail(
	"44444444-4444-4444-4444-444444444444",
);

/** The summary posted and both line notes failed: partly delivered. */
export const partialPackageFeedbackDetail: ReviewFeedbackDetail = {
	...longFeedbackDetail,
	state: "FAILED",
};

export const awaitingApprovalFeedbackDetail: ReviewFeedbackDetail = feedbackDetail(
	"aaaaaaaa-6666-6666-6666-666666666666",
);

export const uncertainWriteFeedbackDetail: ReviewFeedbackDetail = feedbackDetail(
	"bbbbbbbb-6666-6666-6666-666666666666",
);

export const approvedFeedbackDetail: ReviewFeedbackDetail = feedbackDetail(
	"33333333-3333-3333-3333-333333333333",
);

export const rejectedFeedbackDetail: ReviewFeedbackDetail = feedbackDetail(
	"dddddddd-3333-3333-3333-333333333333",
);

export const withdrawnFeedbackDetail: ReviewFeedbackDetail = feedbackDetail(
	"bbbbbbbb-3333-3333-3333-333333333333",
);

export const restoredFeedbackDetail: ReviewFeedbackDetail = feedbackDetail(
	"cccccccc-3333-3333-3333-333333333333",
);

export const replacedFeedbackDetail: ReviewFeedbackDetail = feedbackDetail(
	"99999999-3333-3333-3333-333333333333",
);

export const failedFeedbackDetail: ReviewFeedbackDetail = feedbackDetail(
	"33333333-5555-5555-5555-555555555555",
);

export const unconfirmedFeedbackDetail: ReviewFeedbackDetail = feedbackDetail(
	"33333333-4444-4444-4444-444444444444",
);

export const markedIncorrectFeedbackDetail: ReviewFeedbackDetail = feedbackDetail(
	"eeeeeeee-3333-3333-3333-333333333333",
);

export function boundFeedbackOf(feedback: ReviewFeedback): ReviewBoundFeedback {
	return {
		id: feedback.id,
		destination: feedback.destination,
		state: feedback.state,
		deliveredAt: feedback.deliveredAt,
		withdrawn: feedback.withdrawn,
	};
}

export function resultDetail(input: string | Partial<ReviewResultDetail> = {}): ReviewResultDetail {
	const resultId = typeof input === "string" ? input : "66666666-6666-6666-6666-666666666666";
	const { review, result: spec } = resultSpec(resultId);
	const {
		feedback: _tally,
		problemCount: _count,
		problems: _summaries,
		...shared
	} = toResult(review, spec);
	const hidden = spec.evidenceHidden === true;
	const practice = practiceOf(spec.practiceSlug);
	return {
		...shared,
		practiceVersionId: practice.currentVersion.id,
		practiceVersionNumber: practice.currentVersion.number,
		itemVersion: review.item.kind === ITEM_KIND.pullRequest ? ITEM_VERSION : null,
		explanation: hidden ? null : spec.explanation,
		evidence: hidden
			? null
			: {
					citations: spec.evidence ?? [],
					openQuestions: spec.openQuestions ?? [],
					inapplicability: spec.inapplicability ?? null,
					search: null,
				},
		evidenceHidden: hidden,
		evidenceVersion: spec.unknownProof === true ? 0 : 1,
		problems: toProblems(spec),
		example: hidden ? null : (spec.example ?? null),
		coverage: spec.coverage ?? null,
		endedAt: null,
		endReason: null,
		marks: marksOf(resultId, spec),
		disagreements: disagreementsOf(resultId),
		feedback: feedbackFor(resultId).map(({ review: owner, item }) =>
			boundFeedbackOf(toFeedback(owner, item)),
		),
		...(typeof input === "string" ? {} : input),
	};
}

export const reviewResultDetail = resultDetail();

export function problemDetail(problemId: string): ReviewProblemDetail {
	for (const { review, result } of allResultSpecs) {
		const row = toProblemRows(review, result).find((entry) => entry.id === problemId);
		const problem = toProblems(result).find((entry) => entry.id === problemId);
		if (row !== undefined && problem !== undefined) {
			return { ...row, problem, result: boundResultOf(toResult(review, result)) };
		}
	}
	throw new Error(`No problem ${problemId} in the fixture`);
}

export const reviewProblemDetail: ReviewProblemDetail = problemDetail(
	problemIdOf("66666666-6666-6666-6666-666666666666", 1),
);

export const evidenceHiddenProblemDetail: ReviewProblemDetail = problemDetail(
	problemIdOf("66666666-7777-7777-7777-777777777777", 0),
);

const MODEL = "openai/gpt-oss-120b";

function finishedAt(review: ReviewSpec): Date | null {
	return review.status === "RUNNING" || review.status === "QUEUED"
		? null
		: new Date(new Date(review.startedAt).getTime() + 5 * MINUTE);
}

const PRACTICE_STATUS_OF: Record<ReviewSummary["status"], ReviewPractice["status"]> = {
	QUEUED: "QUEUED",
	RUNNING: "RUNNING",
	COMPLETE: "DONE",
	PARTIAL: "DONE",
	FAILED: "FAILED",
	CANCELLED: "FAILED",
};

function practicesOf(review: ReviewSpec): ReviewPractice[] {
	const slugs = [
		...new Set([...review.results.map((result) => result.practiceSlug), ...(review.pending ?? [])]),
	];
	const reviewed = slugs.map((practiceSlug): ReviewPractice => {
		const practice = practiceOf(practiceSlug);
		const results = review.results.filter((result) => result.practiceSlug === practiceSlug);
		const items = new Set(results.map((result) => itemOf(review, result).id)).size;
		return {
			practiceSlug,
			practiceName: practiceNameOf(practiceSlug),
			practiceVersionId: practice.currentVersion.id,
			number: practice.currentVersion.number,
			status: results.length > 0 ? "DONE" : PRACTICE_STATUS_OF[review.status],
			reason: null,
			itemCounts: {
				listed: Math.max(items, 1),
				reviewed: items,
				reused: 0,
				withheld: 0,
				notReviewed: results.length === 0 && review.status !== "RUNNING" ? 1 : 0,
			},
			results: resultCounts(results.map((result) => result.outcome)),
			script: null,
		};
	});
	const withheld = (review.withheld ?? []).map(({ practiceSlug, reason }): ReviewPractice => ({
		practiceSlug,
		practiceName: practiceNameOf(practiceSlug),
		practiceVersionId: practiceOf(practiceSlug).currentVersion.id,
		number: practiceOf(practiceSlug).currentVersion.number,
		status: "WITHHELD",
		reason,
		itemCounts: { listed: 1, reviewed: 0, reused: 0, withheld: 1, notReviewed: 0 },
		results: resultCounts([]),
		script: null,
	}));
	return [...reviewed, ...withheld];
}

function startedByOf(review: ReviewSpec): ItemEvent | null {
	if (!hasText(review.event)) {
		return null;
	}
	const occurredAt = new Date(new Date(review.startedAt).getTime() - 20_000);
	return {
		id: `${review.id.slice(0, -3)}e00`,
		itemId: review.item.id,
		name: review.event,
		itemVersion: review.item.kind === ITEM_KIND.pullRequest ? ITEM_VERSION : null,
		occurredAt,
		foundBy: "WEBHOOK",
		state: "STARTED",
		reason: null,
		decidedAt: occurredAt,
		reviewId: review.id,
	};
}

function toReview(review: ReviewSpec): ReviewSummary {
	const createdAt = new Date(review.startedAt);
	return {
		id: review.id,
		kind: review.kind ?? "ITEM",
		item: review.item,
		itemVersion: review.item.kind === ITEM_KIND.pullRequest ? ITEM_VERSION : null,
		origin: originOf(review),
		status: review.status,
		stopReason: review.stopReason ?? null,
		startedBy: startedByOf(review),
		requestedBy: review.requestedBy ?? null,
		batchId: review.batchId ?? null,
		createdAt,
		orderedAt: createdAt,
		completedAt: finishedAt(review),
		practices: practicesOf(review),
		results: resultCounts(review.results.map((result) => result.outcome)),
		feedback: feedbackCounts(review.feedback.map((item) => item.state)),
		jobCount: review.jobs ?? 1,
		activeJobCount: review.status === "RUNNING" || review.status === "QUEUED" ? 1 : 0,
		model: MODEL,
	};
}

export const reviews: ReviewSummary[] = allReviews
	.map(toReview)
	.sort((a, b) => b.createdAt.getTime() - a.createdAt.getTime());

export function reviewDeveloperOf(reviewId: string): ReviewDeveloper | null {
	return allReviews.find((entry) => entry.id === reviewId)?.developer ?? null;
}

export function reviewRefOf(review: ReviewSummary): ReviewRef {
	return {
		id: review.id,
		kind: review.kind,
		item: review.item,
		itemVersion: review.itemVersion,
		origin: review.origin,
		status: review.status,
		createdAt: review.createdAt,
		completedAt: review.completedAt,
	};
}

const JOB_STATUS_OF: Record<ReviewSummary["status"], ReviewJob["status"]> = {
	QUEUED: "QUEUED",
	RUNNING: "RUNNING",
	COMPLETE: "COMPLETED",
	PARTIAL: "COMPLETED",
	FAILED: "FAILED",
	CANCELLED: "CANCELLED",
};

/** Processing starts only when a job completes, so a queued or running job carries NONE, as the server sends it. */
function processingOf(status: ReviewJob["status"]): ReviewJob["processingStatus"] {
	return status === "COMPLETED" ? "DONE" : "NONE";
}

function exitCodeOf(status: ReviewJob["status"]): number | null {
	if (status === "RUNNING" || status === "QUEUED") {
		return null;
	}
	return status === "FAILED" ? 1 : 0;
}

function jobsOf(summary: ReviewSummary, count: number): ReviewJob[] {
	const started = summary.createdAt.getTime();
	const status = JOB_STATUS_OF[summary.status];
	return Array.from({ length: count }, (_, index) => {
		const startedAt = new Date(started + 40_000 + index * 70_000);
		const last = index === count - 1;
		const jobStatus = summary.status === "PARTIAL" && last ? "CANCELLED" : status;
		const ended = jobStatus !== "RUNNING" && jobStatus !== "QUEUED";
		return {
			id: `${summary.id.slice(0, -3)}${String(index).padStart(3, "0")}`,
			// The server counts attempts from 0.
			attempt: 0,
			status: jobStatus,
			processingStatus: processingOf(jobStatus),
			availableAt: summary.createdAt,
			startedAt: jobStatus === "QUEUED" ? null : startedAt,
			completedAt: ended ? new Date(startedAt.getTime() + 60_000) : null,
			exitCode: exitCodeOf(jobStatus),
			errorMessage:
				jobStatus === "FAILED"
					? "Cannot compute diff: all resolution strategies failed for commit 27f4e88c."
					: null,
			traceId: `4bf92f3577b34da6a3ce929d0e0e47${String(index).padStart(2, "0")}`,
		};
	});
}

function usageOf(jobs: readonly ReviewJob[]): ReviewUsage {
	const attempts = jobs
		.filter((job) => job.startedAt !== null)
		.map((job) => ({
			attempt: job.attempt,
			jobId: job.id,
			pricingState: "PRICED" as const,
			costUsd: "0.0125",
			calls: 3,
			inputTokens: 11_000,
			outputTokens: 300,
			reasoningTokens: 40,
			cacheReadTokens: 0,
			cacheWriteTokens: 0,
		}));
	return {
		attempts,
		calls: attempts.length * 3,
		inputTokens: attempts.length * 11_000,
		outputTokens: attempts.length * 300,
		costUsd: (attempts.length * 0.0125).toFixed(4),
		priced: true,
	};
}

function detailOf(summary: ReviewSummary, jobs: ReviewJob[] | null): ReviewDetail {
	return {
		...summary,
		runtime: jobs === null ? null : { jobs },
		usage: usageOf(jobs ?? []),
		stageTiming: (jobs ?? []).map((job) => ({
			stage: "RUN",
			startedAt: job.startedAt,
			completedAt: job.completedAt,
			durationMs:
				job.startedAt === null || job.completedAt === null
					? null
					: job.completedAt.getTime() - job.startedAt.getTime(),
		})),
		generatedPaths: null,
	};
}

export function reviewDetail(input: string | Partial<ReviewDetail> = {}): ReviewDetail {
	const reviewId = typeof input === "string" ? input : "11111111-1111-1111-1111-111111111111";
	const spec = reviewSpec(reviewId);
	const summary = toReview(spec);
	return {
		...detailOf(summary, spec.runtimeExpired === true ? null : jobsOf(summary, spec.jobs ?? 1)),
		...(typeof input === "string" ? {} : input),
	};
}

export const reviewDetailCompleted = reviewDetail();

export const reviewDetailRunning: ReviewDetail = reviewDetail(
	"aaaaaaaa-8888-8888-8888-888888888888",
);

export const reviewDetailScanManyJobs: ReviewDetail = reviewDetail(
	"dddddddd-9999-9999-9999-999999999999",
);

export const reviewDetailExpiredRuntime: ReviewDetail = reviewDetail(
	"bbbbbbbb-8888-8888-8888-888888888888",
);

function standaloneReview(
	id: string,
	overrides: Partial<ReviewSummary>,
	jobs: (summary: ReviewSummary) => ReviewJob[],
): ReviewDetail {
	const summary: ReviewSummary = {
		...toReview(reviewSpec("11111111-1111-1111-1111-111111111111")),
		id,
		results: resultCounts([]),
		feedback: ZERO_FEEDBACK,
		practices: [],
		...overrides,
	};
	const runtime = jobs(summary);
	return { ...detailOf(summary, runtime), jobCount: runtime.length };
}

export const reviewDetailQueued: ReviewDetail = standaloneReview(
	"0a0a0a0a-1111-4111-8111-000000000001",
	{
		status: "QUEUED",
		createdAt: minutesBefore(2),
		orderedAt: minutesBefore(2),
		completedAt: null,
		activeJobCount: 1,
	},
	(summary) => jobsOf(summary, 1),
);

export const reviewDetailBackingOff: ReviewDetail = standaloneReview(
	"0a0a0a0a-1111-4111-8111-000000000002",
	{
		status: "QUEUED",
		createdAt: minutesBefore(20),
		orderedAt: minutesBefore(20),
		completedAt: null,
		activeJobCount: 1,
	},
	(summary) => {
		const [first] = jobsOf({ ...summary, status: "FAILED" }, 1);
		const [retry] = jobsOf(summary, 1);
		if (first === undefined || retry === undefined) {
			throw new Error("A backing-off review needs two jobs");
		}
		return [
			{ ...first, errorMessage: "Runner exited with code 137 (out of memory).", exitCode: 137 },
			{ ...retry, id: `${retry.id.slice(0, -1)}1`, attempt: 1, availableAt: minutesAfter(3) },
		];
	},
);

export const reviewDetailTimedOut: ReviewDetail = standaloneReview(
	"0a0a0a0a-1111-4111-8111-000000000003",
	{ status: "FAILED", stopReason: "TIMED_OUT" },
	(summary) =>
		jobsOf({ ...summary, status: "FAILED" }, 1).map((job) => ({
			...job,
			status: "TIMED_OUT",
			exitCode: 124,
			errorMessage: "The agent exceeded the 1200s timeout and was stopped.",
		})),
);

export const reviewDetailProcessingFailed: ReviewDetail = standaloneReview(
	"0a0a0a0a-1111-4111-8111-000000000004",
	{ status: "FAILED", stopReason: "PROCESSING_FAILED" },
	(summary) =>
		jobsOf({ ...summary, status: "COMPLETE" }, 1).map((job) => ({
			...job,
			processingStatus: "FAILED",
			errorMessage: "Processing the review output failed.",
		})),
);

export const reviewDetailCancelledOnBudget: ReviewDetail = standaloneReview(
	"0a0a0a0a-1111-4111-8111-000000000005",
	{ status: "CANCELLED", stopReason: "BUDGET" },
	(summary) => jobsOf(summary, 1).map((job) => ({ ...job, startedAt: null, exitCode: null })),
);

/** It failed while preparing, before any model call, so it recorded no result and spent nothing. */
export const reviewDetailPreparationFailed: ReviewDetail = {
	...standaloneReview(
		"0a0a0a0a-1111-4111-8111-000000000006",
		{ status: "FAILED", stopReason: "PREPARATION_FAILED" },
		(summary) => jobsOf(summary, 1),
	),
	usage: usageOf([]),
};

export const reviewUsageTwoAttempts: ReviewUsage = {
	attempts: [
		{
			attempt: 0,
			jobId: "11111111-1111-1111-1111-111111111000",
			pricingState: "PRICED",
			costUsd: "0.0125",
			calls: 3,
			inputTokens: 11_000,
			outputTokens: 300,
			reasoningTokens: 40,
			cacheReadTokens: 0,
			cacheWriteTokens: 0,
		},
		{
			attempt: 1,
			jobId: "11111111-1111-1111-1111-111111111001",
			pricingState: "PRICED",
			costUsd: "0.0300",
			calls: 4,
			inputTokens: 13_000,
			outputTokens: 614,
			reasoningTokens: 80,
			cacheReadTokens: 2000,
			cacheWriteTokens: 0,
		},
	],
	calls: 7,
	inputTokens: 24_000,
	outputTokens: 914,
	costUsd: "0.0425",
	priced: true,
};

export const reviewUsageUnpriced: ReviewUsage = {
	attempts: [
		{
			attempt: 0,
			jobId: "11111111-1111-1111-1111-111111111000",
			pricingState: "UNPRICED",
			costUsd: null,
			calls: 7,
			inputTokens: 24_000,
			outputTokens: 914,
			reasoningTokens: 120,
			cacheReadTokens: 0,
			cacheWriteTokens: 0,
		},
	],
	calls: 7,
	inputTokens: 24_000,
	outputTokens: 914,
	costUsd: null,
	priced: false,
};

function cycled<T>(specs: readonly T[], index: number): T {
	const spec = specs[index % specs.length];
	if (spec === undefined) {
		throw new Error("A page cannot be filled from an empty list of specs.");
	}
	return spec;
}

function cycledId(id: string, cycle: number): string {
	return `${id.slice(0, -2)}${(10 + cycle).toString(16).padStart(2, "0")}`;
}

export function manyResults(count: number): ReviewResult[] {
	const base = reviewResults;
	const rows = Array.from({ length: count }, (_, index) => {
		const source = cycled(base, index);
		const cycle = Math.floor(index / base.length);
		if (cycle === 0) {
			return source;
		}
		return {
			...source,
			id: cycledId(source.id, cycle),
			recordedAt: new Date(source.recordedAt.getTime() - cycle * 86_400_000),
		};
	});
	// Newest first, as the server sends them, so each day's rows stay together under one heading.
	return rows.sort((a, b) => b.recordedAt.getTime() - a.recordedAt.getTime());
}

export function manyMembers(count: number): WorkspaceMembership[] {
	return Array.from({ length: count }, (_, index) => {
		const source = cycled(workspaceMembers, index);
		const cycle = Math.floor(index / workspaceMembers.length);
		if (cycle === 0) {
			return source;
		}
		const accountId = String(Number(source.account.id) + cycle * 100);
		return {
			...source,
			account: {
				...source.account,
				id: accountId,
				displayName:
					source.account.displayName === null ? null : `${source.account.displayName} ${cycle}`,
				identities: source.account.identities.map((identity) => ({
					...identity,
					login: identity.login === null ? null : `${identity.login}-${cycle}`,
				})),
			},
		};
	});
}

export function manyFeedback(count: number): ReviewFeedback[] {
	const base = reviewFeedback;
	const rows = Array.from({ length: count }, (_, index) => {
		const source = cycled(base, index);
		const cycle = Math.floor(index / base.length);
		if (cycle === 0) {
			return source;
		}
		return {
			...source,
			id: cycledId(source.id, cycle),
			createdAt: new Date(source.createdAt.getTime() - cycle * 86_400_000),
		};
	});
	// Newest first, as the server sends them, so each day's rows stay together under one heading.
	return rows.sort((a, b) => b.createdAt.getTime() - a.createdAt.getTime());
}

/**
 * A story applies the server's facet filtering over its pool of rows; an unset or empty facet
 * selects every row.
 */
export function selects(
	selected: readonly string[] | undefined,
	actual: string | null | undefined,
): boolean {
	return (
		selected === undefined ||
		selected.length === 0 ||
		(hasText(actual) && selected.includes(actual))
	);
}

const outcomeCounts = (
	met: number,
	notMet: number,
	doesNotApply: number,
	couldNotDetermine: number,
): ReviewResultCounts => ({ met, notMet, doesNotApply, couldNotDetermine });

const countsOf = (
	practiceSlug: string,
	results: ReviewResultCounts,
	feedback: Partial<ReviewFeedbackCounts>,
	resultsMarkedIncorrect = 0,
): ReviewPracticeCounts => ({
	practiceSlug,
	practiceName: practiceNameOf(practiceSlug),
	group: groupRefOf(practiceSlug),
	results,
	feedback: { ...ZERO_FEEDBACK, ...feedback },
	resultsMarkedIncorrect,
});

export const practiceCounts: ReviewPracticeCounts[] = [
	countsOf(
		"thin-controllers",
		outcomeCounts(18, 9, 4, 2),
		{ delivered: 7, awaitingApproval: 2, withheld: 2, failed: 1 },
		1,
	),
	countsOf(
		"errors-carry-context",
		outcomeCounts(6, 11, 1, 3),
		{ delivered: 6, ready: 1, rejected: 1, replaced: 1 },
		2,
	),
	countsOf("tests-name-the-behaviour", outcomeCounts(9, 2, 3, 0), { delivered: 2 }),
	countsOf("the-change-explains-itself", outcomeCounts(2, 4, 1, 1), {
		delivered: 1,
		awaitingApproval: 1,
		withheld: 1,
	}),
	countsOf("product-language", outcomeCounts(0, 1, 1, 0), {}),
];

const OVERVIEW_DAYS = REVIEW_RANGE_DEFS["30d"].days;
const OVERVIEW_FROM = reviewRangeStart(STORY_NOW, "30d");

/**
 * Day by day, oldest first, with quiet weekends and a busy stretch so the bars look like a team's.
 * Each row adds up to its stage's total below, as the server's buckets do.
 */
const PER_DAY = {
	reviews: [
		0, 2, 0, 3, 2, 0, 0, 3, 0, 2, 1, 3, 0, 0, 2, 3, 0, 1, 4, 0, 2, 0, 0, 1, 3, 1, 0, 3, 0, 2,
	],
	results: [
		0, 5, 3, 0, 0, 6, 1, 4, 3, 7, 0, 0, 4, 5, 0, 2, 8, 1, 4, 0, 0, 2, 6, 2, 1, 5, 0, 4, 1, 4,
	],
	feedback: [
		0, 1, 0, 2, 1, 0, 0, 2, 1, 1, 1, 2, 0, 0, 1, 2, 0, 1, 2, 1, 1, 0, 0, 1, 1, 1, 0, 1, 0, 1,
	],
} as const;

function overviewBuckets(perDay: Record<keyof typeof PER_DAY, readonly number[]>) {
	return Array.from({ length: OVERVIEW_DAYS }, (_, day): ReviewBucket => ({
		start: addDays(OVERVIEW_FROM, day),
		reviews: perDay.reviews[day] ?? 0,
		results: perDay.results[day] ?? 0,
		feedback: perDay.feedback[day] ?? 0,
	}));
}

const NOTHING_PER_DAY = Array.from({ length: OVERVIEW_DAYS }, () => 0);

export const practiceReviewOverview: ReviewOverview = {
	from: OVERVIEW_FROM,
	to: new Date(STORY_NOW),
	bucket: "DAY",
	buckets: overviewBuckets(PER_DAY),
	reviews: { complete: 31, partial: 1, failed: 3, cancelled: 1, running: 1, queued: 2 },
	results: outcomeCounts(35, 27, 10, 6),
	resultsMarkedIncorrect: 3,
	// One piece of feedback citing two practices counts for each, so these are not the practices' sum.
	feedback: {
		...ZERO_FEEDBACK,
		delivered: 14,
		awaitingApproval: 3,
		withheld: 3,
		failed: 1,
		ready: 1,
		rejected: 1,
		replaced: 1,
		unconfirmed: 1,
	},
	practices: practiceCounts,
};

export const deliveredByLocationCounts: ReasonCount[] = [
	{ reason: "GITHUB", description: FEEDBACK_LOCATION_DEFS.GITHUB.label, count: 8 },
	{ reason: "SLACK", description: FEEDBACK_LOCATION_DEFS.SLACK.label, count: 2 },
	{ reason: "PRACTICE_PAGES", description: FEEDBACK_LOCATION_DEFS.PRACTICE_PAGES.label, count: 4 },
];

export const withheldByReasonCounts: ReasonCount[] = [
	{ reason: "VOLUME_CAPPED", description: withholdingReasonSentence("VOLUME_CAPPED"), count: 1 },
	{ reason: "ITEM_MERGED", description: withholdingReasonSentence("ITEM_MERGED"), count: 2 },
];

export const quietPracticeReviewOverview: ReviewOverview = {
	...practiceReviewOverview,
	buckets: overviewBuckets({
		reviews: NOTHING_PER_DAY,
		results: NOTHING_PER_DAY,
		feedback: NOTHING_PER_DAY,
	}),
	reviews: { complete: 0, partial: 0, failed: 0, cancelled: 0, running: 0, queued: 0 },
	results: outcomeCounts(0, 0, 0, 0),
	resultsMarkedIncorrect: 0,
	feedback: ZERO_FEEDBACK,
	practices: [],
};

export function readyReviewOverview(
	overview: ReviewOverview = practiceReviewOverview,
): OverviewRegionState {
	return { status: "ready", overview, stale: false };
}

/** The same thirty days as the lists' day filters, so a count's link can be read in a story. */
export const reviewOverviewScope: OutcomeScope = {
	from: toDayParam(OVERVIEW_FROM),
	to: toDayParam(new Date(STORY_NOW)),
};

export const practiceReviewTiming: ReviewTiming = {
	from: OVERVIEW_FROM,
	to: new Date(STORY_NOW),
	asked: { count: 40, medianSeconds: 120, p90Seconds: 600 },
	queue: { count: 258, medianSeconds: 26, p90Seconds: 60 },
	preparation: { count: 51, medianSeconds: 20, p90Seconds: 40 },
	model: { count: 51, medianSeconds: 700, p90Seconds: 1080 },
	posted: { count: 14, medianSeconds: 12, p90Seconds: 30 },
	total: { count: 14, medianSeconds: 878, p90Seconds: 1500 },
	totalShares: {
		asked: 120 / 878,
		queue: 26 / 878,
		preparation: 20 / 878,
		model: 700 / 878,
		runUnsplit: 0,
		posted: 12 / 878,
	},
	finished: { count: 51, medianSeconds: 866, p90Seconds: 1490 },
	finishedShares: {
		asked: 120 / 866,
		queue: 26 / 866,
		preparation: 20 / 866,
		model: 700 / 866,
		runUnsplit: 0,
		posted: null,
	},
	read: { count: 11, medianSeconds: 67_020, p90Seconds: 90_000 },
	waitingLong: 0,
	finishedAfterMerge: 9,
	finishedAfterMergeStartedByMerge: 0,
	feedbackWithheldAsMerged: 0,
};

const queueItem = (
	index: number,
	state: "running" | "waiting" | "held",
	item: ItemRef,
	developer: ReviewDeveloper | null,
	minutes: number,
): ReviewQueueItem => ({
	reviewId: `0c0c0c0c-0000-4000-8000-${String(index).padStart(12, "0")}`,
	jobId: `0d0d0d0d-0000-4000-8000-${String(index).padStart(12, "0")}`,
	status: state === "running" ? "RUNNING" : "QUEUED",
	heldForBudget: state === "held",
	item,
	developer,
	since: minutesBefore(minutes),
});

export const reviewQueueHead = {
	runningLong: queueItem(1, "running", reviewItem, ada, 3),
	runningShort: queueItem(2, "running", gitlabMergeRequest, grace, 1),
	waitingLong: queueItem(3, "waiting", webhookRetryPullRequest, alan, 4),
	waitingShort: queueItem(4, "waiting", leagueColumnsPullRequest, katherine, 2),
	waitingNew: queueItem(5, "waiting", invoiceBackfillMergeRequest, barbara, 1),
	scanJob: queueItem(6, "held", scannedRepository, null, 1),
};

export const idleReviewQueue: ReviewQueue = {
	asOf: new Date(STORY_NOW),
	running: 0,
	activeWorkers: 0,
	waiting: 0,
	heldForBudget: 0,
	oldestWaitingSince: null,
	head: [],
	finishedLastHour: 12,
	finishedLastDay: 96,
	finishedPerHourLastDay: 4,
};

export const busyReviewQueue: ReviewQueue = {
	...idleReviewQueue,
	running: 2,
	activeWorkers: 2,
	waiting: 4,
	heldForBudget: 1,
	oldestWaitingSince: minutesBefore(4),
	head: Object.values(reviewQueueHead),
};

export const awaitingApprovalFeedback: ReviewFeedback[] = reviewFeedback
	.filter((item) => item.state === "DELIVERED" || item.state === "WITHHELD")
	.slice(0, 3)
	.map((item) => ({
		...item,
		state: "AWAITING_APPROVAL",
		deliveredAt: null,
		stopReason: "PRACTICE_REQUIRES_APPROVAL",
		withdrawn: false,
	}));

export function itemEventsOf(review: ReviewSummary): ItemEvent[] {
	const asked = review.createdAt.getTime();
	const event = (
		suffix: string,
		name: string,
		offsetMinutes: number,
		state: ItemEvent["state"],
		reason: ItemEvent["reason"],
	): ItemEvent => {
		const occurredAt = new Date(asked + offsetMinutes * MINUTE);
		return {
			id: `${review.id.slice(0, -3)}${suffix}`,
			itemId: review.item.id,
			name,
			itemVersion: review.itemVersion,
			occurredAt,
			foundBy: "WEBHOOK",
			state,
			reason,
			decidedAt: occurredAt,
			reviewId: null,
		};
	};
	return [
		event("e01", "scm.pull_request.opened", -90, "WITHHELD", "COOLDOWN_ACTIVE"),
		...(review.startedBy === null ? [] : [review.startedBy]),
		event("e02", "scm.pull_request.synchronized", 30, "WAITING", "MODEL_UNAVAILABLE"),
	];
}

export function itemTraceOf(review: ReviewSummary): ItemTrace {
	const ref = reviewRefOf(review);
	return {
		...itemTrace,
		item: review.item,
		events: itemEventsOf(review),
		reviews: [ref],
		practices: itemTrace.practices.map((entry) =>
			entry.review === null ? entry : { ...entry, review: ref },
		),
	};
}

export function reviewFeedbackChecksOf(review: ReviewSummary): FeedbackCheck[] {
	const own = allFeedbackSpecs
		.filter(({ review: owner }) => owner.id === review.id)
		.flatMap(({ item }) => checksOf(item));
	const end = review.completedAt ?? review.createdAt;
	return [...own, { ...deniedFeedbackCheck, checkedAt: new Date(end.getTime() + 30_000) }];
}

export const mergedPullRequestFacts: ItemFacts = {
	item: reviewItem,
	facts: [
		{ name: "State", value: "Merged", url: null },
		{ name: "Author", value: "ada", url: "https://github.com/ada" },
		{ name: "Branch", value: "feature/retry-service → main", url: null },
		{ name: "Head", value: "27f4e88c", url: `${reviewItem.url ?? ""}/commits/27f4e88c` },
		{ name: "Size", value: "+120 −30 in 7 files", url: null },
		{ name: "Commits", value: "3", url: null },
		{ name: "Checks", value: "Failing", url: `${reviewItem.url ?? ""}/checks` },
		{ name: "Merged", value: "7 Aug 2026, 10:02", url: null },
	],
};

export const openIssueFacts: ItemFacts = {
	item: trackerIssue,
	facts: [
		{ name: "State", value: "Open", url: null },
		{ name: "Author", value: "grace", url: "https://github.com/grace" },
		{ name: "Labels", value: null, url: null },
	],
};

export const practiceVersions: PracticeVersion[] = [
	practiceVersion(definitionOf({ name: "Thin controllers" }), {
		id: "303",
		number: 3,
		createdAt: new Date("2026-08-02T09:00:00Z"),
		changed: ["CRITERIA", "READS"],
		reviewChanged: true,
	}),
	practiceVersion(definitionOf({ name: "Thin controllers" }), {
		id: "302",
		number: 2,
		createdAt: new Date("2026-07-20T09:00:00Z"),
		changed: ["GUIDANCE"],
		reviewChanged: false,
	}),
	practiceVersion(definitionOf({ name: "Thin controllers" }), {
		id: "301",
		number: 1,
		createdAt: new Date("2026-07-01T09:00:00Z"),
		changed: [],
		reviewChanged: false,
	}),
];

const scriptRun = (
	practiceSlug: string,
	fields: Pick<PracticeScriptRun, "scriptKind" | "status"> & Partial<PracticeScriptRun>,
): PracticeScriptRun => {
	const practice = workspacePractices.find((entry) => entry.slug === practiceSlug);
	return {
		jobId: "11111111-1111-1111-1111-111111111000",
		attempt: 1,
		practiceId: practice?.id ?? "999",
		practiceVersionId: practice?.currentVersion.id ?? "99901",
		stopReason: null,
		noResultCause: null,
		finishedAt: new Date("2026-07-28T13:39:30Z"),
		durationMs: 4200,
		error: null,
		models: [],
		reviewModelCalls: null,
		...fields,
	};
};

const decisionModel = (calls: number, tier: "IN_HOUSE" | "CLOUD") => ({
	kind: "DECISION" as const,
	modelId: tier === "CLOUD" ? "gpt-5-mini" : "acme/house-model",
	dataHandlingTier: tier,
	calls,
	inputTokens: calls * 3000,
	outputTokens: calls * 150,
	cacheReadTokens: 0,
	cacheWriteTokens: 0,
});

export const reviewPracticeScripts = {
	fromCode: scriptRun("thin-controllers", {
		scriptKind: "CUSTOM",
		status: "OK",
		models: [decisionModel(12, "CLOUD")],
	}),
	withModels: scriptRun("errors-carry-context", {
		scriptKind: "CUSTOM",
		status: "OK",
		durationMs: 12_840,
		models: [
			decisionModel(9, "IN_HOUSE"),
			{
				kind: "EMBEDDING",
				modelId: "acme/embed",
				dataHandlingTier: "IN_HOUSE",
				calls: 2,
				inputTokens: 4000,
				outputTokens: 0,
				cacheReadTokens: 0,
				cacheWriteTokens: 0,
			},
		],
	}),
	builtIn: scriptRun("the-change-explains-itself", {
		scriptKind: "BUILTIN",
		status: "OK",
		durationMs: 900,
		reviewModelCalls: 4,
	}),
	/** Six submissions, no reads: the evidence check refused four, the tool schema one, then the loop guard ended it. */
	evidenceNotRead: scriptRun("the-change-explains-itself", {
		scriptKind: "BUILTIN",
		status: "FAILED",
		stopReason: "OFF_FORMAT",
		noResultCause: "EVIDENCE_NOT_READ",
		durationMs: 48_300,
		reviewModelCalls: 6,
	}),
	/** Recorded before the runner counted review sessions. */
	builtInUnrecorded: scriptRun("the-change-explains-itself", {
		scriptKind: "BUILTIN",
		status: "FAILED",
		stopReason: "OFF_FORMAT",
		noResultCause: "SESSION_ENDED",
		durationMs: 21_000,
	}),
	withheld: scriptRun("product-language", {
		scriptKind: "CUSTOM",
		status: "WITHHELD",
		stopReason: "UNAVAILABLE",
		durationMs: null,
	}),
	failed: scriptRun("tests-name-the-behaviour", {
		scriptKind: "CUSTOM",
		status: "FAILED",
		stopReason: "ERROR",
		durationMs: 40,
		error: "TypeError: change.files is not iterable at run (tests-name-the-behaviour.ts:14:23)",
	}),
	outOfTime: scriptRun("review-comments-explain", {
		scriptKind: "CUSTOM",
		status: "FAILED",
		stopReason: "DEADLINE",
		durationMs: 15_000,
	}),
	notFinished: scriptRun("decisions-are-written-down", {
		scriptKind: "CUSTOM",
		status: "NOT_FINISHED",
		durationMs: null,
	}),
	notReached: scriptRun("decisions-are-written-down", {
		scriptKind: "CUSTOM",
		status: "NOT_REACHED",
		durationMs: null,
		reviewModelCalls: 0,
	}),
	removedPractice: scriptRun("retired-practice", {
		scriptKind: "CUSTOM",
		status: "FAILED",
		stopReason: "DEADLINE",
		durationMs: 15_000,
	}),
} satisfies Record<string, PracticeScriptRun>;
