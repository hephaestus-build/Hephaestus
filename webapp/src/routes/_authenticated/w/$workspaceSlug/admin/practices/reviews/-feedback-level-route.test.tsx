import type { QueryClient } from "@tanstack/react-query";
import { fireEvent, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { HttpResponse, http } from "msw";
import { assert, describe, expect, it, vi } from "vitest";

import {
	getPracticeReviewOverviewQueryKey,
	listPracticeReviewObservationsQueryKey,
	listPracticeReviewsQueryKey,
} from "@/api/@tanstack/react-query.gen";
import { reviewFeedbackDetail } from "@/components/admin/practice-reviews/fixtures";
import { reviewHandlers } from "@/components/admin/practice-reviews/story-mock-server";
import { server } from "@/mocks/server";
import { ROUTE_RENDER_WAIT, renderRouteAtWithRouter, testQueryClient } from "@/test/router-harness";

vi.setConfig({ testTimeout: 20_000 });

const FEEDBACK_ID = reviewFeedbackDetail.id;

/** The level opened by its URL over the list it belongs to, as a shared link opens it. */
const FEEDBACK_LEVEL = `/w/acme/admin/practices/reviews/feedback?detail=feedback:${FEEDBACK_ID}`;

function proposalWire(deliveryState: "AWAITING_APPROVAL" | "PREPARED") {
	const body = "Please keep the cache scoped to one workspace so membership changes cannot leak.";
	return {
		...reviewFeedbackDetail,
		deliveryState,
		body,
		proposedPlacements:
			reviewFeedbackDetail.proposedPlacements.length === 0
				? [{ type: "SUMMARY" as const, body }]
				: reviewFeedbackDetail.proposedPlacements.map((placement) =>
						placement.type === "SUMMARY" ? { ...placement, body } : placement,
					),
		createdAt: reviewFeedbackDetail.createdAt.toISOString(),
		observations: reviewFeedbackDetail.observations.map((observation) => ({
			...observation,
			observedAt: observation.observedAt.toISOString(),
		})),
	};
}

/**
 * Cached reads that count feedback by delivery state, under filters the level cannot know and the
 * page under it does not mount, so each stays in the cache for the assertion to read.
 */
function seedCountingReads(queryClient: QueryClient, workspaceSlug: string) {
	const path = { workspaceSlug };
	const keys = [
		getPracticeReviewOverviewQueryKey({
			path,
			query: { from: new Date("2026-01-01T00:00:00Z"), zone: "UTC" },
		}),
		listPracticeReviewsQueryKey({ path, query: { status: ["COMPLETED"] } }),
		listPracticeReviewObservationsQueryKey({ path, query: { agentJobId: "cached-review" } }),
	];
	for (const key of keys) {
		queryClient.setQueryData(key, {});
	}
	return keys;
}

describe("feedback approval level", () => {
	it("shows the exact proposal, approves it, and refreshes this workspace's counts of it", async () => {
		let deliveryState: "AWAITING_APPROVAL" | "PREPARED" = "AWAITING_APPROVAL";
		let requestBody: unknown;
		server.use(
			http.get("*/workspaces/:workspaceSlug/members/me", () =>
				HttpResponse.json({ role: "ADMIN", userId: 1, userLogin: "ada", userName: "Ada" }),
			),
			http.get("*/workspaces/:workspaceSlug/practices/reviews/feedback/:feedbackId", () =>
				HttpResponse.json(proposalWire(deliveryState)),
			),
			http.put(
				"*/workspaces/:workspaceSlug/practices/reviews/feedback/:feedbackId/approval",
				async ({ request }) => {
					requestBody = await request.json();
					deliveryState = "PREPARED";
					return HttpResponse.json({ feedbackId: FEEDBACK_ID, decision: "APPROVED" });
				},
			),
			...reviewHandlers(),
		);

		const queryClient = testQueryClient();
		const ours = seedCountingReads(queryClient, "acme");
		const theirs = seedCountingReads(queryClient, "other");
		renderRouteAtWithRouter(FEEDBACK_LEVEL, queryClient);

		await screen.findByRole("heading", { name: /Feedback for/u }, ROUTE_RENDER_WAIT);
		expect(screen.getByText(/Please keep the cache scoped/u)).not.toBeNull();
		const [firstObservation] = reviewFeedbackDetail.observations;
		assert(firstObservation);
		expect(screen.getByRole("link", { name: firstObservation.summary })).not.toBeNull();

		await userEvent.click(screen.getByRole("button", { name: "Approve for delivery" }));
		await screen.findByText("Feedback approved. Delivery is being checked.");
		expect(requestBody).toStrictEqual({ decision: "APPROVED" });
		// The level reads the decision back rather than assuming it: once prepared, there is nothing
		// left to approve.
		await waitFor(() =>
			expect(screen.queryByRole("button", { name: "Approve for delivery" })).toBeNull(),
		);
		// Every count of it moved: the overview's "awaiting approval", a review's tally, an
		// observation's feedback — in this workspace only.
		const invalidated = (keys: typeof ours) =>
			keys.map((key) => queryClient.getQueryState(key)?.isInvalidated);
		expect(invalidated(ours)).toStrictEqual([true, true, true]);
		expect(invalidated(theirs)).toStrictEqual([false, false, false]);
	});

	it("sends the selected rejection category and reviewer context", async () => {
		let requestBody: unknown;
		server.use(
			http.get("*/workspaces/:workspaceSlug/members/me", () =>
				HttpResponse.json({ role: "ADMIN", userId: 1, userLogin: "ada", userName: "Ada" }),
			),
			http.get("*/workspaces/:workspaceSlug/practices/reviews/feedback/:feedbackId", () =>
				HttpResponse.json(proposalWire("AWAITING_APPROVAL")),
			),
			http.put(
				"*/workspaces/:workspaceSlug/practices/reviews/feedback/:feedbackId/approval",
				async ({ request }) => {
					requestBody = await request.json();
					return HttpResponse.json({ feedbackId: FEEDBACK_ID, decision: "REJECTED" });
				},
			),
			...reviewHandlers(),
		);

		renderRouteAtWithRouter(FEEDBACK_LEVEL);
		await screen.findByRole("heading", { name: /Feedback for/u }, ROUTE_RENDER_WAIT);
		await userEvent.click(screen.getByRole("button", { name: "Reject feedback" }));
		const dialog = await screen.findByRole("dialog", { name: "Reject this feedback" });
		await userEvent.click(within(dialog).getByText("Missing important context"));
		await userEvent.type(
			within(dialog).getByLabelText("Note"),
			"The fallback path was not considered.",
		);
		await userEvent.click(within(dialog).getByRole("button", { name: "Reject feedback" }));

		await screen.findByText("Feedback rejected");
		expect(requestBody).toStrictEqual({
			decision: "REJECTED",
			rejectionReason: "MISSING_CONTEXT",
			rejectionNote: "The fallback path was not considered.",
		});
	});

	it("reloads the winning decision after a concurrent approval conflict", async () => {
		let deliveryState: "AWAITING_APPROVAL" | "PREPARED" = "AWAITING_APPROVAL";
		let detailReads = 0;
		server.use(
			http.get("*/workspaces/:workspaceSlug/members/me", () =>
				HttpResponse.json({ role: "ADMIN", userId: 1, userLogin: "ada", userName: "Ada" }),
			),
			http.get("*/workspaces/:workspaceSlug/practices/reviews/feedback/:feedbackId", () => {
				detailReads += 1;
				return HttpResponse.json(proposalWire(deliveryState));
			}),
			http.put(
				"*/workspaces/:workspaceSlug/practices/reviews/feedback/:feedbackId/approval",
				() => {
					deliveryState = "PREPARED";
					return HttpResponse.json(
						{ status: 409, title: "This review was already decided" },
						{ status: 409 },
					);
				},
			),
			...reviewHandlers(),
		);

		renderRouteAtWithRouter(FEEDBACK_LEVEL);
		await screen.findByRole("button", { name: "Approve for delivery" });
		fireEvent.click(screen.getByRole("button", { name: "Approve for delivery" }));

		await waitFor(() =>
			expect(screen.queryByRole("button", { name: "Approve for delivery" })).toBeNull(),
		);
		expect(detailReads).toBeGreaterThan(1);
	});
});
