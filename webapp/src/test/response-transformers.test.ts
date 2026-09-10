import { HttpResponse, http } from "msw";
import { assert, describe, expect, it } from "vitest";

import { adminListAuthEvents, getDirectoryPolicy, listBackfillRuns } from "@/api/sdk.gen";
import { server } from "@/mocks/server";

/**
 * Guards the one setting that makes the generated `Date` types true: `transformer: true` on the
 * `@hey-api/sdk` plugin in `openapi-ts.config.ts`.
 *
 * The transformers plugin emits `transformers.gen.ts` whether or not anything consumes it, so
 * dropping that setting leaves a client that compiles, exports every hook and still types timestamps
 * as `Date` while handing components the raw ISO string. `typecheck` cannot see the difference. It
 * has shipped that way once, crashing a screen that formatted a timestamp.
 *
 * The assertions go through the real SDK rather than reading `sdk.gen.ts` as text, so they fail for
 * the reason that matters — a caller not getting a `Date` — however the wiring got lost. It lives
 * outside `src/api/` because `openapi-ts` empties that directory on every run.
 */
describe("generated SDK response transformers", () => {
	it("preserves an unconfigured directory state and revives nested directory evidence", async () => {
		server.use(http.get("*/workspaces/acme/directory-access", () => HttpResponse.json({})));
		const empty = await getDirectoryPolicy({ path: { workspaceSlug: "acme" } });
		expect(empty.data?.policy).toBeUndefined();
		server.use(
			http.get("*/workspaces/acme/directory-access", () =>
				HttpResponse.json({
					policy: {
						approvedAt: ISO,
						approvedEvidence: { startedAt: ISO, completedAt: ISO },
						previewEvidence: { startedAt: ISO, completedAt: ISO },
					},
				}),
			),
		);
		const configured = await getDirectoryPolicy({ path: { workspaceSlug: "acme" } });
		expect(configured.data?.policy?.approvedAt).toBeInstanceOf(Date);
		expect(configured.data?.policy?.approvedEvidence?.startedAt.toISOString()).toBe(ISO);
		expect(configured.data?.policy?.previewEvidence?.completedAt.toISOString()).toBe(ISO);
	});

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
