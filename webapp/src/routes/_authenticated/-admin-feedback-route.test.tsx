import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { HttpResponse, http } from "msw";
import { beforeAll, beforeEach, describe, expect, it } from "vitest";

import type {
	FeedbackItem,
	LlmModel,
	ProductFeedbackDestinations,
	ProductFeedbackNeedsActionList,
} from "@/api/types.gen";
import type { Wire } from "@/lib/dates";
import { problemType } from "@/lib/problem-detail";
import { server } from "@/mocks/server";
import { ROUTE_RENDER_WAIT, renderRouteAt, loadRouteCode } from "@/test/router-harness";

// Mounting the real route pulls in the whole admin layout and its lazy modules.
beforeAll(
	async () => loadRouteCode("/admin/feedback", "/w/acme/practices/code-review"),
	ROUTE_RENDER_WAIT.timeout,
);

const bugReport = {
	id: "f1f1f1f1-0000-0000-0000-000000000001",
	kind: "BUG",
	message: "The practice page jumps to the top every time I change the filter.",
	account: { id: 2, displayName: "Grace Hopper", email: "grace@example.org" },
	workspace: { id: 7, slug: "acme", displayName: "Acme" },
	pagePath: "/w/acme/practices/code-review",
	appVersion: "0.71.0",
	createdAt: "2026-09-11T09:00:00.000Z",
	canFile: false,
	savedBeforeDisclosure: false,
} satisfies Wire<FeedbackItem>;

const resolvedNote = {
	id: "f1f1f1f1-0000-0000-0000-000000000004",
	kind: "FEEDBACK",
	message: "A weekly digest would be even better.",
	appVersion: "0.69.0",
	createdAt: "2026-08-30T09:00:00.000Z",
	resolvedAt: "2026-09-01T09:00:00.000Z",
	resolvedBy: { id: 1, displayName: "Ada Lovelace" },
	canFile: false,
	savedBeforeDisclosure: false,
} satisfies Wire<FeedbackItem>;

/** What each status filter lists; a request without one, or with any other, sees the open items. */
const INBOX_BY_STATUS: Record<string, Wire<FeedbackItem>[]> = {
	RESOLVED: [resolvedNote],
	ALL: [bugReport, resolvedNote],
};

/** Answers by status and records which status each request asked for. */
function mockReports() {
	const requested: (string | null)[] = [];
	server.use(
		http.get("*/admin/product-feedback", ({ request }) => {
			const status = new URL(request.url).searchParams.get("status");
			requested.push(status);
			const content = INBOX_BY_STATUS[status ?? ""] ?? [bugReport];
			return HttpResponse.json({
				content,
				page: 0,
				size: 50,
				totalElements: content.length,
				totalPages: 1,
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
	reportsToMove: 0,
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
	limits: { contextWindow: 128_000, maxOutputTokens: 16_384, bounded: true },
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
	cleanups: [],
} satisfies Wire<ProductFeedbackNeedsActionList>;

const scrubbed = {
	items: [],
	cleanups: [
		{
			id: "e1e1e1e1-0000-0000-0000-000000000001",
			step: "EDIT_HISTORY",
			repository: "hephaestus-build/product-feedback",
			issueNumber: 12,
			issueUrl: "https://github.com/hephaestus-build/product-feedback/issues/12",
			marker: "hephaestus-product-feedback:5d1c0a52-0000-0000-0000-000000000001",
			erasedAt: "2026-09-11T08:00:00.000Z",
			since: "2026-09-11T08:05:00.000Z",
		},
	],
} satisfies Wire<ProductFeedbackNeedsActionList>;

beforeEach(() => {
	server.use(
		http.get("*/admin/product-feedback/destinations", () => HttpResponse.json(destinations)),
		http.get("*/admin/product-feedback/needs-action", () =>
			HttpResponse.json({ items: [], cleanups: [] }),
		),
		http.get("*/admin/llm/models", () => HttpResponse.json([draftingModel])),
	);
});

describe("instance product feedback destinations", () => {
	it("files a saved report on GitHub and says when this instance cannot send email", async () => {
		const filed: string[] = [];
		mockReports();
		server.use(
			http.get("*/admin/product-feedback", () =>
				HttpResponse.json({
					content: [{ ...bugReport, canFile: true }],
					page: 0,
					size: 50,
					totalElements: 1,
					totalPages: 1,
				}),
			),
			http.get("*/admin/email/status", () => HttpResponse.json({ configured: false })),
			http.post(`*/admin/product-feedback/${bugReport.id}/file`, () => {
				filed.push(bugReport.id);
				return new HttpResponse(null, { status: 204 });
			}),
		);
		renderRouteAt("/admin/feedback");
		const user = userEvent.setup();

		await screen.findByText(/This instance cannot send email/u, undefined, ROUTE_RENDER_WAIT);
		await user.click(await screen.findByRole("button", { name: "File on GitHub" }));
		const confirm = await screen.findByRole("alertdialog");
		expect(filed).toStrictEqual([]);
		await user.click(within(confirm).getByRole("button", { name: "File on GitHub" }));

		await waitFor(() => expect(filed).toStrictEqual([bugReport.id]));
		await screen.findByText("Report sent to filing");
	});

	it("saves the destinations as the server's update", async () => {
		const puts: unknown[] = [];
		mockReports();
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
		mockReports();
		server.use(
			http.put("*/admin/product-feedback/destinations", () =>
				HttpResponse.json(
					{ status: 403, type: problemType("step-up-required"), maxAgeSeconds: 300 },
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
		mockReports();
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

	it("confirms an erased report's GitHub step only after the admin agrees", async () => {
		mockReports();
		const confirmed: string[] = [];
		server.use(
			http.get("*/admin/product-feedback/needs-action", () => HttpResponse.json(scrubbed)),
			http.post("*/admin/product-feedback/cleanups/:cleanupId/confirm", ({ params }) => {
				confirmed.push(String(params.cleanupId));
				return new HttpResponse(null, { status: 204 });
			}),
		);
		renderRouteAt("/admin/feedback");
		const user = userEvent.setup();

		await user.click(
			await screen.findByRole("button", { name: "Confirm done" }, ROUTE_RENDER_WAIT),
		);
		const dialog = await screen.findByRole("alertdialog", {
			name: "Is the erased text gone from GitHub?",
		});
		expect(confirmed).toStrictEqual([]);
		await user.click(within(dialog).getByRole("button", { name: "Confirm done" }));

		await waitFor(() => expect(confirmed).toStrictEqual([scrubbed.cleanups[0]?.id]));
	});
});

describe("instance product feedback route", () => {
	it("shows open feedback by default and asks for the status the filter selects", async () => {
		const requested = mockReports();
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

	it("marks feedback resolved and refreshes the reports", async () => {
		mockReports();
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
