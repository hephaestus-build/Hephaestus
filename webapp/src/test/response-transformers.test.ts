import { HttpResponse, http } from "msw";
import { assert, describe, expect, it } from "vitest";

import { adminListAuthEvents, getReview, listReviewBatches } from "@/api/sdk.gen";
import { reviewDetailCompleted } from "@/components/admin/practice-reviews/fixtures";
import { server } from "@/mocks/server";

/**
 * SDK calls must apply response transformers so their Date values match the generated types.
 * These tests stay outside src/api because generation empties that directory.
 */
describe("generated SDK response transformers", () => {
	it("revives a required date field into a Date", async () => {
		server.use(
			http.get("*/admin/audit", () =>
				HttpResponse.json({
					content: [{ id: 1, eventType: "LOGIN", result: "SUCCESS", occurredAt: ISO }],
				}),
			),
		);

		const { data } = await adminListAuthEvents();
		const occurredAt = data?.content[0]?.occurredAt;

		// Not `instanceof` alone: a transformer that fabricated `new Date()` would satisfy that while
		// losing the instant the server sent.
		assert(occurredAt instanceof Date);
		expect(occurredAt.toISOString()).toBe(ISO);
	});

	it("revives dates nested in a page payload, where the recursion could quietly stop", async () => {
		server.use(http.get("*/workspaces/:slug/practices/review-batches", () => batchesResponse()));

		const { data } = await listReviewBatches({ path: { workspaceSlug: "acme" } });

		expect(data?.content[0]?.from).toBeInstanceOf(Date);
		expect(data?.content[0]?.createdAt).toBeInstanceOf(Date);
	});

	it("leaves a null date null rather than the epoch", async () => {
		server.use(
			http.get("*/workspaces/:slug/practices/review-batches", () => batchesResponse("RUNNING")),
		);

		const { data } = await listReviewBatches({ path: { workspaceSlug: "acme" } });

		// new Date(null) creates a real epoch date; missing timestamps must remain absent.
		expect(data?.content[0]?.finishedAt).toBeNull();
	});

	it("revives a review's completion only when the server recorded it", async () => {
		const review = reviewDetailCompleted;
		const path = { workspaceSlug: "acme", reviewId: review.id };
		const endpoint = "*/workspaces/:slug/practices/reviews/:reviewId";
		server.use(http.get(endpoint, () => HttpResponse.json({ ...review, completedAt: ISO })));

		const completed = await getReview({ path });
		const completedAt = completed.data?.completedAt;
		assert(completedAt instanceof Date);
		expect(completedAt.toISOString()).toBe(ISO);

		server.use(http.get(endpoint, () => HttpResponse.json({ ...review, completedAt: null })));
		const running = await getReview({ path });
		expect(running.data?.completedAt).toBeNull();
	});
});

const ISO = "2026-07-24T10:30:00.000Z";

function batchesResponse(status = "COMPLETED") {
	return HttpResponse.json({
		content: [
			{
				id: "11111111-1111-1111-1111-111111111111",
				itemKind: "scm.pull_request",
				origin: "BACKFILL",
				status,
				submittedCount: 0,
				skippedCount: 0,
				failedCount: 0,
				from: ISO,
				to: ISO,
				createdAt: ISO,
				startedAt: ISO,
				finishedAt: status === "COMPLETED" ? ISO : null,
			},
		],
		page: 0,
		size: 20,
		totalElements: 1,
		totalPages: 1,
	});
}
