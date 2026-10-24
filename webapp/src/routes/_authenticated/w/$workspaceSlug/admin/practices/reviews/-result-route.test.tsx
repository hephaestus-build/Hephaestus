import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { HttpResponse, http } from "msw";
import { assert, beforeEach, describe, expect, it, vi } from "vitest";

import {
	getOwnResultQueryKey,
	getOwnReviewQueryKey,
	getReviewOverviewQueryKey,
	listOwnResultsQueryKey,
} from "@/api/@tanstack/react-query.gen";
import type { ResultMark, ReviewResultDetail } from "@/api/types.gen";
import { reviewResultDetail } from "@/components/admin/practice-reviews/fixtures";
import { stackInSearch } from "@/components/layout/detail-drawer/detail-stack";
import { server } from "@/mocks/server";
import { reviewHandlers } from "@/routes/_authenticated/w/$workspaceSlug/admin/practices/reviews/-reviews-server";
import { ObserverStub } from "@/test/observers";
import { ROUTE_RENDER_WAIT, renderRouteAtWithRouter, testQueryClient } from "@/test/router-harness";

vi.setConfig({ testTimeout: 20_000 });

// jsdom needs an IntersectionObserver stub for paged lists.
beforeEach(() => {
	vi.stubGlobal("IntersectionObserver", ObserverStub);
});

const RESULT_LEVEL = `/w/acme/admin/practices/reviews/results?detail=result:${reviewResultDetail.id}`;

const REASON = "The 404 comes from the router.";

// MSW selects the first matching handler; register detail overrides before list fixtures.
function stub(...handlers: Parameters<typeof server.use>) {
	server.use(...handlers, ...reviewHandlers());
}

function countingReads(workspaceSlug: string) {
	return [
		listOwnResultsQueryKey({ path: { workspaceSlug } }),
		getOwnResultQueryKey({ path: { workspaceSlug, resultId: reviewResultDetail.id } }),
		getOwnReviewQueryKey({ path: { workspaceSlug, reviewId: reviewResultDetail.reviewId } }),
		getReviewOverviewQueryKey({
			path: { workspaceSlug },
			query: { from: new Date("2026-01-01T00:00:00Z"), zone: "UTC" },
		}),
	];
}

const incorrectMark: ResultMark = {
	id: "mark-1",
	resultId: reviewResultDetail.id,
	kind: "INCORRECT",
	reason: REASON,
	createdAt: reviewResultDetail.recordedAt,
	createdBy: null,
	removedAt: null,
	removedBy: null,
	removalReason: null,
};

function withMarks(marks: ResultMark[]): ReviewResultDetail {
	const active = marks.find((mark) => mark.kind === "INCORRECT" && mark.removedAt === null);
	return {
		...reviewResultDetail,
		marks,
		markedIncorrectAt: active?.createdAt ?? null,
	};
}

async function submitReason(action: string) {
	const user = userEvent.setup();
	await user.click(await screen.findByRole("button", { name: action }, ROUTE_RENDER_WAIT));
	await user.type(await screen.findByRole("textbox", { name: "Reason" }), REASON);
	const submit = screen.getAllByRole("button", { name: action }).at(-1);
	assert(submit);
	await user.click(submit);
}

