import type {
	AgentJob,
	AutonomyAssignment,
	EvidenceCitation,
	Practice,
	ReviewArtifact,
	ReviewFeedback,
	ReviewFeedbackDetail,
	ReviewObservation,
	ReviewObservationDetail,
	ReviewRunSummary,
	ReviewSubject,
	WorkspaceMembership,
} from "@/api/types.gen";

// The only thing written by hand is REVIEW_FIXTURE: reviews, their observations and the feedback
// those drove. Every count, preview, truncation flag and summary is computed from it below, so a
// fixture whose tallies contradict the records they count is not something you can write.

// ---------------------------------------------------------------------------------------------
// People
// ---------------------------------------------------------------------------------------------

const ada: ReviewSubject = { id: 7, login: "ada", name: "Ada Lovelace" };
const grace: ReviewSubject = { id: 9, login: "grace", name: "Grace Hopper" };
const alan: ReviewSubject = { id: 11, login: "alan", name: "Alan Turing" };
const katherine: ReviewSubject = { id: 14, login: "katherine", name: "Katherine Johnson" };
const barbara: ReviewSubject = { id: 18, login: "barbara", name: "Barbara Liskov" };

export const workspaceMembers: WorkspaceMembership[] = [
	{ userId: ada.id, userLogin: ada.login, userName: ada.name, role: "MEMBER" },
	{ userId: grace.id, userLogin: grace.login, userName: grace.name, role: "ADMIN" },
	{ userId: alan.id, userLogin: alan.login, userName: alan.name, role: "MEMBER" },
	{ userId: katherine.id, userLogin: katherine.login, userName: katherine.name, role: "MEMBER" },
	{ userId: barbara.id, userLogin: barbara.login, userName: barbara.name, role: "MEMBER" },
];

// ---------------------------------------------------------------------------------------------
// The work under review
// ---------------------------------------------------------------------------------------------

// Every list row and every detail header draws its glyph from the provider and its words from the
// kind, so a fixture on a single provider would let a wrong mark or a missing label ship.
export const reviewArtifact: ReviewArtifact = {
	id: 42,
	type: "scm.pull_request",
	provider: "GITHUB",
	number: 1423,
	repositoryName: "ls1intum/Hephaestus",
	title: "Cache the workspace member lookup on the review path",
	url: "https://github.com/ls1intum/Hephaestus/pull/1423",
};

export const gitlabMergeRequest: ReviewArtifact = {
	id: 43,
	type: "scm.pull_request",
	provider: "GITLAB",
	number: 88,
	repositoryName: "platform/billing-service",
	title: "Move invoice numbering behind the billing boundary",
	url: "https://gitlab.example.com/platform/billing-service/-/merge_requests/88",
};

export const slackConversation: ReviewArtifact = {
	id: 81,
	type: "chat.conversation_thread",
	provider: "SLACK",
	channelName: "engineering",
	title: "How should we roll back the pricing migration?",
	url: "https://example.slack.com/archives/C01/p1721400000",
};

export const outlineDocument: ReviewArtifact = {
	id: 96,
	type: "docs.document",
	provider: "OUTLINE",
	title: "Runbook: restoring a workspace from backup",
	url: "https://docs.example.com/doc/runbook-restore",
};

/**
 * No review in the fixture reaches an issue, but `scm.issue` is a kind this build has copy and a
 * glyph for, so something has to render it.
 */
export const trackerIssue: ReviewArtifact = {
	id: 204,
	type: "scm.issue",
	provider: "GITHUB",
	number: 204,
	repositoryName: "ls1intum/Hephaestus",
	title: "Reviews of documents do not record which revision they read",
	url: "https://github.com/ls1intum/Hephaestus/issues/204",
};

const webhookRetryPullRequest: ReviewArtifact = {
	id: 44,
	type: "scm.pull_request",
	provider: "GITHUB",
	number: 1431,
	repositoryName: "ls1intum/Hephaestus",
	title: "Retry webhook deliveries with backoff instead of dropping them",
	url: "https://github.com/ls1intum/Hephaestus/pull/1431",
};

const leagueColumnsPullRequest: ReviewArtifact = {
	id: 45,
	type: "scm.pull_request",
	provider: "GITHUB",
	number: 1436,
	repositoryName: "ls1intum/Hephaestus",
	title: "Drop the unused league columns",
	url: "https://github.com/ls1intum/Hephaestus/pull/1436",
};

const invoiceBackfillMergeRequest: ReviewArtifact = {
	id: 46,
	type: "scm.pull_request",
	provider: "GITLAB",
	number: 91,
	repositoryName: "platform/billing-service",
	title: "Backfill the invoice sequence table",
	url: "https://gitlab.example.com/platform/billing-service/-/merge_requests/91",
};

// ---------------------------------------------------------------------------------------------
// The catalogue
// ---------------------------------------------------------------------------------------------

const groupNames = {
	"code-quality": "Code quality",
	architecture: "Architecture",
	testing: "Testing",
	documentation: "Documentation",
	collaboration: "Collaboration",
} as const;

type GroupSlug = keyof typeof groupNames;

const group = (slug: GroupSlug) => ({ slug, name: groupNames[slug] });

export const practiceGroups = Object.entries(groupNames).map(([slug, name], index) => ({
	id: index + 1,
	slug,
	name,
	active: true,
	displayOrder: index,
	createdAt: new Date("2026-01-01T00:00:00Z"),
}));

