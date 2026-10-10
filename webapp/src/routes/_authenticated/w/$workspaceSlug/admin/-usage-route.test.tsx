import { fireEvent, screen, waitFor, within } from "@testing-library/react";
import { HttpResponse, http } from "msw";
import { describe, expect, it, vi } from "vitest";

import type { LlmUsageByPractice, WorkspaceLlmUsageReport } from "@/api/types.gen";
import type { Wire } from "@/lib/dates";
import { server } from "@/mocks/server";
import { levelsOpenedBy } from "@/test/detail-stack";
import { ROUTE_RENDER_WAIT, renderRouteAt } from "@/test/router-harness";

// Mounting the real route pulls in the whole admin layout and its lazy modules.
vi.setConfig({ testTimeout: 15_000 });

const REPORT: Wire<WorkspaceLlmUsageReport> = {
	month: "2026-07",
	ownProviderMonthlyBudgetUsd: 10,
	instanceTotalCostUsd: 0,
	ownProviderTotalCostUsd: 1.5,
	instanceBudgetVerdict: "WITHIN",
	ownProviderBudgetVerdict: "WITHIN",
	instancePaused: false,
	ownProviderPaused: false,
	ownProviderInUse: true,
	unpricedEventCount: 0,
	byJobType: [],
	byDay: [],
	byPractice: [],
	precomputeTotal: {
		reviews: 0,
		calls: 0,
		inputTokens: 0,
		outputTokens: 0,
		instanceTotalCostUsd: 0,
		ownProviderTotalCostUsd: 0,
		unpricedEventCount: 0,
	},
};

/** As the server writes it: a null slug or name is left out of the JSON. */
const PRECOMPUTE_ROWS: Wire<LlmUsageByPractice>[] = [
	{
		practiceSlug: "comment-quality",
		practiceName: "Comments explain why",
		purposes: ["PRACTICE_DECISION"],
		reviews: 4,
		calls: 40,
		inputTokens: 9000,
		outputTokens: 120,
		instanceTotalCostUsd: 0,
		ownProviderTotalCostUsd: 0.4,
		unpricedEventCount: 0,
	},
	{
		purposes: ["PRACTICE_EMBEDDING", "PRACTICE_RERANKING"],
		reviews: 1,
		calls: 6,
		inputTokens: 800,
		outputTokens: 0,
		instanceTotalCostUsd: 0,
		ownProviderTotalCostUsd: 0.02,
		unpricedEventCount: 0,
	},
];

const REJECTION = "A cap above $1,000,000 is not accepted.";

const rejected = () =>
	HttpResponse.json(
		{ status: 400, title: "Bad Request", detail: REJECTION },
		{ status: 400, headers: { "Content-Type": "application/problem+json" } },
	);

function mockUsageRoute() {
	server.use(
		http.get("*/workspaces/:workspaceSlug/members/me", () =>
			HttpResponse.json({ role: "ADMIN", userId: 1, userLogin: "ada", userName: "Ada" }),
		),
		http.get("*/workspaces/:workspaceSlug/llm/usage", () => HttpResponse.json(REPORT)),
		http.put("*/workspaces/:workspaceSlug/llm/budget", rejected),
	);
}

async function renderUsageRoute() {
	mockUsageRoute();
	renderRouteAt("/w/acme/admin/usage");
	await screen.findByRole("heading", { name: "AI usage" }, ROUTE_RENDER_WAIT);
	return screen.findByRole("button", { name: "Change provider cap" }, ROUTE_RENDER_WAIT);
}

const capField = () => screen.getByLabelText(/Monthly cap/iu);

/**
 * Only what is peculiar to *this* route. Where a rejection is reported — inline while the dialog is
 * open, as a toast once it is gone — belongs to `BudgetAmountDialog` and is asserted once, over the
 * instance budget.
 */
