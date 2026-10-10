import { fireEvent, screen, waitFor, within } from "@testing-library/react";
import { HttpResponse, http, type PathParams } from "msw";
import { describe, expect, it, vi } from "vitest";

import type { AdminWorkspaceLlmUsage, WorkspaceLlmUsageReport } from "@/api/types.gen";
import { NO_PRECOMPUTE_USAGE } from "@/components/admin/usage/fixtures";
import { currentMonthUtc, formatMonthLabel } from "@/components/admin/usage/usage-utils";
import { server } from "@/mocks/server";
import { deferred } from "@/test/async";
import { ROUTE_RENDER_WAIT, renderRouteAt, renderRouteAtWithRouter } from "@/test/router-harness";

// Mounting the real route pulls in the whole admin layout and its lazy modules.
vi.setConfig({ testTimeout: 20_000 });

/** The real current month: the editor and the cap state are withdrawn on a closed one. */
const MONTH = currentMonthUtc();

/** 86% of the $50 budget below, so the pace warning is on screen to read. */
const SPENT_USD = 43;

function row(budgetUsd: number | undefined): AdminWorkspaceLlmUsage {
	return {
		workspaceSlug: "acme",
		displayName: "Acme",
		events: 12,
		instanceTotalCostUsd: SPENT_USD,
		instanceMonthlyBudgetUsd: budgetUsd,
		instanceBudgetVerdict: "WITHIN",
		instancePaused: false,
		ownProviderTotalCostUsd: 0,
		ownProviderBudgetVerdict: "WITHIN",
		ownProviderPaused: false,
		ownProviderInUse: false,
	};
}

function detail(budgetUsd: number | undefined): WorkspaceLlmUsageReport {
	return {
		month: MONTH,
		instanceTotalCostUsd: SPENT_USD,
		instanceMonthlyBudgetUsd: budgetUsd,
		instanceBudgetVerdict: "WITHIN",
		instancePaused: false,
		ownProviderTotalCostUsd: 0,
		ownProviderBudgetVerdict: "WITHIN",
		ownProviderPaused: false,
		ownProviderInUse: false,
		unpricedEventCount: 0,
		...NO_PRECOMPUTE_USAGE,
		byJobType: [],
		byDay: [],
	};
}

const requestedMonths: string[] = [];

function mockUsageRoutes(options: {
	budgetUsd?: number;
	onPutBudget?: (budgetUsd: number | undefined) => Promise<Response> | Response;
}) {
	let budget = options.budgetUsd;
	requestedMonths.length = 0;
	const putBudget =
		options.onPutBudget ??
		(async (next: number | undefined) => {
			budget = next;
			return HttpResponse.json({ monthlyBudgetUsd: next ?? null });
		});
	server.use(
		http.get("*/admin/llm/usage", ({ request }) => {
			requestedMonths.push(new URL(request.url).searchParams.get("month") ?? "");
			return HttpResponse.json({ month: MONTH, workspaces: [row(budget)] });
		}),
		http.get("*/workspaces/:workspaceSlug/llm/usage", () => HttpResponse.json(detail(budget))),
		http.put<PathParams, { monthlyBudgetUsd?: number }>(
			"*/admin/workspaces/:workspaceSlug/llm/budget",
			async ({ request }) => {
				const body = await request.json();
				return putBudget(body.monthlyBudgetUsd);
			},
		),
	);
}

async function renderUsageRoute(url = "/admin/usage") {
	renderRouteAt(url);
	await screen.findByRole("heading", { name: "AI usage" }, ROUTE_RENDER_WAIT);
	return screen.findByRole(
		"button",
		{ name: "Change budget for Acme (shared models)" },
		ROUTE_RENDER_WAIT,
	);
}

async function saveBudget(amount: string) {
	const dialog = await screen.findByRole("dialog");
	fireEvent.change(within(dialog).getByLabelText(/Monthly budget/iu), {
		target: { value: amount },
	});
	fireEvent.click(within(dialog).getByRole("button", { name: "Save budget" }));
}

describe("instance AI usage route", () => {
	// `?month=` is route state, so only a mounted router exercises `usageSearchSchema`'s clamp.
	it("clamps a future ?month= back to this month", async () => {
		mockUsageRoutes({ budgetUsd: 50 });
		await renderUsageRoute("/admin/usage?month=2999-01");

		screen.getByText(formatMonthLabel(MONTH));
		expect(requestedMonths).toStrictEqual([MONTH]);
	});

	// The row and the panel are fed by two endpoints, and the panel stays mounted across the write.
	it("refreshes the expanded panel's budget, not just the row's", async () => {
		mockUsageRoutes({ budgetUsd: 50 });
		await renderUsageRoute();

		fireEvent.click(screen.getByRole("button", { name: /^Details for Acme/u }));
		await screen.findByText("Acme has used 86% of its shared-model budget");

		fireEvent.click(screen.getByRole("button", { name: "Change budget for Acme (shared models)" }));
		await saveBudget("200");

		await screen.findByText("Budget saved. New calls resume within a minute.");
		await waitFor(() =>
			expect(screen.queryByText(/Acme has used \d+% of its shared-model budget/u)).toBeNull(),
		);
	});

	it("says so out loud when a budget write fails after the dialog was dismissed", async () => {
		const slowPut = deferred();
		mockUsageRoutes({
			budgetUsd: 50,
			onPutBudget: async () => {
				await slowPut.promise;
				return HttpResponse.json(
					{ status: 500, title: "Internal Server Error", detail: "The budget service is down." },
					{ status: 500, headers: { "Content-Type": "application/problem+json" } },
				);
			},
		});
		await renderUsageRoute();

		fireEvent.click(screen.getByRole("button", { name: "Change budget for Acme (shared models)" }));
		await saveBudget("200");

		fireEvent.keyDown(await screen.findByRole("dialog"), { key: "Escape" });
		await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());

		slowPut.resolve();

		await screen.findByText("We could not save the budget");
		screen.getByText("The budget service is down.");
		expect(
			screen
				.getByRole("progressbar", { name: "Shared-model budget used by Acme" })
				.getAttribute("aria-valuetext"),
		).toBe("86% used, $43.00 of $50");
	});

	it("reports a rejection inline, and only inline, while the dialog is open", async () => {
		mockUsageRoutes({
			budgetUsd: 50,
			onPutBudget: () =>
				HttpResponse.json(
					{ status: 400, title: "Bad Request", detail: "A budget above $1,000,000 is refused." },
					{ status: 400, headers: { "Content-Type": "application/problem+json" } },
				),
		});
		await renderUsageRoute();

		fireEvent.click(screen.getByRole("button", { name: "Change budget for Acme (shared models)" }));
		await saveBudget("9999999");

		const dialog = await screen.findByRole("dialog");
		await within(dialog).findByText("A budget above $1,000,000 is refused.");
		expect(screen.queryByText("We could not save the budget")).toBeNull();
	});
});