/**
 * Fills in the governance fields no review surface reads, so a fixture spells out only what it
 * varies. `whyItMatters` and `whatGoodLooksLike` are the prose `PracticeDetailHoverCard` shows: a
 * fixture without them renders a bare link and its story passes while proving nothing.
 */
function practiceFixture(
	practice: Pick<
		Practice,
		"id" | "slug" | "name" | "groupSlug" | "criteria" | "whyItMatters" | "whatGoodLooksLike"
	> & { displayOrder: number; autonomy: AutonomyAssignment["effective"] },
): Practice {
	const { autonomy, ...rest } = practice;
	return {
		...rest,
		artifactKind: "scm.pull_request",
		createdAt: new Date("2026-01-01T00:00:00Z"),
		updatedAt: new Date("2026-06-01T00:00:00Z"),
		bindings: [],
		autonomy: { effective: autonomy, inherited: false, source: "PRACTICE" },
		automatedReviewPolicy: {
			automatedReview: {
				evidenceSufficiency: "SUFFICIENT_WHEN_REQUIREMENTS_MET",
				mode: "LANGUAGE_MODEL",
			},
			knownLimitations: [],
			sourceContractVersion: "1.0.0",
			whenEvidenceIsInsufficient: "SKIP_AUTOMATED_REVIEW",
		},
		automatedReviewValidation: {
			policyDigest: "sha256:0",
			reviewRuleFingerprint: "rule:1",
			sourceContractVersion: "1.0.0",
			status: "AUTHOR_DECLARED",
		},
	};
}

export const workspacePractices: Practice[] = [
	practiceFixture({
		id: 1,
		slug: "thin-controllers",
		name: "Thin controllers",
		groupSlug: "code-quality",
		criteria: "A controller validates its input, delegates, and maps the result. Nothing else.",
		whyItMatters:
			"Logic that lives in a controller can only be tested through HTTP, so it tends not to be tested at all.",
		whatGoodLooksLike:
			"The controller method reads as a list of three steps and names a service that does the work.",
		displayOrder: 0,
		autonomy: "HUMAN_APPROVAL",
	}),
	practiceFixture({
		id: 2,
		slug: "product-language",
		name: "Product language",
		groupSlug: "architecture",
		criteria: "Names in the code are the names the people using it would use.",
		whyItMatters:
			"A boundary named after its storage stops matching the product the day the storage changes, and every reader after that has to translate.",
		whatGoodLooksLike:
			"A route, a class or a column could be read aloud in a planning meeting without explanation.",
		displayOrder: 1,
		autonomy: "AUTOMATIC",
	}),
	practiceFixture({
		id: 3,
		slug: "errors-carry-context",
		name: "Errors carry their context",
		groupSlug: "code-quality",
		criteria:
			"A failure reports which operation failed and on what, not only that something went wrong.",
		whyItMatters:
			"An error that says only what went wrong sends the next person to the logs to work out where, and at three in the morning that is the whole incident.",
		whatGoodLooksLike:
			"The message names the thing being acted on and the action attempted, and two different causes do not share one status code.",
		displayOrder: 2,
		autonomy: "AUTOMATIC",
	}),
	practiceFixture({
		id: 4,
		slug: "tests-name-the-behaviour",
		name: "Tests name the behaviour",
		groupSlug: "testing",
		criteria: "A test's name states the behaviour it pins down, not the method it calls.",
		whyItMatters:
			"A red build should say what broke before anyone opens the file; a name like testCache2 makes the reader run the test to find out.",
		whatGoodLooksLike: "The failure line of a broken build reads as a sentence about the system.",
		displayOrder: 3,
		autonomy: "HUMAN_APPROVAL",
	}),
	practiceFixture({
		id: 5,
		slug: "the-change-explains-itself",
		name: "The change explains itself",
		groupSlug: "documentation",
		criteria: "The description says why the change was made, not only what it does.",
		whyItMatters:
			"Six months later the diff still says what changed. Nothing else records why, so the next person reverses the decision without knowing there was one.",
		whatGoodLooksLike:
			"A reviewer who was not in the room can tell what problem the change is answering.",
		displayOrder: 4,
		autonomy: "HUMAN_APPROVAL",
	}),
	practiceFixture({
		id: 6,
		slug: "decisions-are-written-down",
		name: "Decisions are written down",
		groupSlug: "collaboration",
		criteria:
			"A decision reached in a discussion ends up somewhere durable before the thread ends.",
		whyItMatters:
			"A choice that only exists in a chat thread is re-argued every time somebody new joins, and nobody can tell a decision from an opinion.",
		whatGoodLooksLike:
			"The thread ends with what was chosen, who chose it, and a link to where it now lives.",
		displayOrder: 5,
		autonomy: "AUTOMATIC",
	}),
];

// ---------------------------------------------------------------------------------------------
// The spec
// ---------------------------------------------------------------------------------------------

interface FeedbackSpec {
	id: string;
	/** The composed body exactly as the server stores it: Markdown, headings and all. The list's
	 * preview and truncation flag are derived from it. */
	body?: string;
	channel: ReviewFeedback["channel"];
	outcome: ReviewFeedback["deliveryState"];
	withheldFor?: ReviewFeedback["suppressionReason"];
	recipient: ReviewSubject;
	composedAt: string;
	deliveredAt?: string;
	/** The feedback this one took the place of. */
	replaces?: string;
	/** Where an inline note landed. Absent means it was posted as a summary on the work. */
	anchoredAt?: { path: string; startLine: number; endLine: number };
	/** Observations it draws on, in render order; the first leads it. */
	from: string[];
}