describe("workspace AI usage route", () => {
	it("does not re-show a dismissed rejection when the cap dialog is reopened", async () => {
		const changeCap = await renderUsageRoute();

		fireEvent.click(changeCap);
		fireEvent.change(capField(), { target: { value: "999999999" } });
		fireEvent.click(screen.getByRole("button", { name: "Save cap" }));
		await screen.findByText(REJECTION);

		fireEvent.click(screen.getByRole("button", { name: "Cancel" }));
		await waitFor(() => expect(screen.queryByText(REJECTION)).toBeNull());

		fireEvent.click(screen.getByRole("button", { name: "Change provider cap" }));

		await screen.findByRole("button", { name: "Save cap" });
		expect(screen.queryByText(REJECTION)).toBeNull();
		expect(capField().getAttribute("aria-invalid")).toBe("false");
	});

	it("offers a provider cap for a connected provider before its first call", async () => {
		mockReport({
			...REPORT,
			ownProviderMonthlyBudgetUsd: undefined,
			ownProviderTotalCostUsd: 0,
			ownProviderInUse: true,
		});
		renderRouteAt("/w/acme/admin/usage");

		await screen.findByRole("button", { name: "Set provider cap" }, ROUTE_RENDER_WAIT);
	});
});

function mockReport(report: Wire<WorkspaceLlmUsageReport>, requestedMonths: string[] = []) {
	server.use(
		http.get("*/workspaces/:workspaceSlug/members/me", () =>
			HttpResponse.json({ role: "ADMIN", userId: 1, userLogin: "ada", userName: "Ada" }),
		),
		http.get("*/workspaces/:workspaceSlug/llm/usage", ({ request }) => {
			requestedMonths.push(new URL(request.url).searchParams.get("month") ?? "");
			return HttpResponse.json(report);
		}),
	);
}

describe("workspace AI usage route, precompute models by practice", () => {
	it("asks for the month in the address and lists each practice from the wire", async () => {
		const requestedMonths: string[] = [];
		mockReport(
			{
				...REPORT,
				byJobType: [
					{
						jobType: "PULL_REQUEST_REVIEW",
						instanceTotalCostUsd: 0,
						ownProviderTotalCostUsd: 1.5,
						unpricedEventCount: 0,
						inputTokens: 90_000,
						outputTokens: 4000,
						cacheReadTokens: 0,
						cacheWriteTokens: 0,
						totalCalls: 60,
						events: 5,
					},
				],
				byPractice: PRECOMPUTE_ROWS,
				precomputeTotal: {
					...REPORT.precomputeTotal,
					reviews: 4,
					calls: 46,
					ownProviderTotalCostUsd: 0.42,
				},
			},
			requestedMonths,
		);
		renderRouteAt("/w/acme/admin/usage?month=2026-07");

		const table = await screen.findByRole(
			"table",
			{ name: "Precompute model spend by practice" },
			ROUTE_RENDER_WAIT,
		);
		expect(requestedMonths).toContain("2026-07");
		const link = within(table).getByRole("link", { name: "Comments explain why" });
		expect(new URL(String(link.getAttribute("href")), window.location.origin).pathname).toBe(
			"/w/acme/admin/practices",
		);
		expect(levelsOpenedBy(link)).toStrictEqual(["practice:comment-quality"]);
		const notAttributed = within(within(table).getByRole("row", { name: /^Not attributed/u }));
		notAttributed.getByText("Embedding model");
		notAttributed.getByText("Reranking model");
		expect(within(table).getByRole("row", { name: /^Total/u }).textContent).toContain("$0.42");
	});

	it("leaves the card out when no script called a model", async () => {
		mockReport(REPORT);
		renderRouteAt("/w/acme/admin/usage");

		await screen.findByRole("button", { name: "Change provider cap" }, ROUTE_RENDER_WAIT);
		expect(screen.queryByText("Precompute models by practice")).toBeNull();
	});

	it("says the report could not load, and offers a retry", async () => {
		server.use(
			http.get("*/workspaces/:workspaceSlug/members/me", () =>
				HttpResponse.json({ role: "ADMIN", userId: 1, userLogin: "ada", userName: "Ada" }),
			),
			http.get("*/workspaces/:workspaceSlug/llm/usage", () =>
				HttpResponse.json(
					{ status: 500, title: "Internal Server Error", detail: "The ledger is down." },
					{ status: 500, headers: { "Content-Type": "application/problem+json" } },
				),
			),
		);
		renderRouteAt("/w/acme/admin/usage");

		await screen.findByText("We could not load AI usage", undefined, ROUTE_RENDER_WAIT);
		screen.getByRole("button", { name: /retry/iu });
	});
});
