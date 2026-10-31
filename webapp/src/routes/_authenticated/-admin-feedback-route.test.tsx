import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { HttpResponse, http } from "msw";
import { beforeEach, describe, expect, it, vi } from "vitest";

import type {
	FeedbackItem,
	LlmModel,
	ProductFeedbackDestinations,
	ProductFeedbackNeedsActionList,
} from "@/api/types.gen";
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

/** What each status filter lists; a request without one, or with any other, sees the open items. */
const INBOX_BY_STATUS: Record<string, Wire<FeedbackItem>[]> = {
	RESOLVED: [resolvedNote],
	ALL: [bugReport, resolvedNote],
};

/** Answers by status and records which status each request asked for. */
function mockInbox() {
	const requested: (string | null)[] = [];
	server.use(
		http.get("*/admin/product-feedback", ({ request }) => {
			const status = new URL(request.url).searchParams.get("status");
			requested.push(status);
			const content = INBOX_BY_STATUS[status ?? ""] ?? [bugReport];
			return HttpResponse.json({
				content,
				page: { totalPages: 1, totalElements: content.length },
			});
		}),
	);
	return requested;
}

const destinations = {
	publicRepository: "hephaestus-build/Hephaestus",
	dailyDraftLimit: 50,
	paused: false,
	noticeVersion: 3,
	publicCheck: "INSTALLED",
	privateCheck: "UNSET",
	draftsToday: 4,
	needsAction: 1,
} satisfies Wire<ProductFeedbackDestinations>;

const draftingModel = {
	id: 7,
	slug: "drafting",
	displayName: "Drafting model",
	connectionDisplayName: "University cluster",
	connectionId: 1,
	createdAt: "2026-09-01T09:00:00.000Z",
	dataHandlingTier: "IN_HOUSE",
	enabled: true,
	grantedWorkspaceIds: [],
	upstreamModelId: "drafting-1",
	visibility: "PUBLIC",
} satisfies Wire<LlmModel>;

const stopped = {
	items: [
		{
			id: "c1c1c1c1-0000-0000-0000-000000000001",
			kind: "BUG",
			createdAt: "2026-09-11T08:00:00.000Z",
			stoppedAt: "2026-09-11T08:10:00.000Z",
			reason: "GitHub refused it",
			route: "PUBLIC",
			repository: "hephaestus-build/Hephaestus",
		},
	],
} satisfies Wire<ProductFeedbackNeedsActionList>;

beforeEach(() => {
	server.use(
		http.get("*/admin/product-feedback/destinations", () => HttpResponse.json(destinations)),
		http.get("*/admin/product-feedback/needs-action", () => HttpResponse.json({ items: [] })),
		http.get("*/admin/llm/models", () => HttpResponse.json([draftingModel])),
	);
});

describe("instance product feedback destinations", () => {
	it("saves the destinations as the server's update", async () => {
		const puts: unknown[] = [];
		mockInbox();
		server.use(
			http.put("*/admin/product-feedback/destinations", async ({ request }) => {
				const body = await request.json();
				puts.push(body);
				return HttpResponse.json({
					...destinations,
					privateRepository: "hephaestus-build/product-feedback",
					dailyDraftLimit: 20,
					noticeVersion: 4,
				});
			}),
		);
		renderRouteAt("/admin/feedback");
		const user = userEvent.setup();

		const privateRepository = await screen.findByRole(
			"textbox",
			{ name: "Private repository" },
			ROUTE_RENDER_WAIT,
		);
		await user.type(privateRepository, "hephaestus-build/product-feedback");
		const limit = screen.getByRole("spinbutton", { name: "Daily draft limit" });
		await user.clear(limit);
		await user.type(limit, "20");
		await user.click(screen.getByRole("checkbox", { name: "Ask everyone to choose again" }));
		await user.click(screen.getByRole("button", { name: "Save destinations" }));

		await waitFor(() => expect(puts).toHaveLength(1));
		expect(puts[0]).toStrictEqual({
			publicRepository: "hephaestus-build/Hephaestus",
			privateRepository: "hephaestus-build/product-feedback",
			dailyDraftLimit: 20,
			paused: false,
			newNoticeVersion: true,
		});
		// The saved answer reseeds the form: the box is clear again for the next notice.
		await waitFor(() =>
			expect(
				screen
					.getByRole("checkbox", { name: "Ask everyone to choose again" })
					.getAttribute("aria-checked"),
			).toBe("false"),
		);
	});

	it("asks for a fresh sign-in when the save needs one", async () => {
		mockInbox();
		server.use(
			http.put("*/admin/product-feedback/destinations", () =>
				HttpResponse.json(
					{ status: 403, code: "step_up_required", maxAgeSeconds: 300 },
					{ status: 403 },
				),
			),
		);
		renderRouteAt("/admin/feedback");
		const user = userEvent.setup();

		await user.click(await screen.findByRole("switch", { name: "Pause" }, ROUTE_RENDER_WAIT));
		await user.click(screen.getByRole("button", { name: "Save destinations" }));

		await screen.findByRole("dialog", { name: "Confirm access" });
	});

	it("retries a stopped report", async () => {
		mockInbox();
		const retried: string[] = [];
		server.use(
			http.get("*/admin/product-feedback/needs-action", () => HttpResponse.json(stopped)),
			http.post("*/admin/product-feedback/:feedbackId/retry", ({ params }) => {
				retried.push(String(params.feedbackId));
				return new HttpResponse(null, { status: 204 });
			}),
		);
		renderRouteAt("/admin/feedback");
		const user = userEvent.setup();

		await user.click(await screen.findByRole("button", { name: "Retry" }, ROUTE_RENDER_WAIT));

		await waitFor(() => expect(retried).toStrictEqual([stopped.items[0]?.id]));
	});
});

describe("instance feedback inbox route", () => {
	it("shows open feedback by default and asks for the status the filter selects", async () => {
		const requested = mockInbox();
		renderRouteAt("/admin/feedback");
		const user = userEvent.setup();

		await screen.findByText(bugReport.message, undefined, ROUTE_RENDER_WAIT);
		expect(requested).toContain("OPEN");
		expect(screen.getByRole("link", { name: "grace@example.org" }).getAttribute("href")).toBe(
			"mailto:grace@example.org",
		);
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
