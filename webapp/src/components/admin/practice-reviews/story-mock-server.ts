import { HttpResponse, http } from "msw";

import type { ReviewFeedback, ReviewObservation } from "@/api/types.gen";

import {
	feedbackDetail,
	observationDetail,
	practiceGroups,
	reviewFeedback,
	reviewJob,
	reviewObservations,
	reviewRuns,
	workspaceMembers,
	workspacePractices,
} from "./story-mock-data";

// These handlers filter, sort and paginate the fixture the way the server does. A mock that answers
// every URL with the same array leaves a story that clicks a facet testing nothing.

const PAGE_SIZE = 25;

/**
 * Repeated params and comma-joined params both, because which one the generated client emits is its
 * business and a mock that guesses wrong silently ignores the filter.
 */
function values(url: URL, name: string): string[] {
	return url.searchParams
		.getAll(name)
		.flatMap((value) => value.split(","))
		.filter(Boolean);
}

function single(url: URL, name: string): string | undefined {
	return url.searchParams.get(name) ?? undefined;
}

function matches(selected: string[], actual: string | undefined): boolean {
	return selected.length === 0 || (actual !== undefined && selected.includes(actual));
}

function withinScope(
	url: URL,
	row: { agentJobId: string; artifact?: { id: number; type: string } },
) {
	const agentJobId = single(url, "agentJobId");
	const artifactKind = single(url, "artifactKind");
	const artifactId = single(url, "artifactId");
	if (agentJobId && row.agentJobId !== agentJobId) return false;
	if (artifactKind && row.artifact?.type !== artifactKind) return false;
	if (artifactId && String(row.artifact?.id) !== artifactId) return false;
	return true;
}

function withinDates(url: URL, at: Date) {
	const from = single(url, "from");
	const to = single(url, "to");
	if (from && at < new Date(from)) return false;
	if (to && at >= new Date(to)) return false;
	return true;
}

function page(rows: unknown[], url: URL) {
	const number = Number(single(url, "page") ?? 0);
	const size = Number(single(url, "size") ?? PAGE_SIZE);
	return HttpResponse.json({
		content: rows.slice(number * size, number * size + size),
		page: {
			number,
			size,
			totalElements: rows.length,
			totalPages: Math.max(1, Math.ceil(rows.length / size)),
		},
	});
}

function filterObservations(rows: ReviewObservation[], url: URL) {
	const subjectUserId = single(url, "subjectUserId");
	return rows.filter(
		(row) =>
			withinScope(url, row) &&
			withinDates(url, row.observedAt) &&
			matches(values(url, "groupSlug"), row.group?.slug) &&
			matches(values(url, "practiceSlug"), row.practiceSlug) &&
			matches(values(url, "assessmentStatus"), row.assessmentStatus) &&
			matches(values(url, "presence"), row.presence) &&
			matches(values(url, "assessment"), row.assessment) &&
			matches(values(url, "severity"), row.severity) &&
			(!subjectUserId || String(row.subject?.id) === subjectUserId),
	);
}

function filterFeedback(rows: ReviewFeedback[], url: URL) {
	const recipientUserId = single(url, "recipientUserId");
	return rows.filter(
		(row) =>
			withinScope(url, row) &&
			withinDates(url, row.createdAt) &&
			matches(values(url, "deliveryState"), row.deliveryState) &&
			matches(values(url, "channel"), row.channel) &&
			matches(values(url, "suppressionReason"), row.suppressionReason) &&
			(!recipientUserId || String(row.recipient?.id) === recipientUserId),
	);
}

/**
 * The server's `ACTIONABILITY` ordering, applied rather than merely accepted: a mock that checked
 * only that the parameter was sent would pass a story whose control sets the wrong value, or whose
 * screen re-orders the answer on the way in.
 */
const ACTIONABILITY_RANK: Record<string, number> = { CRITICAL: 0, MAJOR: 1, MINOR: 2, INFO: 3 };

function actionability(row: ReviewObservation): number {
	if (row.assessmentStatus !== "ASSESSED" || !row.presence || !row.assessment) return 6;
	if ((row.presence === "PRESENT") !== (row.assessment === "GOOD"))
		return ACTIONABILITY_RANK[row.severity ?? "INFO"] ?? 4;
	return 5;
}

function sortObservations(rows: ReviewObservation[], url: URL) {
	if (single(url, "sort") !== "ACTIONABILITY") return rows;
	// Ties are newest first, as on the server.
	return [...rows].sort(
		(a, b) =>
			actionability(a) - actionability(b) ||
			new Date(b.observedAt).getTime() - new Date(a.observedAt).getTime(),
	);
}

export interface ReviewMockOptions {
	observations?: ReviewObservation[];
	feedback?: ReviewFeedback[];
	/**
	 * Answer an observation query with a 400 unless it asks for this ordering. A screen that shows
	 * only the observations most worth acting on has to request that ordering, and a mock that ignores
	 * `sort` answers a screen that forgot to ask exactly as it answers one that remembered.
	 */
	requireObservationSort?: string;
}

/**
 * Spread into a story's `msw.handlers` with the handlers that story overrides placed *before* it:
 * MSW matches the first handler that fits.
 */
export function reviewHandlers({
	observations,
	feedback,
	requireObservationSort,
}: ReviewMockOptions = {}) {
	const observationRows = observations ?? reviewObservations;
	const feedbackRows = feedback ?? reviewFeedback;
	return [
		http.get("*/workspaces/:workspaceSlug/practices/reviews/observations", ({ request }) => {
			const url = new URL(request.url);
			if (requireObservationSort && single(url, "sort") !== requireObservationSort) {
				return HttpResponse.json(
					{ detail: `Expected sort=${requireObservationSort}` },
					{ status: 400 },
				);
			}
			return page(sortObservations(filterObservations(observationRows, url), url), url);
		}),
		http.get(
			"*/workspaces/:workspaceSlug/practices/reviews/observations/:observationId",
			({ params }) => HttpResponse.json(observationDetail(String(params.observationId))),
		),
		http.get("*/workspaces/:workspaceSlug/practices/reviews/feedback", ({ request }) =>
			page(filterFeedback(feedbackRows, new URL(request.url)), new URL(request.url)),
		),
		http.get("*/workspaces/:workspaceSlug/practices/reviews/feedback/:feedbackId", ({ params }) =>
			HttpResponse.json(feedbackDetail(String(params.feedbackId))),
		),
		http.get("*/workspaces/:workspaceSlug/practices/reviews", ({ request }) => {
			const url = new URL(request.url);
			const status = single(url, "status");
			return page(
				// Both filters, intersected, exactly as the endpoint applies them. Honouring only
				// `status` here would let a story "prove" a date range that the screen never sent.
				reviewRuns.filter(
					(run) => (!status || run.status === status) && withinDates(url, run.createdAt),
				),
				url,
			);
		}),
		http.get("*/workspaces/:workspaceSlug/agents/jobs/:jobId", ({ params }) =>
			HttpResponse.json(reviewJob(String(params.jobId))),
		),
		http.get("*/workspaces/:workspaceSlug/practices", () => HttpResponse.json(workspacePractices)),
		http.get("*/workspaces/:workspaceSlug/practice-groups", () =>
			HttpResponse.json(practiceGroups),
		),
		http.get("*/workspaces/:workspaceSlug/members", () => HttpResponse.json(workspaceMembers)),
	];
}
