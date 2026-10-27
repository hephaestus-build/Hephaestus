import { HttpResponse, http } from "msw";
import { z } from "zod";

import type {
	ItemRef,
	ResultMark,
	ReviewEvidenceOutcome,
	ReviewFeedback,
	ReviewProblem,
	ReviewResult,
	ReviewSummary,
} from "@/api/types.gen";
import { itemTrace } from "@/components/practice-trace/fixtures";
import { hasText } from "@/lib/text";
import { mockPracticeDefinitionOptions } from "@/mocks/fixtures/practice";
import { ownMembership, workspaceListItem } from "@/mocks/fixtures/workspaces";

import {
	busyReviewQueue,
	disagreedResultIds,
	feedbackDecisions,
	feedbackDetail,
	feedbackIdsFrom,
	feedbackPracticeSlugs,
	itemEventsOf,
	itemTraceOf,
	locationCounts,
	mergedPullRequestFacts,
	openIssueFacts,
	practiceCounts,
	practiceGroups,
	practiceGroupSlug,
	practiceReviewOverview,
	practiceReviewTiming,
	practiceVersions,
	problemDetail,
	reasonCounts,
	resultDetail,
	resultMarks,
	reviewAccounts,
	reviewDetail,
	reviewDeveloperOf,
	reviewFeedback,
	reviewFeedbackChecksOf,
	reviewPracticeScripts,
	reviewProblems,
	reviewResults,
	reviews,
	reviewUsageTwoAttempts,
	workspaceMembers,
	workspacePractices,
} from "@/components/admin/practice-reviews/fixtures";
import {
	type LineageKind,
	reviewLineage,
} from "@/components/admin/practice-reviews/lineage-fixtures";

const PAGE_SIZE = 25;

// Match repeated and comma-separated values supported by the generated client.
function values(url: URL, name: string): string[] {
	return url.searchParams
		.getAll(name)
		.flatMap((value) => value.split(","))
		.filter(Boolean);
}

function single(url: URL, name: string): string | undefined {
	return url.searchParams.get(name) ?? undefined;
}

function matches(selected: string[], actual: string | null | undefined): boolean {
	return selected.length === 0 || (hasText(actual) && selected.includes(actual));
}

function matchesAny(selected: string[], actual: readonly (string | null)[]): boolean {
	return (
		selected.length === 0 || actual.some((value) => hasText(value) && selected.includes(value))
	);
}

function flag(url: URL, name: string, actual: boolean): boolean {
	const asked = single(url, name);
	return asked === undefined || asked === String(actual);
}

function equals(url: URL, name: string, actual: string | null | undefined): boolean {
	const asked = single(url, name);
	return !hasText(asked) || asked === actual;
}

function withinScope(url: URL, row: { reviewId: string; item: ItemRef | null }) {
	return (
		equals(url, "reviewId", row.reviewId) &&
		equals(url, "itemId", row.item?.id) &&
		equals(url, "itemKind", row.item?.kind)
	);
}

// Date filters include from and exclude to.
function withinDates(url: URL, at: Date) {
	const from = single(url, "from");
	const to = single(url, "to");
	if (hasText(from) && at < new Date(from)) {
		return false;
	}
	if (hasText(to) && at >= new Date(to)) {
		return false;
	}
	return true;
}

function pageOf<T>(rows: readonly T[], url: URL) {
	const number = Number(single(url, "page") ?? 0);
	const size = Number(single(url, "size") ?? PAGE_SIZE);
	return {
		content: rows.slice(number * size, number * size + size),
		number,
		size,
		totalElements: rows.length,
		totalPages: Math.max(1, Math.ceil(rows.length / size)),
	};
}

function springPage(rows: readonly unknown[], url: URL) {
	const page = pageOf(rows, url);
	return HttpResponse.json({
		...page,
		numberOfElements: page.content.length,
		first: page.number === 0,
		last: page.number >= page.totalPages - 1,
		empty: page.content.length === 0,
	});
}

function inPracticeScope(url: URL, practiceSlugs: readonly string[]) {
	return (
		matchesAny(values(url, "practiceSlug"), practiceSlugs) &&
		matchesAny(values(url, "groupSlug"), practiceSlugs.map(practiceGroupSlug))
	);
}

