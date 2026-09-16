import { fireEvent, screen, waitFor, within } from "@testing-library/react";
import { HttpResponse, http, type PathParams } from "msw";
import { describe, expect, it, vi } from "vitest";

import type { AdminWorkspaceLlmUsage, WorkspaceLlmUsageReport } from "@/api/types.gen";
import { currentMonthUtc, formatMonthLabel } from "@/components/admin/usage/usage-utils";
import { server } from "@/mocks/server";
import { ROUTE_RENDER_WAIT, renderRouteAt } from "@/test/router-harness";

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
		unpricedEventCount: 0,
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
	return screen.findByRole("button", { name: /Set budget for Acme/u }, ROUTE_RENDER_WAIT);
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

		fireEvent.click(screen.getByRole("button", { name: /View usage details for Acme/u }));
		await screen.findByText("Acme has used 86% of its shared-model budget");

		fireEvent.click(screen.getByRole("button", { name: /Set budget for Acme/u }));
		await saveBudget("200");

		await screen.findByText("Budget saved. New calls resume within a minute.");
		await waitFor(() =>
			expect(screen.queryByText(/Acme has used \d+% of its shared-model budget/u)).toBeNull(),
		);
	});

	it("says so out loud when a budget write fails after the dialog was dismissed", async () => {
		let releaseBudgetPut: (() => void) | undefined;
		const slowPut = new Promise<void>((resolve) => {
			releaseBudgetPut = resolve;
		});
		mockUsageRoutes({
			budgetUsd: 50,
			onPutBudget: async () => {
				await slowPut;
				return HttpResponse.json(
					{ status: 500, title: "Internal Server Error", detail: "The budget service is down." },
					{ status: 500, headers: { "Content-Type": "application/problem+json" } },
				);
			},
		});
		await renderUsageRoute();

		fireEvent.click(screen.getByRole("button", { name: /Set budget for Acme/u }));
		await saveBudget("200");

		fireEvent.keyDown(await screen.findByRole("dialog"), { key: "Escape" });
		await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());

		releaseBudgetPut?.();

		await screen.findByText("Couldn't save the budget");
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

		fireEvent.click(screen.getByRole("button", { name: /Set budget for Acme/u }));
		await saveBudget("9999999");

		const dialog = await screen.findByRole("dialog");
		await within(dialog).findByText("A budget above $1,000,000 is refused.");
		expect(screen.queryByText("Couldn't save the budget")).toBeNull();
	});
});