interface ObservationSpec {
	assessmentStatus: ReviewObservation["assessmentStatus"];
	id: string;
	summary: string;
	evidenceRationale: string;
	practiceSlug: string;
	group: GroupSlug;
	presence: ReviewObservation["presence"];
	assessment?: ReviewObservation["assessment"];
	severity?: ReviewObservation["severity"];
	claimCurrentness?: ReviewObservation["claimCurrentness"];
	observedAt: string;
	evidence?: EvidenceCitation[];
}

interface RunSpec {
	id: string;
	work: ReviewArtifact;
	status: ReviewRunSummary["status"];
	startedAt: string;
	/** Whose work this review was about; every observation and every recipient inherits it. */
	developer: ReviewSubject;
	origin?: ReviewObservation["origin"];
	observations: ObservationSpec[];
	feedback: FeedbackSpec[];
}

const diff = (
	path: string,
	startLine: number,
	endLine: number,
	quote: string,
): EvidenceCitation => ({
	sourceKind: "scm.pull-request.diff",
	artifactPath: "inputs/context/diff.patch",
	path,
	side: "NEW",
	startLine,
	endLine,
	quote,
	quoteRedacted: false,
});

const cited = (sourceKind: string, path: string, quote: string, line = 1): EvidenceCitation => ({
	sourceKind,
	artifactPath: `inputs/context/${sourceKind.replace(/\./g, "-")}.json`,
	path,
	startLine: line,
	endLine: line,
	quote,
	quoteRedacted: false,
});

/**
 * Shaped the way `DeliveryComposer` shapes a real note: a lead line, bold observation headings with an
 * inline-code locator, a fenced quote of the code, an italic why-this-matters, and a rule between
 * observations. Long enough that the server's preview cut lands inside the first fence, which is the
 * case a fixture of one-sentence previews never reaches.
 */
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
	"Callers get one 404 for two very different situations, so an operator debugging a support ticket",
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

/**
 * Spans every conclusion a review can reach and every outcome a piece of feedback can have, with a
 * withheld piece under each family an operator can filter by. Deliberately ordinary otherwise: a set
 * that is mostly failures and empties teaches a reader that the product mostly does not work.
 */