function filterResults(rows: readonly ReviewResult[], url: URL) {
	const disagreed = disagreedResultIds();
	return rows.filter(
		(row) =>
			withinScope(url, row) &&
			withinDates(url, row.recordedAt) &&
			inPracticeScope(url, [row.practiceSlug]) &&
			equals(url, "developerId", row.developer?.accountId) &&
			matches(values(url, "outcome"), row.outcome) &&
			matches(values(url, "severity"), row.severity) &&
			equals(url, "currentness", row.currentness) &&
			flag(url, "markedIncorrect", row.markedIncorrectAt !== null) &&
			flag(url, "accepted", row.acceptedAt !== null) &&
			flag(url, "disagreed", disagreed.has(row.id)),
	);
}

function filterProblems(rows: readonly ReviewProblem[], url: URL) {
	return rows.filter(
		(row) =>
			withinScope(url, row) &&
			equals(url, "resultId", row.resultId) &&
			equals(url, "path", row.path) &&
			withinDates(url, row.recordedAt) &&
			inPracticeScope(url, [row.practiceSlug]) &&
			equals(url, "developerId", row.developer?.accountId) &&
			matches(values(url, "severity"), row.severity) &&
			equals(url, "currentness", row.currentness) &&
			flag(url, "markedIncorrect", row.markedIncorrectAt !== null) &&
			flag(url, "accepted", row.acceptedAt !== null) &&
			flag(url, "disagreed", row.disagreedAt !== null) &&
			(single(url, "openOnly") !== "true" || row.markedIncorrectAt === null),
	);
}

function filterFeedback(rows: readonly ReviewFeedback[], url: URL) {
	const resultId = single(url, "resultId");
	const fromResult = hasText(resultId) ? feedbackIdsFrom(resultId) : undefined;
	return rows.filter(
		(row) =>
			withinScope(url, row) &&
			(fromResult === undefined || fromResult.has(row.id)) &&
			withinDates(url, row.createdAt) &&
			inPracticeScope(url, feedbackPracticeSlugs(row.id)) &&
			equals(url, "developerId", row.developer.accountId) &&
			matches(values(url, "destination"), row.destination) &&
			matches(values(url, "state"), row.state) &&
			equals(url, "location", row.location) &&
			matches(values(url, "stopReason"), row.stopReason) &&
			flag(url, "withdrawn", row.withdrawn) &&
			flag(url, "disagreed", row.disagreedAt !== null),
	);
}

function filterReviews(rows: readonly ReviewSummary[], url: URL) {
	return rows.filter(
		(row) =>
			withinScope(url, { reviewId: row.id, item: row.item }) &&
			matches(values(url, "status"), row.status) &&
			equals(url, "kind", row.kind) &&
			inPracticeScope(
				url,
				row.practices.map((practice) => practice.practiceSlug),
			) &&
			equals(url, "developerId", reviewDeveloperOf(row.id)?.accountId) &&
			withinDates(url, row.createdAt),
	);
}

/**
 * The server's `ACTIONABILITY` ordering, applied rather than merely accepted: a mock that checked
 * only that the parameter was sent would pass a story whose control sets the wrong value, or whose
 * screen re-orders the answer on the way in.
 */
const ACTIONABILITY_RANK: Record<string, number> = { CRITICAL: 0, MAJOR: 1, MINOR: 2, TRIVIAL: 3 };

function actionability(row: ReviewResult): number {
	switch (row.outcome) {
		case "NOT_MET": {
			return ACTIONABILITY_RANK[row.severity ?? "TRIVIAL"] ?? 4;
		}
		case "MET": {
			return 5;
		}
		case "DOES_NOT_APPLY":
		case "COULD_NOT_DETERMINE": {
			return 6;
		}
	}
}

/** The fixtures come newest first, as the server sends them; `OLDEST` turns them round. */
function byTime<T>(rows: readonly T[], url: URL): readonly T[] {
	return single(url, "sort") === "OLDEST" ? [...rows].reverse() : rows;
}

function sortResults(rows: readonly ReviewResult[], url: URL) {
	if (single(url, "sort") !== "ACTIONABILITY") {
		return byTime(rows, url);
	}
	// Ties are newest first, as on the server.
	return [...rows].sort(
		(a, b) =>
			actionability(a) - actionability(b) ||
			new Date(b.recordedAt).getTime() - new Date(a.recordedAt).getTime(),
	);
}

function reviewedItems(): ItemRef[] {
	const byId = new Map<string, ItemRef>();
	for (const review of reviews) {
		byId.set(review.item.id, review.item);
	}
	return [...byId.values()];
}

function itemNamed(itemId: string): ItemRef {
	return reviewedItems().find((item) => item.id === itemId) ?? { ...itemTrace.item, id: itemId };
}

function reviewOfItem(itemId: string): ReviewSummary | undefined {
	return reviews.find((review) => review.item.id === itemId);
}

