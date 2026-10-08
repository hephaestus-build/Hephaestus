import { HttpResponse, http } from "msw";
import { assert, describe, expect, it } from "vitest";

import { adminListAuthEvents, listBackfillRuns } from "@/api/sdk.gen";
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
					page: { number: 0, size: 20, totalElements: 1, totalPages: 1 },
				}),
			),
		);

		const { data } = await adminListAuthEvents();
		const occurredAt = data?.content?.[0]?.occurredAt;

		// Not `instanceof` alone: a transformer that fabricated `new Date()` would satisfy that while
		// losing the instant the server sent.
		assert(occurredAt instanceof Date);
		expect(occurredAt.toISOString()).toBe(ISO);
	});

	it("revives dates nested in a list payload, where the recursion could quietly stop", async () => {
		server.use(http.get("*/workspaces/:slug/practices/backfill-runs", () => runsResponse()));

		const { data } = await listBackfillRuns({ path: { workspaceSlug: "acme" } });

		expect(data?.[0]?.fromAt).toBeInstanceOf(Date);
		expect(data?.[0]?.createdAt).toBeInstanceOf(Date);
	});

	it("leaves an omitted optional date undefined rather than an Invalid Date", async () => {
		server.use(
			// `finishedAt` deliberately absent: a run still in flight has not finished.
			http.get("*/workspaces/:slug/practices/backfill-runs", () => runsResponse("RUNNING")),
		);

		const { data } = await listBackfillRuns({ path: { workspaceSlug: "acme" } });

		// `new Date(undefined)` is an Invalid Date, which renders as the literal text "Invalid Date"
		// instead of the em dash a screen shows for "not yet". The transformer must skip the field.
		expect(data?.[0]?.finishedAt).toBeUndefined();
	});
});

const ISO = "2026-07-24T10:30:00.000Z";

function runsResponse(status = "COMPLETED") {
	return HttpResponse.json([
		{
			id: "11111111-1111-1111-1111-111111111111",
			artifactKind: "scm.pull_request",
			status,
			discoveredVia: "BACKFILL",
			submittedCount: 0,
			passedCount: 0,
			failedCount: 0,
			fromAt: ISO,
			toAt: ISO,
			createdAt: ISO,
		},
	]);
}
