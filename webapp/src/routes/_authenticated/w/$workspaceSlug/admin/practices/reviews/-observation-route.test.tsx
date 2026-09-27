import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { HttpResponse, http } from "msw";
import { assert, describe, expect, it, vi } from "vitest";

import { listPracticeStandingsQueryKey } from "@/api/@tanstack/react-query.gen";
import { reviewObservationDetail } from "@/components/admin/practice-reviews/fixtures";
import { server } from "@/mocks/server";
import { ROUTE_RENDER_WAIT, renderRouteAtWithRouter, testQueryClient } from "@/test/router-harness";

vi.setConfig({ testTimeout: 20_000 });

describe("observation detail route", () => {
	it("marks the observation incorrect and refreshes this workspace's reads of it, and no other's", async () => {
		let sent: unknown;
		server.use(
			http.get("*/workspaces/:workspaceSlug/members/me", () =>
				HttpResponse.json({ role: "ADMIN", userId: 1, userLogin: "ada", userName: "Ada" }),
			),
			http.get("*/workspaces/:workspaceSlug/practices", () => HttpResponse.json([])),
			http.get("*/workspaces/:workspaceSlug/practices/reviews/observations/:observationId", () =>
				HttpResponse.json(reviewObservationDetail),
			),
			http.patch(
				"*/workspaces/:workspaceSlug/practices/reviews/observations/:observationId/validity",
				async ({ request }) => {
					sent = await request.json();
					return HttpResponse.json({
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
					});
				},
			),
		);
		const queryClient = testQueryClient();
		const ours = listPracticeStandingsQueryKey({ path: { workspaceSlug: "acme" } });
		const theirs = listPracticeStandingsQueryKey({ path: { workspaceSlug: "other" } });
		queryClient.setQueryData(ours, []);
		queryClient.setQueryData(theirs, []);

		renderRouteAtWithRouter(
			`/w/acme/admin/practices/reviews/observations/${reviewObservationDetail.id}`,
			queryClient,
		);
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
		expect(queryClient.getQueryState(ours)?.isInvalidated).toBe(true);
		expect(queryClient.getQueryState(theirs)?.isInvalidated).toBe(false);
	});

	it("keeps the reason and the cached reads when the server refuses the change", async () => {
		server.use(
			http.get("*/workspaces/:workspaceSlug/members/me", () =>
				HttpResponse.json({ role: "ADMIN", userId: 1, userLogin: "ada", userName: "Ada" }),
			),
			http.get("*/workspaces/:workspaceSlug/practices", () => HttpResponse.json([])),
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

		renderRouteAtWithRouter(
			`/w/acme/admin/practices/reviews/observations/${reviewObservationDetail.id}`,
			queryClient,
		);
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
				server.use(
					http.get("*/workspaces/:workspaceSlug/members/me", () =>
						HttpResponse.json({ role: "ADMIN", userId: 1, userLogin: "ada", userName: "Ada" }),
					),
					http.get("*/workspaces/:workspaceSlug/practices", () => HttpResponse.json([])),
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

				renderRouteAtWithRouter(
					`/w/acme/admin/practices/reviews/observations/${reviewObservationDetail.id}`,
				);
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