const LINEAGE_KINDS = [
	"ITEM",
	"REVIEW",
	"RESULT",
	"PROBLEM",
	"FEEDBACK",
] as const satisfies readonly LineageKind[];
const lineageKindSchema = z.enum(LINEAGE_KINDS);

const LINEAGE_ID_PARAM: Record<LineageKind, string> = {
	ITEM: "itemId",
	REVIEW: "reviewId",
	RESULT: "resultId",
	PROBLEM: "problemId",
	FEEDBACK: "feedbackId",
};

const createMarkSchema = z.object({
	kind: z.enum(["INCORRECT", "ACCEPTED"]),
	reason: z.string().min(1),
});

const removeMarkSchema = z.object({ reason: z.string().min(1) });

const createDecisionSchema = z.object({
	action: z.enum(["APPROVE", "REJECT", "WITHDRAW", "RESTORE"]),
	reasonCode: z
		.enum([
			"INCORRECT",
			"MISSING_CONTEXT",
			"UNHELPFUL",
			"DUPLICATE",
			"INAPPROPRIATE_PLACEMENT",
			"OTHER",
		])
		.optional(),
	note: z.string().optional(),
	contentDigest: z.string().optional(),
});

const evidenceOutcomes: ReviewEvidenceOutcome[] = practiceCounts.map((counts) => ({
	practiceSlug: counts.practiceSlug,
	practiceName: counts.practiceName,
	outcomes: counts.results,
	withheld: counts.feedback.withheld,
	notReviewed: 0,
}));

export interface ReviewMockOptions {
	results?: ReviewResult[];
	feedback?: ReviewFeedback[];
	requireResultSort?: string;
	role?: Parameters<typeof ownMembership>[0];
}

/** Place overrides before these handlers; MSW uses the first match. */
export function reviewHandlers({
	results,
	feedback,
	requireResultSort,
	role = "ADMIN",
}: ReviewMockOptions = {}) {
	const resultRows = results ?? reviewResults;
	const feedbackRows = feedback ?? reviewFeedback;
	return [
		// The parent gate reads the member list; both workspaces are used by these review fixtures.
		http.get("*/workspaces", () =>
			HttpResponse.json([
				workspaceListItem("acme", { practicesEnabled: true }),
				workspaceListItem("other", { id: 2, practicesEnabled: true }),
			]),
		),
		// One fixed range whatever `from` and `zone` asked for: the overview's counts are the server's
		// arithmetic, and a story reads them rather than proving them.
		http.get("*/workspaces/:workspaceSlug/practices/reviews/overview", () =>
			HttpResponse.json(practiceReviewOverview),
		),
		http.get("*/workspaces/:workspaceSlug/practices/reviews/timing", () =>
			HttpResponse.json(practiceReviewTiming),
		),
		http.get("*/workspaces/:workspaceSlug/practices/reviews/queue", () =>
			HttpResponse.json(busyReviewQueue),
		),
		http.get("*/workspaces/:workspaceSlug/practices/reviews/evidence-outcomes", () =>
			HttpResponse.json(evidenceOutcomes),
		),
		...resultHandlers(resultRows, requireResultSort),
		http.get("*/workspaces/:workspaceSlug/practices/reviews/problems", ({ request }) =>
			springPage(
				byTime(filterProblems(reviewProblems, new URL(request.url)), new URL(request.url)),
				new URL(request.url),
			),
		),
		http.get("*/workspaces/:workspaceSlug/practices/reviews/problems/:problemId", ({ params }) =>
			HttpResponse.json(problemDetail(String(params.problemId))),
		),
		...feedbackHandlers(feedbackRows),
		http.get("*/workspaces/:workspaceSlug/practices/reviews/lineage", ({ request }) => {
			const url = new URL(request.url);
			const kind = lineageKindSchema.safeParse(single(url, "kind"));
			if (!kind.success) {
				return HttpResponse.json({ detail: "Unknown lineage kind" }, { status: 400 });
			}
			const id = single(url, LINEAGE_ID_PARAM[kind.data]);
			if (!hasText(id)) {
				return HttpResponse.json(
					{ detail: "The selected id must match the lineage kind." },
					{ status: 400 },
				);
			}
			return HttpResponse.json(reviewLineage(kind.data, id));
		}),
		...reviewDetailHandlers(),
		...itemHandlers(),
		...catalogHandlers(),
		http.get("*/workspaces/:workspaceSlug/members/me", () =>
			HttpResponse.json(ownMembership(role)),
		),
		http.get("*/workspaces/:workspaceSlug/members", ({ request }) =>
			HttpResponse.json(springPage(workspaceMembers, new URL(request.url))),
		),
	];
}