export const REVIEW_FIXTURE: RunSpec[] = [
	{
		id: "11111111-1111-1111-1111-111111111111",
		work: reviewArtifact,
		status: "COMPLETED",
		startedAt: "2026-07-28T13:35:00Z",
		developer: ada,
		observations: [
			{
				id: "55555555-5555-5555-5555-555555555555",
				summary: "The controller delegates before it does anything else",
				evidenceRationale:
					"The handler is three statements long: it binds the request, calls ReviewQueryService and maps the result. The caching decision that arrived with this change sits entirely inside the service, so it can be exercised without standing up HTTP.",
				practiceSlug: "thin-controllers",
				group: "code-quality",
				assessmentStatus: "ASSESSED",
				presence: "PRESENT",
				assessment: "GOOD",
				observedAt: "2026-07-28T13:40:00Z",
				evidence: [
					diff(
						"server/application/src/main/java/de/tum/cit/aet/hephaestus/practices/reviewoutput/ReviewQueryController.java",
						61,
						64,
						"var page = reviewQueryService.observations(workspaceSlug, filter, pageable);\nreturn ResponseEntity.ok(page.map(ReviewObservationDTO::from));",
					),
				],
			},
			{
				id: "66666666-6666-6666-6666-666666666666",
				summary: "A cache miss and a permission failure come back as the same 404",
				evidenceRationale:
					"findVisible returns an empty Optional both when the row does not exist and when the caller has no grant on the workspace, and the only caller turns either into a NotFoundException. Two situations that need different answers — retry the link, or ask for access — arrive as one.",
				practiceSlug: "errors-carry-context",
				group: "code-quality",
				assessmentStatus: "ASSESSED",
				presence: "PRESENT",
				assessment: "BAD",
				severity: "MAJOR",
				observedAt: "2026-07-28T13:39:00Z",
				evidence: [
					diff(
						"server/application/src/main/java/de/tum/cit/aet/hephaestus/practices/reviewoutput/ReviewQueryService.java",
						116,
						119,
						"return repository.findVisible(workspaceId, id)\n    .orElseThrow(() -> new NotFoundException());",
					),
					cited(
						"scm.repository.tree",
						"server/application/src/main/java/de/tum/cit/aet/hephaestus/core/security/WorkspaceGuard.java",
						'throw new ForbiddenException("No grant on workspace " + slug);',
						88,
					),
					cited(
						"scm.review-threads",
						"Review thread on ReviewQueryService.java",
						"Do we lose the difference between 'gone' and 'not yours' here?",
					),
				],
			},
			{
				id: "77777777-7777-7777-7777-777777777777",
				summary: "Three of the new tests are named after the method they call",
				evidenceRationale:
					"testCache1, testCache2 and testCache3 pin down a cold read, a warm read and an eviction after a membership change. The names carry none of that, so a red build names a file and a number rather than the behaviour that broke.",
				practiceSlug: "tests-name-the-behaviour",
				group: "testing",
				assessmentStatus: "ASSESSED",
				presence: "PRESENT",
				assessment: "BAD",
				severity: "MINOR",
				observedAt: "2026-07-28T13:38:00Z",
				evidence: [
					diff(
						"server/application/src/test/java/de/tum/cit/aet/hephaestus/practices/reviewoutput/ReviewQueryServiceTest.java",
						44,
						46,
						"@Test\nvoid testCache1() {",
					),
				],
			},
			{
				id: "88888888-8888-8888-8888-888888888888",
				summary: "The description lists the files touched and stops there",
				evidenceRationale:
					"The body of the pull request is a bullet per changed file. Nothing in it says what was slow, how slow, or why a cache was the answer rather than a narrower query — the questions a reviewer who was not in that conversation has to ask before they can agree.",
				practiceSlug: "the-change-explains-itself",
				group: "documentation",
				assessmentStatus: "ASSESSED",
				presence: "ABSENT",
				assessment: "BAD",
				severity: "INFO",
				observedAt: "2026-07-28T13:37:00Z",
				evidence: [
					cited(
						"scm.pull-request.core",
						"Pull request description",
						"- ReviewQueryService.java\n- ReviewQueryController.java\n- ReviewQueryServiceTest.java",
					),
				],
			},
		],
		feedback: [
			{
				id: "33333333-3333-3333-3333-333333333333",
				body: "You kept the controller focused and moved the caching decision into the service, which is what lets the rest of this be tested without HTTP.",
				channel: "IN_CONTEXT",
				outcome: "DELIVERED",
				recipient: ada,
				composedAt: "2026-07-28T13:42:00Z",
				deliveredAt: "2026-07-28T13:43:00Z",
				from: ["55555555-5555-5555-5555-555555555555"],
			},
			{
				id: "44444444-4444-4444-4444-444444444444",
				body: LONG_BODY,
				channel: "IN_CONTEXT",
				outcome: "DELIVERED",
				recipient: ada,
				composedAt: "2026-07-28T13:41:00Z",
				deliveredAt: "2026-07-28T13:41:30Z",
				replaces: "99999999-3333-3333-3333-333333333333",
				from: ["66666666-6666-6666-6666-666666666666", "77777777-7777-7777-7777-777777777777"],
			},
			{
				id: "99999999-3333-3333-3333-333333333333",
				body: "The lookup collapses two different failures into one 404. Worth splitting before this merges.",
				channel: "IN_CONTEXT",
				outcome: "SUPERSEDED",
				recipient: ada,
				composedAt: "2026-07-28T09:12:00Z",
				deliveredAt: "2026-07-28T09:12:20Z",
				from: ["66666666-6666-6666-6666-666666666666"],
			},
			{
				id: "aaaaaaaa-3333-3333-3333-333333333333",
				body: "The description could say what was slow before the cache went in.",
				channel: "IN_CONTEXT",
				outcome: "SUPPRESSED",
				withheldFor: "COMPOSER_DEDUPED",
				recipient: ada,
				composedAt: "2026-07-28T13:41:00Z",
				from: ["88888888-8888-8888-8888-888888888888"],
			},
		],
	},
	{
		id: "22222222-2222-2222-2222-222222222222",
		work: gitlabMergeRequest,
		status: "COMPLETED",
		startedAt: "2026-07-27T09:10:00Z",
		developer: alan,
		// A confirmed campaign over work that already existed, so nothing from it is spoken aloud.
		origin: "BACKFILL",
		observations: [
			{
				id: "bbbbbbbb-2222-2222-2222-222222222222",
				summary: "Invoice numbering leaks the ledger's table name into the public API",
				evidenceRationale:
					"The response field is called ledgerSeqNo, which is the column the number is stored in. Callers outside billing have to learn the storage layout to read an invoice, and the day the ledger is replaced the field is either wrong or frozen.",
				practiceSlug: "product-language",
				group: "architecture",
				assessmentStatus: "ASSESSED",
				presence: "PRESENT",
				assessment: "BAD",
				severity: "CRITICAL",
				claimCurrentness: "STALE",
				observedAt: "2026-07-27T09:15:00Z",
				evidence: [
					diff(
						"src/main/java/platform/billing/api/InvoiceResponse.java",
						22,
						22,
						"private Long ledgerSeqNo;",
					),
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
				evidenceRationale:
					"Everything in the merge request sits under the billing domain package. There is no request handler in the diff, so this practice has nothing to look at here.",
				practiceSlug: "thin-controllers",
				group: "code-quality",
				assessmentStatus: "NOT_APPLICABLE",
				presence: undefined,
				observedAt: "2026-07-27T09:14:00Z",
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
				channel: "IN_CONTEXT",
				outcome: "SUPPRESSED",
				withheldFor: "BACKFILL_QUIET",
				recipient: alan,
				composedAt: "2026-07-27T09:20:00Z",
				from: ["bbbbbbbb-2222-2222-2222-222222222222"],
			},
		],
	},
	{
		id: "33333333-3333-3333-3333-333333333333",
		work: slackConversation,
		status: "COMPLETED",
		startedAt: "2026-07-26T16:00:00Z",
		developer: grace,
		origin: "MANUAL",
		observations: [
			{
				id: "eeeeeeee-3333-3333-3333-333333333333",
				summary: "The thread ends without naming what was chosen",
				evidenceRationale:
					"Four people weighed two rollback strategies over eleven messages and the last one is a thumbs-up. Nothing in the thread states which strategy won, so a reader arriving tomorrow cannot tell agreement from the end of the working day.",
				practiceSlug: "decisions-are-written-down",
				group: "collaboration",
				assessmentStatus: "ASSESSED",
				presence: "ABSENT",
				assessment: "BAD",
				severity: "MINOR",
				claimCurrentness: "UNVERIFIABLE",
				observedAt: "2026-07-26T16:02:00Z",
				evidence: [
					cited(
						"slack.conversation.thread",
						"Message from Grace Hopper, 16:41",
						"either works honestly, whatever the on-call prefers",
						11,
					),
					cited(
						"hephaestus.observation-history",
						"Earlier observation on this practice",
						"An earlier thread in the same channel also closed without a written decision.",
					),
				],
			},
			{
				id: "ffffffff-3333-3333-3333-333333333333",
				summary: "The rollback is described in the words the on-call would use",
				evidenceRationale:
					"Every message names the customer-visible effect — prices reverting, invoices reissuing — rather than the tables involved. Somebody paged at two in the morning could act on this thread without opening the schema.",
				practiceSlug: "product-language",
				group: "architecture",
				assessmentStatus: "ASSESSED",
				presence: "PRESENT",
				assessment: "GOOD",
				observedAt: "2026-07-26T16:01:00Z",
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
				channel: "IN_CHAT",
				outcome: "PREPARED",
				recipient: grace,
				composedAt: "2026-07-26T16:05:00Z",
				from: ["eeeeeeee-3333-3333-3333-333333333333"],
			},
			{
				id: "22222222-4444-4444-4444-444444444444",
				body: "You described the rollback in terms of what a customer would see rather than which tables move. That is the version somebody paged at 2am can act on.",
				channel: "IN_CHAT",
				outcome: "DELIVERED",
				recipient: grace,
				composedAt: "2026-07-26T16:06:00Z",
				deliveredAt: "2026-07-26T18:40:00Z",
				from: ["ffffffff-3333-3333-3333-333333333333"],
			},
		],
	},
	{
		id: "44444444-4444-4444-4444-444444444444",
		work: outlineDocument,
		status: "COMPLETED",
		startedAt: "2026-07-25T11:25:00Z",
		developer: barbara,
		observations: [
			{
				id: "11111111-5555-5555-5555-555555555555",
				summary: "The runbook opens with the one step that cannot be undone",
				evidenceRationale:
					"Step one puts the workspace into maintenance before anything is restored, and the page says so in the first line rather than in a note at the bottom. A reader following the page top to bottom does the irreversible thing at the point where it is still safe.",
				practiceSlug: "the-change-explains-itself",
				group: "documentation",
				assessmentStatus: "ASSESSED",
				presence: "PRESENT",
				assessment: "GOOD",
				observedAt: "2026-07-25T11:30:00Z",
				evidence: [
					cited(
						"docs.document.core",
						"Runbook: restoring a workspace from backup",
						"Before anything else: put the workspace into maintenance. Restores started while writes are landing cannot be replayed.",
						3,
					),
				],
			},
		],
		feedback: [
			{
				id: "33333333-5555-5555-5555-555555555555",
				body: "The runbook puts the irreversible step first and says why, which is the order somebody under pressure will actually follow.",
				channel: "IN_CONTEXT",
				outcome: "FAILED",
				recipient: barbara,
				composedAt: "2026-07-25T11:32:00Z",
				from: ["11111111-5555-5555-5555-555555555555"],
			},
		],
	},
	{
		id: "55555555-6666-6666-6666-666666666666",
		work: webhookRetryPullRequest,
		status: "COMPLETED",
		startedAt: "2026-07-29T08:05:00Z",
		developer: katherine,
		observations: [
			{
				id: "44444444-6666-6666-6666-666666666666",
				summary: "A dropped delivery is logged at debug and never counted",
				evidenceRationale:
					"When the backoff gives up, the handler writes a debug line and returns. Nothing increments a counter and nothing reaches the dead-letter subject, so a provider outage looks identical to a quiet afternoon on every dashboard the team has.",
				practiceSlug: "errors-carry-context",
				group: "code-quality",
				assessmentStatus: "ASSESSED",
				presence: "PRESENT",
				assessment: "BAD",
				severity: "MAJOR",
				observedAt: "2026-07-29T08:12:00Z",
				evidence: [
					diff(
						"server/application/src/main/java/de/tum/cit/aet/hephaestus/integration/core/webhook/WebhookRetryPolicy.java",
						74,
						75,
						'log.debug("giving up on {} after {} attempts", subject, attempts);\nreturn;',
					),
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
				evidenceRationale:
					"Each of the four new tests is named for the behaviour it fixes in place — that the delay doubles, that it stops at the ceiling, that a success resets it, that a 4xx is not retried. A red build points straight at which of the four rules broke.",
				practiceSlug: "tests-name-the-behaviour",
				group: "testing",
				assessmentStatus: "ASSESSED",
				presence: "PRESENT",
				assessment: "GOOD",
				observedAt: "2026-07-29T08:11:00Z",
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
				evidenceRationale:
					"The class, the metric and the log lines all say outbox. The property is hephaestus.webhook.retry-buffer.*, so an operator reading a dashboard and an operator editing configuration are looking for two different words for one thing.",
				practiceSlug: "product-language",
				group: "architecture",
				assessmentStatus: "ASSESSED",
				presence: "PRESENT",
				assessment: "BAD",
				severity: "MINOR",
				observedAt: "2026-07-29T08:10:00Z",
				evidence: [
					diff(
						"server/application/src/main/resources/application.yml",
						118,
						118,
						"retry-buffer:\n  capacity: 5000",
					),
				],
			},
		],
		feedback: [
			{
				id: "77777777-6666-6666-6666-666666666666",
				body: "Giving up on a delivery is the one moment worth counting. A debug line means a provider outage and a quiet afternoon look the same on every dashboard you have.",
				channel: "IN_CONTEXT",
				outcome: "SUPPRESSED",
				withheldFor: "ARTIFACT_MERGED",
				recipient: katherine,
				composedAt: "2026-07-29T08:20:00Z",
				from: ["44444444-6666-6666-6666-666666666666"],
			},
			{
				id: "88888888-6666-6666-6666-666666666666",
				body: "Each retry test is named for the rule it pins down, so a red build points at which of the four broke.",
				channel: "IN_CONTEXT",
				outcome: "SUPPRESSED",
				withheldFor: "RECIPIENT_OPTED_OUT",
				recipient: katherine,
				composedAt: "2026-07-29T08:19:00Z",
				from: ["55555555-7777-7777-7777-777777777777"],
			},
			{
				id: "99999999-6666-6666-6666-666666666666",
				body: "The dashboard says outbox and the config says retry-buffer. Picking one and using it in both places would save the next operator a search.",
				channel: "IN_CONTEXT",
				outcome: "DELIVERED",
				recipient: katherine,
				composedAt: "2026-07-29T08:18:00Z",
				deliveredAt: "2026-07-29T08:18:40Z",
				anchoredAt: {
					path: "server/application/src/main/resources/application.yml",
					startLine: 118,
					endLine: 120,
				},
				from: ["66666666-7777-7777-7777-777777777777"],
			},
		],
	},
	{
		id: "aaaaaaaa-8888-8888-8888-888888888888",
		work: leagueColumnsPullRequest,
		status: "RUNNING",
		startedAt: "2026-07-29T09:40:00Z",
		developer: grace,
		observations: [],
		feedback: [],
	},
	{
		id: "bbbbbbbb-8888-8888-8888-888888888888",
		work: invoiceBackfillMergeRequest,
		status: "FAILED",
		startedAt: "2026-07-24T15:02:00Z",
		developer: alan,
		observations: [],
		feedback: [],
	},
];

// ---------------------------------------------------------------------------------------------
// Derivations
// ---------------------------------------------------------------------------------------------

/** The cut the server applies to `bodyPreview`, mirrored here so `bodyTruncated` cannot claim
 * something the preview beside it contradicts. */
const BODY_PREVIEW_LENGTH = 320;

function preview(body: string | undefined) {
	return {
		bodyPreview: body?.slice(0, BODY_PREVIEW_LENGTH),
		bodyTruncated: (body?.length ?? 0) > BODY_PREVIEW_LENGTH,
	};
}

const allRuns = REVIEW_FIXTURE;
const allObservationSpecs = allRuns.flatMap((run) =>
	run.observations.map((observation) => ({ run, observation })),
);
const allFeedbackSpecs = allRuns.flatMap((run) => run.feedback.map((item) => ({ run, item })));

/** The feedback drawing on one observation, newest first, as the detail surfaces list it. */
function feedbackFor(observationId: string) {
	return allFeedbackSpecs
		.filter(({ item }) => item.from.includes(observationId))
		.sort((a, b) => b.item.composedAt.localeCompare(a.item.composedAt));
}

type Disposition = "delivered" | "failed" | "prepared" | "superseded" | "suppressed";

const DISPOSITION_OF: Record<ReviewFeedback["deliveryState"], Disposition> = {
	AWAITING_APPROVAL: "prepared",
	DELIVERED: "delivered",
	DISCARDED: "suppressed",
	FAILED: "failed",
	PARTIALLY_DELIVERED: "prepared",
	PARTIALLY_FAILED: "failed",
	PREPARED: "prepared",
	SUPERSEDED: "superseded",
	SUPPRESSED: "suppressed",
};

function disposition(observationId: string): Record<Disposition, number> {
	const counts: Record<Disposition, number> = {
		delivered: 0,
		failed: 0,
		prepared: 0,
		superseded: 0,
		suppressed: 0,
	};
	for (const { item } of feedbackFor(observationId)) {
		counts[DISPOSITION_OF[item.outcome]] += 1;
	}
	return counts;
}

function toObservation(run: RunSpec, spec: ObservationSpec): ReviewObservation {
	const practice = workspacePractices.find((entry) => entry.slug === spec.practiceSlug);
	if (!practice) throw new Error(`No practice named ${spec.practiceSlug} in the fixture catalogue`);
	return {
		id: spec.id,
		agentJobId: run.id,
		artifact: run.work,
		group: group(spec.group),
		assessment: spec.assessment,
		claimCurrentness: spec.claimCurrentness ?? "CURRENT",
		feedbackDisposition: disposition(spec.id),
		observedAt: new Date(spec.observedAt),
		origin: run.origin ?? "LIVE",
		practiceName: practice.name,
		practiceSlug: practice.slug,
		assessmentStatus: spec.assessmentStatus,
		presence: spec.presence,
		severity: spec.severity,
		subject: run.developer,
		summary: spec.summary,
	};
}

function toFeedback(run: RunSpec, spec: FeedbackSpec): ReviewFeedback {
	return {
		id: spec.id,
		agentJobId: run.id,
		artifact: run.work,
		...preview(spec.body),
		channel: spec.channel,
		createdAt: new Date(spec.composedAt),
		deliveredAt: spec.deliveredAt ? new Date(spec.deliveredAt) : undefined,
		deliveryState: spec.outcome,
		observationCount: spec.from.length,
		recipient: spec.recipient,
		replacesId: spec.replaces,
		subject: run.developer,
		suppressionReason: spec.withheldFor,
	};
}

/** Newest first, as the list endpoint returns them. */
export const reviewObservations: ReviewObservation[] = allObservationSpecs
	.map(({ run, observation }) => toObservation(run, observation))
	.sort((a, b) => b.observedAt.getTime() - a.observedAt.getTime());

/** Newest first, as the list endpoint returns them. */
export const reviewFeedback: ReviewFeedback[] = allFeedbackSpecs
	.map(({ run, item }) => toFeedback(run, item))
	.sort((a, b) => b.createdAt.getTime() - a.createdAt.getTime());

/** Both tallies counted from the records they summarise, so a run card cannot claim a number the
 * list under it contradicts. */
export const reviewRuns: ReviewRunSummary[] = allRuns
	.map((run) => ({
		id: run.id,
		status: run.status,
		target: run.work,
		createdAt: new Date(run.startedAt),
		observations: {
			strengths: run.observations.filter((o) => o.assessment === "GOOD").length,
			problems: run.observations.filter((o) => o.assessment === "BAD").length,
			notApplicable: run.observations.filter((o) => o.assessmentStatus === "NOT_APPLICABLE").length,
			undetermined: run.observations.filter((o) => o.assessmentStatus === "UNDETERMINED").length,
		},
		feedback: {
			delivered: run.feedback.filter((f) => f.outcome === "DELIVERED").length,
			failed: run.feedback.filter((f) => f.outcome === "FAILED").length,
			prepared: run.feedback.filter((f) => f.outcome === "PREPARED").length,
			superseded: run.feedback.filter((f) => f.outcome === "SUPERSEDED").length,
			suppressed: run.feedback.filter((f) => f.outcome === "SUPPRESSED").length,
		},
	}))
	.sort((a, b) => b.createdAt.getTime() - a.createdAt.getTime());

const JOB_TYPE_BY_ARTIFACT: Record<string, AgentJob["jobType"]> = {
	"scm.pull_request": "PULL_REQUEST_REVIEW",
	"scm.issue": "ISSUE_REVIEW",
	"chat.conversation_thread": "CONVERSATION_REVIEW",
	"docs.document": "DOCUMENT_REVIEW",
};

/**
 * The job record behind one review, derived from the same spec as its rows. A review detail page
 * takes its heading from the *job's* target and its rows from the review endpoints, so a job served
 * from an unrelated fixture makes the header name one pull request and every row underneath another.
 */
export function reviewJob(runId: string): AgentJob {
	const run = allRuns.find((entry) => entry.id === runId);
	if (!run) throw new Error(`No review ${runId} in the fixture`);
	const started = new Date(run.startedAt);
	const finished = run.status === "RUNNING" ? undefined : new Date(started.getTime() + 5 * 60_000);
	const delivered = run.feedback.some(
		(item) => item.outcome === "DELIVERED" && item.channel === "IN_CONTEXT",
	);
	return {
		id: run.id,
		jobType: JOB_TYPE_BY_ARTIFACT[run.work.type] ?? "PULL_REQUEST_REVIEW",
		reviewOutcome: "REVIEWED",
		target: run.work,
		status: run.status,
		model: "gpt-5.4-mini",
		configSnapshot: { name: "Default reviewer", llmProvider: "OPENAI" },
		createdAt: started,
		availableAt: started,
		startedAt: started,
		completedAt: finished,
		deliveryStatus: run.status !== "COMPLETED" ? undefined : delivered ? "DELIVERED" : "PENDING",
		llmModel: "openai/gpt-oss-120b",
		llmTotalInputTokens: 24_000,
		llmTotalOutputTokens: 914,
		llmTotalReasoningTokens: 120,
		llmTotalCalls: 7,
		retryCount: 0,
		exitCode: run.status === "FAILED" ? 1 : 0,
		errorMessage:
			run.status === "FAILED"
				? "Cannot compute diff: all resolution strategies failed for commit 27f4e88c."
				: undefined,
	};
}

export function observationDetail(observationId: string): ReviewObservationDetail {
	const found = allObservationSpecs.find(({ observation }) => observation.id === observationId);
	if (!found) throw new Error(`No observation ${observationId} in the fixture`);
	const { run, observation } = found;
	// The detail replaces the tally with the feedback records the tally was counting.
	const { feedbackDisposition: _tally, ...shared } = toObservation(run, observation);
	return {
		...shared,
		evidence: observation.evidence ? { citations: observation.evidence } : undefined,
		feedback: feedbackFor(observationId).map(({ item }) => ({
			feedbackId: item.id,
			agentJobId: run.id,
			channel: item.channel,
			createdAt: new Date(item.composedAt),
			deliveredAt: item.deliveredAt ? new Date(item.deliveredAt) : undefined,
			deliveryState: item.outcome,
			role: item.from[0] === observationId ? "PRIMARY" : "SUPPORTING",
			suppressionReason: item.withheldFor,
		})),
		evidenceRationale: observation.evidenceRationale,
	};
}

export function feedbackDetail(feedbackId: string): ReviewFeedbackDetail {
	const found = allFeedbackSpecs.find(({ item }) => item.id === feedbackId);
	if (!found) throw new Error(`No feedback ${feedbackId} in the fixture`);
	const { run, item } = found;
	return {
		id: item.id,
		agentJobId: run.id,
		artifact: run.work,
		body: item.body,
		channel: item.channel,
		createdAt: new Date(item.composedAt),
		deliveredAt: item.deliveredAt ? new Date(item.deliveredAt) : undefined,
		deliveryState: item.outcome,
		observations: item.from.map((observationId, ordinal) => {
			const source = observationDetail(observationId);
			return {
				observationId,
				group: source.group,
				assessment: source.assessment,
				claimCurrentness: source.claimCurrentness,
				observedAt: source.observedAt,
				ordinal,
				practiceName: source.practiceName,
				practiceSlug: source.practiceSlug,
				assessmentStatus: source.assessmentStatus,
				presence: source.presence,
				role: ordinal === 0 ? "PRIMARY" : "SUPPORTING",
				severity: source.severity,
				summary: source.summary,
			};
		}),
		placements:
			item.outcome !== "DELIVERED" || item.channel !== "IN_CONTEXT"
				? []
				: item.anchoredAt
					? [
							{
								id: `${item.id}-inline`,
								placementType: "INLINE",
								anchorKind: "RANGE",
								anchorSide: "NEW",
								anchorPath: item.anchoredAt.path,
								anchorStartLine: item.anchoredAt.startLine,
								anchorEndLine: item.anchoredAt.endLine,
								postedCommentRef: "2481944",
							},
						]
					: [{ id: `${item.id}-summary`, placementType: "SUMMARY", postedCommentRef: "2481933" }],
		proposedPlacements:
			item.outcome === "AWAITING_APPROVAL" && item.channel === "IN_CONTEXT"
				? [
						{ type: "SUMMARY", body: item.body ?? "" },
						{
							type: "INLINE",
							body: "This fallback catches every exception and hides whether the retry is safe. Catch the expected transport error and let programming errors surface.",
							path: "src/main/java/example/RetryService.java",
							startLine: 48,
						},
						{
							type: "INLINE",
							body: "The new branch changes behavior but the description does not explain why. Add the reason so a future maintainer can distinguish intent from accident.",
							path: "src/main/java/example/ReviewHandler.java",
							startLine: 91,
							endLine: 94,
						},
					]
				: [],
		recipient: item.recipient,
		replacesId: item.replaces,
		reviewedRevision: item.outcome === "AWAITING_APPROVAL" ? "27f4e88c9f5a" : undefined,
		subject: run.developer,
		suppressionReason: item.withheldFor,
		deliveryPolicy: [],
	};
}

/** The withheld piece of feedback the detail stories open by default. */
export const reviewFeedbackDetail: ReviewFeedbackDetail = feedbackDetail(
	"dddddddd-2222-2222-2222-222222222222",
);

/** The long, multi-observation note. */
export const longFeedbackDetail: ReviewFeedbackDetail = feedbackDetail(
	"44444444-4444-4444-4444-444444444444",
);

/** An observation whose evidence spans a diff, a repository file and a human review thread. */
export const reviewObservationDetail: ReviewObservationDetail = observationDetail(
	"66666666-6666-6666-6666-666666666666",
);

// ---------------------------------------------------------------------------------------------
// A page that does not fit on one page
// ---------------------------------------------------------------------------------------------

function cycled<T>(specs: readonly T[], index: number): T {
	const spec = specs[index % specs.length];
	if (!spec) throw new Error("A page cannot be filled from an empty list of specs.");
	return spec;
}

/**
 * The fixture's observations restated across as many days as it takes to fill `count` rows. Cycling
 * the real specs keeps every row a record the server could have produced, unlike a generated
 * `Observation #17`.
 */
export function manyObservations(count: number): ReviewObservation[] {
	const base = reviewObservations;
	return Array.from({ length: count }, (_, index) => {
		const source = cycled(base, index);
		const cycle = Math.floor(index / base.length);
		if (cycle === 0) return source;
		return {
			...source,
			id: `${source.id.slice(0, -2)}${(10 + cycle).toString(36)}`,
			observedAt: new Date(source.observedAt.getTime() - cycle * 86_400_000),
		};
	});
}

/**
 * A workspace large enough that the person facet, which filters one fetched page in the browser
 * because the endpoint takes no name, cannot reach everybody.
 */
export function manyMembers(count: number): WorkspaceMembership[] {
	return Array.from({ length: count }, (_, index) => {
		const source = cycled(workspaceMembers, index);
		const cycle = Math.floor(index / workspaceMembers.length);
		if (cycle === 0) return source;
		return {
			...source,
			userId: (source.userId ?? 0) + cycle * 100,
			userLogin: `${source.userLogin}-${cycle}`,
			userName: `${source.userName} ${cycle}`,
		};
	});
}

export function manyFeedback(count: number): ReviewFeedback[] {
	const base = reviewFeedback;
	return Array.from({ length: count }, (_, index) => {
		const source = cycled(base, index);
		const cycle = Math.floor(index / base.length);
		if (cycle === 0) return source;
		return {
			...source,
			id: `${source.id.slice(0, -2)}${(10 + cycle).toString(36)}`,
			createdAt: new Date(source.createdAt.getTime() - cycle * 86_400_000),
		};
	});
}
