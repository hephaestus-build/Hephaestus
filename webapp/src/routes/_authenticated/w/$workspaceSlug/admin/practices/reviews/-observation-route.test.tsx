import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { HttpResponse, http } from "msw";
import { assert, describe, expect, it, vi } from "vitest";

import {
	getPracticeReviewOverviewQueryKey,
	listPracticeStandingsQueryKey,
} from "@/api/@tanstack/react-query.gen";
import { reviewObservationDetail } from "@/components/admin/practice-reviews/fixtures";
import { reviewHandlers } from "@/components/admin/practice-reviews/story-mock-server";
import { server } from "@/mocks/server";
import { ROUTE_RENDER_WAIT, renderRouteAtWithRouter, testQueryClient } from "@/test/router-harness";

vi.setConfig({ testTimeout: 20_000 });

/** The level opened by its URL over the list it belongs to, as a shared link opens it. */
const OBSERVATION_LEVEL = `/w/acme/admin/practices/reviews/observations?detail=observation:${reviewObservationDetail.id}`;

/**
 * The level's own handlers ahead of the fixture's, which answer the list the level opens over: MSW
 * takes the first handler that matches.
 */
function stub(...handlers: Parameters<typeof server.use>) {
	server.use(
		http.get("*/workspaces/:workspaceSlug/members/me", () =>
			HttpResponse.json({ role: "ADMIN", userId: 1, userLogin: "ada", userName: "Ada" }),
		),
		...handlers,
		...reviewHandlers(),
	);
}

/**
 * The developer's own standings, and the overview's "marked incorrect" under a range the page under
 * the level has not asked for: reads that count the observation without being on screen.
 */
function countingReads(workspaceSlug: string) {
	return [
		listPracticeStandingsQueryKey({ path: { workspaceSlug } }),
		getPracticeReviewOverviewQueryKey({
			path: { workspaceSlug },
			query: { from: new Date("2026-01-01T00:00:00Z"), zone: "UTC" },
		}),
	];
}

describe("observation level", () => {
	it("marks the observation incorrect and refreshes this workspace's reads of it, and no other's", async () => {
		let sent: unknown;
		let current: typeof reviewObservationDetail = reviewObservationDetail;
		stub(
			// Re-read after the change like every other read of it, so it answers as the server would.
			http.get("*/workspaces/:workspaceSlug/practices/reviews/observations/:observationId", () =>
				HttpResponse.json(current),
			),
			http.patch(
				"*/workspaces/:workspaceSlug/practices/reviews/observations/:observationId/validity",
				async ({ request }) => {
					sent = await request.json();
					current = {
						...reviewObservationDetail,
						invalidations: [
							{
								id: "inv-1",
								reason: "The 404 comes from the router.",
								invalidatedAt: reviewObservationDetail.observedAt,
								invalidatedBy: "Ada",
								providerCopy: "PENDING",
							},
						],
					};
					return HttpResponse.json(current);
				},
			),
		);
		const queryClient = testQueryClient();
		const ours = countingReads("acme");
		const theirs = countingReads("other");
		for (const key of [...ours, ...theirs]) {
			queryClient.setQueryData(key, {});
		}

		renderRouteAtWithRouter(OBSERVATION_LEVEL, queryClient);
		const user = userEvent.setup();
		await user.click(
			await screen.findByRole("button", { name: "Mark as incorrect" }, ROUTE_RENDER_WAIT),
		);
		await user.type(
			await screen.findByRole("textbox", { name: "Reason" }),
			"The 404 comes from the router.",
		);
		const submit = screen.getAllByRole("button", { name: "Mark as incorrect" }).at(-1);
		assert(submit);
		await user.click(submit);

		await screen.findByText("Marked as incorrect");
		expect(sent).toStrictEqual({ valid: false, reason: "The 404 comes from the router." });
		await waitFor(() => expect(screen.queryByRole("textbox", { name: "Reason" })).toBeNull());
		const invalidated = (keys: typeof ours) =>
			keys.map((key) => queryClient.getQueryState(key)?.isInvalidated);
		expect(invalidated(ours)).toStrictEqual([true, true]);
		expect(invalidated(theirs)).toStrictEqual([false, false]);
	});

	it("keeps the reason and the cached reads when the server refuses the change", async () => {
		stub(
			http.get("*/workspaces/:workspaceSlug/practices/reviews/observations/:observationId", () =>
				HttpResponse.json(reviewObservationDetail),
			),
			http.patch(
				"*/workspaces/:workspaceSlug/practices/reviews/observations/:observationId/validity",
				() =>
					HttpResponse.json(
						{
							status: 409,
							detail: "Hephaestus is posting feedback that cites this observation right now.",
						},
						{ status: 409 },
					),
			),
		);
		const queryClient = testQueryClient();
		const ours = listPracticeStandingsQueryKey({ path: { workspaceSlug: "acme" } });
		queryClient.setQueryData(ours, []);

		renderRouteAtWithRouter(OBSERVATION_LEVEL, queryClient);
		const user = userEvent.setup();
		await user.click(
			await screen.findByRole("button", { name: "Mark as incorrect" }, ROUTE_RENDER_WAIT),
		);
		await user.type(
			await screen.findByRole("textbox", { name: "Reason" }),
			"The 404 comes from the router.",
		);
		const submit = screen.getAllByRole("button", { name: "Mark as incorrect" }).at(-1);
		assert(submit);
		await user.click(submit);

		await screen.findByText("Couldn't change this observation");
		expect(screen.getByRole("textbox", { name: "Reason" })).toHaveProperty(
			"value",
			"The 404 comes from the router.",
		);
		expect(queryClient.getQueryState(ours)?.isInvalidated).toBe(false);
	});

	it.each([
		{ from: "PENDING", seen: /still bringing the comments/u, after: 10_000 },
		{ from: "UNRESOLVED", seen: /cannot correct every comment/u, after: 60_000 },
	] as const)(
		"keeps watching a $from correction until it is corrected, then stops",
		async ({ from, seen, after }) => {
			vi.useFakeTimers({ shouldAdvanceTime: true });
			try {
				let reads = 0;
				const outcomes = [from, "UPDATED"] as const;
				stub(
					http.get(
						"*/workspaces/:workspaceSlug/practices/reviews/observations/:observationId",
						() => {
							reads += 1;
							return HttpResponse.json({
								...reviewObservationDetail,
								invalidations: [
									{
										id: "inv-1",
										reason: "The 404 comes from the router.",
										invalidatedAt: reviewObservationDetail.observedAt,
										invalidatedBy: "Ada",
										providerCopy: outcomes[Math.min(reads, outcomes.length) - 1],
									},
								],
							});
						},
					),
				);

				renderRouteAtWithRouter(OBSERVATION_LEVEL);
				await screen.findByText(seen, undefined, ROUTE_RENDER_WAIT);
				await vi.advanceTimersByTimeAsync(after);
				await screen.findByText(/now opens with a correction notice/u);
				expect(screen.queryByText(seen)).toBeNull();

				const settledReads = reads;
				await vi.advanceTimersByTimeAsync(2 * after);
				expect(reads).toBe(settledReads);
			} finally {
				vi.useRealTimers();
			}
		},
	);
});