/** Thirty workspaces, the most shared-model spend at "Team 30". */
function mockManyWorkspaces() {
	const workspaces = Array.from({ length: 30 }, (_, index) => {
		const number = String(index + 1).padStart(2, "0");
		return {
			...row(undefined),
			workspaceSlug: `team-${number}`,
			displayName: `Team ${number}`,
			instanceTotalCostUsd: index + 1,
		};
	});
	server.use(http.get("*/admin/llm/usage", () => HttpResponse.json({ month: MONTH, workspaces })));
}

function goToPageTwo() {
	return screen.getByRole("button", { name: "Go to page 2" });
}

async function workspaceRows() {
	const table = await screen.findByRole(
		"table",
		{ name: "Per-workspace AI spend for the selected month" },
		ROUTE_RENDER_WAIT,
	);
	return within(table)
		.getAllByRole("row")
		.slice(2)
		.map(
			(tableRow) =>
				within(tableRow).getAllByRole("cell")[0]?.querySelector(".font-medium")?.textContent,
		);
}

describe("instance AI usage route, the workspaces table", () => {
	it("opens on the most shared-model spend, 25 to a page", async () => {
		mockManyWorkspaces();
		renderRouteAt("/admin/usage");

		await screen.findByRole("button", { name: "Go to page 2" }, ROUTE_RENDER_WAIT);
		const names = await workspaceRows();
		expect(names).toHaveLength(25);
		expect(names[0]).toBe("Team 30");
	});

	it("reads the filter and the sort from the address", async () => {
		mockManyWorkspaces();
		renderRouteAt("/admin/usage?q=Team%201&sort=workspace&desc=false");

		await screen.findByText("Team 19", undefined, ROUTE_RENDER_WAIT);
		await expect(workspaceRows()).resolves.toStrictEqual([
			"Team 10",
			"Team 11",
			"Team 12",
			"Team 13",
			"Team 14",
			"Team 15",
			"Team 16",
			"Team 17",
			"Team 18",
			"Team 19",
		]);
		expect(screen.getByLabelText("Search workspaces")).toBe(screen.getByDisplayValue("Team 1"));
	});

	it("writes the reader's choices to the address, leaving out the defaults", async () => {
		mockManyWorkspaces();
		const { router } = renderRouteAtWithRouter("/admin/usage");

		await screen.findByRole("button", { name: "Go to page 2" }, ROUTE_RENDER_WAIT);
		fireEvent.click(goToPageTwo());
		await waitFor(() => expect(router.state.location.searchStr).toBe("?page=1"));

		// A new sort starts again on the first page.
		const workspace = screen.getByRole("columnheader", { name: "Workspace" });
		fireEvent.click(within(workspace).getByRole("button"));
		await waitFor(() => expect(router.state.location.searchStr).toBe("?sort=workspace&desc=false"));

		// So does a new search.
		fireEvent.click(goToPageTwo());
		await waitFor(() =>
			expect(router.state.location.searchStr).toBe("?sort=workspace&desc=false&page=1"),
		);
		fireEvent.change(screen.getByLabelText("Search workspaces"), { target: { value: "Team 2" } });
		await waitFor(() =>
			expect(router.state.location.searchStr).toBe("?sort=workspace&desc=false&q=Team+2"),
		);
		await screen.findByText("Team 29");
		expect(screen.queryByText("Team 30")).toBeNull();

		fireEvent.click(screen.getByRole("button", { name: "Reset" }));
		await waitFor(() => expect(router.state.location.searchStr).toBe("?sort=workspace&desc=false"));
		await screen.findByText("Team 01");
	});

	it("reads values it cannot use as the defaults, and a page past the end as the last page", async () => {
		mockManyWorkspaces();
		const { router } = renderRouteAtWithRouter("/admin/usage?page=99&sort=bogus&desc=maybe");

		await screen.findByText("Team 05", undefined, ROUTE_RENDER_WAIT);
		const rows = await workspaceRows();
		// Most shared spend first, on the last of two pages: 30 workspaces, 25 to a page.
		expect(rows).toStrictEqual(["Team 05", "Team 04", "Team 03", "Team 02", "Team 01"]);
		expect(
			screen.getByRole("columnheader", { name: "Spend (Shared models)" }).getAttribute("aria-sort"),
		).toBe("descending");
		// Read, not rewritten: the address changes only when the reader changes the view.
		expect(router.state.location.search).toMatchObject({ page: 99 });
	});
});
