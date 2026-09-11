import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { HttpResponse, http } from "msw";
import { describe, expect, it, vi } from "vitest";

import type { FeedbackItem } from "@/api/types.gen";
import type { Wire } from "@/lib/dates";
import { server } from "@/mocks/server";
import { ROUTE_RENDER_WAIT, renderRouteAt } from "@/test/router-harness";

// Mounting the real route pulls in the whole admin layout and its lazy modules.
vi.setConfig({ testTimeout: 30_000 });

const bugReport = {
	id: "f1f1f1f1-0000-0000-0000-000000000001",
	kind: "BUG",
	message: "The practice page jumps to the top every time I change the filter.",
	account: { id: 2, displayName: "Grace Hopper", email: "grace@example.org" },
	workspace: { id: 7, slug: "acme", displayName: "Acme" },
	pagePath: "/w/acme/practices/code-review",
	appVersion: "0.71.0",
	createdAt: "2026-09-11T09:00:00.000Z",
} satisfies Wire<FeedbackItem>;

const resolvedNote = {
	id: "f1f1f1f1-0000-0000-0000-000000000004",
	kind: "FEEDBACK",
	message: "A weekly digest would be even better.",
	appVersion: "0.69.0",
	createdAt: "2026-08-30T09:00:00.000Z",
	resolvedAt: "2026-09-01T09:00:00.000Z",
	resolvedBy: { id: 1, displayName: "Ada Lovelace" },
} satisfies Wire<FeedbackItem>;

/** Answers by status and records which status each request asked for. */
function mockInbox() {
	const requested: (string | null)[] = [];
	server.use(
		http.get("*/admin/product-feedback", ({ request }) => {
			const status = new URL(request.url).searchParams.get("status");
			requested.push(status);
			const content =
				status === "RESOLVED"
					? [resolvedNote]
					: status === "ALL"
						? [bugReport, resolvedNote]
						: [bugReport];
			return HttpResponse.json({
				content,
				page: { totalPages: 1, totalElements: content.length },
			});
		}),
	);
	return requested;
}

describe("instance feedback inbox route", () => {
	it("shows open feedback by default and asks for the status the filter selects", async () => {
		const requested = mockInbox();
		renderRouteAt("/admin/feedback");
		const user = userEvent.setup();

		await screen.findByText(bugReport.message, undefined, ROUTE_RENDER_WAIT);
		expect(requested).toContain("OPEN");
		expect(screen.queryByText(resolvedNote.message)).toBeNull();

		await user.click(screen.getByRole("button", { name: "Resolved" }));
		await screen.findByText(resolvedNote.message);
		expect(requested.at(-1)).toBe("RESOLVED");
		expect(screen.queryByText(bugReport.message)).toBeNull();

		await user.click(screen.getByRole("button", { name: "All" }));
		await screen.findByText(bugReport.message);
		expect(requested.at(-1)).toBe("ALL");
		expect(screen.getByText("2 pieces of feedback.")).not.toBeNull();
	});

	it("marks feedback resolved and refreshes the inbox", async () => {
		mockInbox();
		const triaged: { id: string; body: unknown }[] = [];
		server.use(
			http.patch("*/admin/product-feedback/:feedbackId", async ({ params, request }) => {
				triaged.push({ id: String(params.feedbackId), body: await request.json() });
				return HttpResponse.json({ ...bugReport, resolvedAt: "2026-09-11T10:00:00.000Z" });
			}),
		);
		renderRouteAt("/admin/feedback");
		const user = userEvent.setup();

		await user.click(
			await screen.findByRole("button", { name: "Mark resolved" }, ROUTE_RENDER_WAIT),
		);

		await waitFor(() => expect(triaged).toHaveLength(1));
		expect(triaged[0]).toStrictEqual({ id: bugReport.id, body: { resolved: true } });
	});
});