describe("result level", () => {
	it("shows the result's problems from the wire and not its example", async () => {
		assert(reviewResultDetail.summary !== null);
		stub();
		renderRouteAtWithRouter(RESULT_LEVEL);

		const level = await screen.findByRole(
			"dialog",
			{ name: reviewResultDetail.summary },
			ROUTE_RENDER_WAIT,
		);
		await within(level).findByText("Not met · 3 problems");
		const problems = within(within(level).getByRole("list", { name: "Problems" }));
		problems.getByText("A missing row and a missing grant both become a 404");
		problems.getByText("Trivial");
		expect(within(level).queryByText(/maps every error in one handler/u)).toBeNull();
		expect(within(level).queryByText("Example from this work")).toBeNull();
	});

	it("opens a problem of the result in the result's place, and Back returns to the result", async () => {
		const [problem] = reviewResultDetail.problems;
		assert(problem);
		assert(reviewResultDetail.summary !== null);
		stub();
		const { router } = renderRouteAtWithRouter(RESULT_LEVEL);

		const level = await screen.findByRole(
			"dialog",
			{ name: reviewResultDetail.summary },
			ROUTE_RENDER_WAIT,
		);
		const problems = await within(level).findByRole(
			"list",
			{ name: "Problems" },
			ROUTE_RENDER_WAIT,
		);
		await userEvent.click(within(problems).getByRole("link", { name: problem.summary.trim() }));

		await screen.findByRole("dialog", { name: problem.summary.trim() }, ROUTE_RENDER_WAIT);
		expect(stackInSearch(router.state.location.search.detail)).toStrictEqual([
			`problem:${problem.id}`,
		]);
		router.history.back();
		await screen.findByRole("dialog", { name: reviewResultDetail.summary }, ROUTE_RENDER_WAIT);
		expect(stackInSearch(router.state.location.search.detail)).toStrictEqual([
			`result:${reviewResultDetail.id}`,
		]);
	});

	it("marks the result incorrect and refreshes only this workspace's reads", async () => {
		let sent: unknown;
		let current = withMarks([]);
		stub(
			http.get("*/workspaces/:workspaceSlug/practices/reviews/results/:resultId", () =>
				HttpResponse.json(current),
			),
			http.post(
				"*/workspaces/:workspaceSlug/practices/reviews/results/:resultId/marks",
				async ({ request }) => {
					sent = await request.json();
					current = withMarks([incorrectMark]);
					return HttpResponse.json(incorrectMark, { status: 201 });
				},
			),
		);
		const queryClient = testQueryClient();
		const ours = countingReads("acme");
		const theirs = countingReads("other");
		for (const key of [...ours, ...theirs]) {
			queryClient.setQueryData(key, {});
		}

		renderRouteAtWithRouter(RESULT_LEVEL, queryClient);
		await submitReason("Mark incorrect");

		await screen.findAllByText("Marked incorrect");
		expect(sent).toStrictEqual({ kind: "INCORRECT", reason: REASON });
		await waitFor(() => expect(screen.queryByRole("textbox", { name: "Reason" })).toBeNull());
		const invalidated = (keys: typeof ours) =>
			keys.map((key) => queryClient.getQueryState(key)?.isInvalidated);
		expect(invalidated(ours)).toStrictEqual([true, true, true, true]);
		expect(invalidated(theirs)).toStrictEqual([false, false, false, false]);
	});

	it("removes the incorrect mark with its own reason", async () => {
		let sent: unknown;
		let current = withMarks([incorrectMark]);
		stub(
			http.get("*/workspaces/:workspaceSlug/practices/reviews/results/:resultId", () =>
				HttpResponse.json(current),
			),
			http.delete(
				"*/workspaces/:workspaceSlug/practices/reviews/results/:resultId/marks/:markId",
				async ({ request, params }) => {
					sent = { markId: params.markId, body: await request.json() };
					const removed = { ...incorrectMark, removedAt: new Date(), removalReason: REASON };
					current = withMarks([removed]);
					return HttpResponse.json(removed);
				},
			),
		);

		renderRouteAtWithRouter(RESULT_LEVEL);
		await submitReason("Remove mark");

		await screen.findAllByText("Mark removed");
		expect(sent).toStrictEqual({ markId: incorrectMark.id, body: { reason: REASON } });
	});

	it("keeps the reason and the cached reads when the server refuses the change", async () => {
		stub(
			http.get("*/workspaces/:workspaceSlug/practices/reviews/results/:resultId", () =>
				HttpResponse.json(withMarks([])),
			),
			http.post("*/workspaces/:workspaceSlug/practices/reviews/results/:resultId/marks", () =>
				HttpResponse.json(
					{
						status: 409,
						detail: "Hephaestus is posting feedback that cites this result right now.",
					},
					{ status: 409 },
				),
			),
		);
		const queryClient = testQueryClient();
		const [ours] = countingReads("acme");
		assert(ours);
		queryClient.setQueryData(ours, {});

		renderRouteAtWithRouter(RESULT_LEVEL, queryClient);
		await submitReason("Mark incorrect");

		await screen.findByText("We could not change this observation");
		expect(screen.getByRole("textbox", { name: "Reason" })).toHaveProperty("value", REASON);
		expect(queryClient.getQueryState(ours)?.isInvalidated).toBe(false);
	});
});
