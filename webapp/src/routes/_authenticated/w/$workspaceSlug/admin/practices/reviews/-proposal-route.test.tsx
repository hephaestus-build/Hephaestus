import { fireEvent, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { HttpResponse, http } from "msw";
import { assert, describe, expect, it, vi } from "vitest";
import { reviewFeedbackDetail } from "@/components/admin/practice-reviews/fixtures";
import { reviewHandlers } from "@/components/admin/practice-reviews/story-mock-server";
import { server } from "@/mocks/server";
import { ROUTE_RENDER_WAIT, renderRouteAtWithRouter } from "@/test/router-harness";

vi.setConfig({ testTimeout: 20_000 });

const FEEDBACK_ID = reviewFeedbackDetail.id;

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

describe("feedback proposal route", () => {
	it("shows the exact proposal and sends an approval through the generated operation", async () => {
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

		renderRouteAtWithRouter(`/w/acme/admin/practices/reviews/delivery/${FEEDBACK_ID}`);

		await screen.findByRole("heading", { name: /Feedback for/u }, ROUTE_RENDER_WAIT);
		expect(screen.getByText(/Please keep the cache scoped/u)).not.toBeNull();
		const [firstObservation] = reviewFeedbackDetail.observations;
		assert(firstObservation);
		expect(screen.getByRole("link", { name: firstObservation.summary })).not.toBeNull();

		await userEvent.click(screen.getByRole("button", { name: "Approve for delivery" }));
		await screen.findByRole("heading", { name: /Feedback for/u }, ROUTE_RENDER_WAIT);
		expect(requestBody).toStrictEqual({ decision: "APPROVED" });
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

		renderRouteAtWithRouter(`/w/acme/admin/practices/reviews/delivery/${FEEDBACK_ID}`);
		await screen.findByRole("heading", { name: /Feedback for/u }, ROUTE_RENDER_WAIT);
		await userEvent.click(screen.getByRole("button", { name: "Reject feedback" }));
		const dialog = await screen.findByRole("dialog");
		await userEvent.click(within(dialog).getByText("Missing important context"));
		await userEvent.type(
			within(dialog).getByLabelText("Note"),
			"The fallback path was not considered.",
		);
		await userEvent.click(within(dialog).getByRole("button", { name: "Reject feedback" }));

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

		renderRouteAtWithRouter(`/w/acme/admin/practices/reviews/delivery/${FEEDBACK_ID}`);
		await screen.findByRole("button", { name: "Approve for delivery" });
		fireEvent.click(screen.getByRole("button", { name: "Approve for delivery" }));

		await waitFor(() =>
			expect(screen.queryByRole("button", { name: "Approve for delivery" })).toBeNull(),
		);
		expect(detailReads).toBeGreaterThan(1);
	});
});