function resultHandlers(rows: readonly ReviewResult[], requireResultSort: string | undefined) {
	return [
		http.get("*/workspaces/:workspaceSlug/practices/reviews/results", ({ request }) => {
			const url = new URL(request.url);
			if (hasText(requireResultSort) && single(url, "sort") !== requireResultSort) {
				return HttpResponse.json({ detail: `Expected sort=${requireResultSort}` }, { status: 400 });
			}
			return springPage(sortResults(filterResults(rows, url), url), url);
		}),
		http.get("*/workspaces/:workspaceSlug/practices/reviews/results/:resultId", ({ params }) =>
			HttpResponse.json(resultDetail(String(params.resultId))),
		),
		http.get(
			"*/workspaces/:workspaceSlug/practices/reviews/results/:resultId/marks",
			({ params }) => HttpResponse.json(resultMarks(String(params.resultId))),
		),
		http.post(
			"*/workspaces/:workspaceSlug/practices/reviews/results/:resultId/marks",
			async ({ params, request }) => {
				const body = createMarkSchema.safeParse(await request.json());
				if (!body.success) {
					return HttpResponse.json(
						{ detail: "A mark needs a kind and a reason." },
						{ status: 400 },
					);
				}
				const resultId = String(params.resultId);
				const mark: ResultMark = {
					id: `${resultId.slice(0, -3)}b00`,
					resultId,
					kind: body.data.kind,
					reason: body.data.reason,
					createdBy: reviewAccounts.grace,
					createdAt: new Date(),
					removedBy: null,
					removedAt: null,
					removalReason: null,
				};
				return HttpResponse.json(mark, { status: 201 });
			},
		),
		http.delete(
			"*/workspaces/:workspaceSlug/practices/reviews/results/:resultId/marks/:markId",
			async ({ params, request }) => {
				const body = removeMarkSchema.safeParse(await request.json());
				const mark = resultMarks(String(params.resultId)).find(
					(entry) => entry.id === String(params.markId),
				);
				if (!body.success || mark === undefined) {
					return HttpResponse.json({ detail: "No such mark, or no reason." }, { status: 400 });
				}
				return HttpResponse.json({
					...mark,
					removedBy: reviewAccounts.grace,
					removedAt: new Date(),
					removalReason: body.data.reason,
				});
			},
		),
	];
}

/**
 * The feedback list, its counts by location and by reason, and one piece's detail, decisions and
 * retry. The counts come before the detail, whose path would take them too.
 */
export function feedbackHandlers(rows: readonly ReviewFeedback[] = reviewFeedback) {
	return [
		http.get("*/workspaces/:workspaceSlug/practices/reviews/feedback", ({ request }) => {
			const url = new URL(request.url);
			const filtered = filterFeedback(rows, url);
			// The fixture is newest first, as the endpoint's default order is.
			return springPage(byTime(filtered, url), url);
		}),
		...feedbackCountHandlers(rows),
		http.get("*/workspaces/:workspaceSlug/practices/reviews/feedback/:feedbackId", ({ params }) =>
			HttpResponse.json(feedbackDetail(String(params.feedbackId))),
		),
		http.get(
			"*/workspaces/:workspaceSlug/practices/reviews/feedback/:feedbackId/decisions",
			({ params }) => HttpResponse.json(feedbackDecisions(String(params.feedbackId))),
		),
		http.post(
			"*/workspaces/:workspaceSlug/practices/reviews/feedback/:feedbackId/decisions",
			async ({ params, request }) => {
				const body = createDecisionSchema.safeParse(await request.json());
				if (!body.success) {
					return HttpResponse.json({ detail: "Unknown decision." }, { status: 400 });
				}
				const feedbackId = String(params.feedbackId);
				return HttpResponse.json(
					{
						id: `${feedbackId.slice(0, -3)}d99`,
						feedbackId,
						action: body.data.action,
						actor: reviewAccounts.grace,
						reasonCode: body.data.reasonCode ?? null,
						note: body.data.note ?? null,
						contentDigest: body.data.contentDigest ?? null,
						decidedAt: new Date(),
					},
					{ status: 201 },
				);
			},
		),
		http.post(
			"*/workspaces/:workspaceSlug/practices/reviews/feedback/:feedbackId/retry",
			({ params }) => HttpResponse.json(feedbackDetail(String(params.feedbackId)), { status: 202 }),
		),
	];
}

/**
 * The counts the feedback list's own filter selects, by location and by reason. A test that answers
 * `feedback/:feedbackId` itself puts these before it, since that path would take them too.
 */
export function feedbackCountHandlers(rows: readonly ReviewFeedback[] = reviewFeedback) {
	return [
		http.get("*/workspaces/:workspaceSlug/practices/reviews/feedback/locations", ({ request }) =>
			HttpResponse.json(locationCounts(filterFeedback(rows, new URL(request.url)))),
		),
		http.get("*/workspaces/:workspaceSlug/practices/reviews/feedback/reasons", ({ request }) =>
			HttpResponse.json(reasonCounts(filterFeedback(rows, new URL(request.url)))),
		),
	];
}

function reviewDetailHandlers() {
	return [
		http.get("*/workspaces/:workspaceSlug/practices/reviews", ({ request }) => {
			const url = new URL(request.url);
			return springPage(byTime(filterReviews(reviews, url), url), url);
		}),
		http.get("*/workspaces/:workspaceSlug/practices/reviews/:reviewId", ({ params }) =>
			HttpResponse.json(reviewDetail(String(params.reviewId))),
		),
		http.get("*/workspaces/:workspaceSlug/practices/reviews/:reviewId/usage", () =>
			HttpResponse.json(reviewUsageTwoAttempts),
		),
		http.get("*/workspaces/:workspaceSlug/practices/reviews/:reviewId/practice-scripts", () =>
			HttpResponse.json(Object.values(reviewPracticeScripts)),
		),
		http.get(
			"*/workspaces/:workspaceSlug/practices/reviews/:reviewId/feedback-checks",
			({ params }) =>
				HttpResponse.json(reviewFeedbackChecksOf(reviewDetail(String(params.reviewId)))),
		),
		http.post("*/workspaces/:workspaceSlug/practices/reviews/:reviewId/cancel", ({ params }) => {
			const review = reviewDetail(String(params.reviewId));
			const active = review.status === "QUEUED" || review.status === "RUNNING";
			return HttpResponse.json(
				{
					...review,
					status: active ? "CANCELLED" : review.status,
					stopReason: active ? "CANCELLED_BY_ADMIN" : review.stopReason,
					activeJobCount: 0,
					queuedJobCount: 0,
				},
				{ status: active ? 202 : 200 },
			);
		}),
	];
}

function itemHandlers() {
	return [
		http.get("*/workspaces/:workspaceSlug/practices/items", ({ request }) => {
			const url = new URL(request.url);
			const kind = single(url, "itemKind");
			return springPage(
				reviewedItems().filter((item) => !hasText(kind) || item.kind === kind),
				url,
			);
		}),
		http.get("*/workspaces/:workspaceSlug/practices/items/:itemId", ({ params }) =>
			HttpResponse.json(itemNamed(String(params.itemId))),
		),
		http.get("*/workspaces/:workspaceSlug/practices/items/:itemId/trace", ({ params }) => {
			const itemId = String(params.itemId);
			const review = reviewOfItem(itemId);
			return HttpResponse.json(
				review === undefined ? { ...itemTrace, item: itemNamed(itemId) } : itemTraceOf(review),
			);
		}),
		http.get("*/workspaces/:workspaceSlug/practices/items/:itemId/facts", ({ params }) => {
			const item = itemNamed(String(params.itemId));
			if (item.kind === "scm.pull_request") {
				return HttpResponse.json({ ...mergedPullRequestFacts, item });
			}
			if (item.kind === "scm.issue") {
				return HttpResponse.json({ ...openIssueFacts, item });
			}
			return HttpResponse.json({ item, facts: [] });
		}),
		http.get(
			"*/workspaces/:workspaceSlug/practices/items/:itemId/events",
			({ params, request }) => {
				const review = reviewOfItem(String(params.itemId));
				return springPage(review === undefined ? [] : itemEventsOf(review), new URL(request.url));
			},
		),
	];
}

function catalogHandlers() {
	return [
		http.get("*/workspaces/:workspaceSlug/practices/definition-options", () =>
			HttpResponse.json(mockPracticeDefinitionOptions),
		),
		http.get("*/workspaces/:workspaceSlug/practices/:practiceSlug/versions", () =>
			HttpResponse.json(practiceVersions),
		),
		http.get("*/workspaces/:workspaceSlug/practices", () => HttpResponse.json(workspacePractices)),
		http.get("*/workspaces/:workspaceSlug/practice-groups", () =>
			HttpResponse.json(practiceGroups),
		),
	];
}
