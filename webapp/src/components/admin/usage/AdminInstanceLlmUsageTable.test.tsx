import { fireEvent, render, screen, within } from "@testing-library/react";
import { assert, describe, expect, it, vi } from "vitest";

import type {
	AdminWorkspaceLlmUsage,
	FxRateInfo,
	LlmUsageByDay,
	WorkspaceLlmUsageReport,
} from "@/api/types.gen";

import {
	type AdminInstanceUsageView,
	AdminInstanceLlmUsageTable,
	type AdminInstanceLlmUsageTableProps,
} from "./AdminInstanceLlmUsageTable";
import { NO_PRECOMPUTE_USAGE } from "./fixtures";

const workspace: AdminWorkspaceLlmUsage = {
	workspaceSlug: "example-workspace",
	displayName: "Example Workspace",
	instanceMonthlyBudgetUsd: 25,
	instanceTotalCostUsd: 4.25,
	instanceBudgetVerdict: "WITHIN",
	instancePaused: false,
	ownProviderTotalCostUsd: 1.75,
	ownProviderBudgetVerdict: "WITHIN",
	ownProviderPaused: false,
	ownProviderInUse: true,
	events: 3,
};

const julyFifth: LlmUsageByDay = {
	day: new Date("2026-07-05T00:00:00.000Z"),
	instanceTotalCostUsd: 4.25,
	ownProviderTotalCostUsd: 1.75,
	unpricedEventCount: 0,
	events: 1,
};

const detailReport: WorkspaceLlmUsageReport = {
	month: "2026-07",
	instanceMonthlyBudgetUsd: 25,
	instanceTotalCostUsd: 4.25,
	instanceBudgetVerdict: "WITHIN",
	instancePaused: false,
	ownProviderMonthlyBudgetUsd: 10,
	ownProviderTotalCostUsd: 1.75,
	ownProviderBudgetVerdict: "WITHIN",
	ownProviderPaused: false,
	ownProviderInUse: true,
	unpricedEventCount: 0,
	...NO_PRECOMPUTE_USAGE,
	byJobType: [
		{
			jobType: "MENTOR_TURN",
			instanceTotalCostUsd: 4.25,
			ownProviderTotalCostUsd: 1.75,
			unpricedEventCount: 0,
			inputTokens: 100,
			outputTokens: 25,
			cacheReadTokens: 0,
			cacheWriteTokens: 0,
			totalCalls: 4,
			events: 2,
		},
	],
	byDay: [julyFifth],
};

const DEFAULT_VIEW: AdminInstanceUsageView = { q: "", sort: "sharedSpend", desc: true, page: 0 };

function renderTable(
	rows: AdminWorkspaceLlmUsage[],
	overrides: Partial<AdminInstanceLlmUsageTableProps> = {},
) {
	return render(
		<AdminInstanceLlmUsageTable
			rows={rows}
			month="2026-07"
			now={new Date("2026-07-10T12:00:00.000Z")}
			isCurrentMonth
			isLoading={false}
			error={null}
			view={DEFAULT_VIEW}
			onViewChange={vi.fn()}
			expandedWorkspaceSlug={null}
			isDetailLoading={false}
			detailError={null}
			onToggleDetails={vi.fn()}
			onEditSharedModelBudget={vi.fn()}
			{...overrides}
		/>,
	);
}

/** The cell that holds a limit's meter: its badges and caption sit beside the bar. */
function capCellOf(meterName: string): HTMLElement {
	const cell = screen.getByRole("progressbar", { name: meterName }).closest("td");
	assert(cell, `No cell holds the meter "${meterName}".`);
	return cell;
}

function workspaceNames(): (string | null)[] {
	return screen
		.getAllByRole("row")
		.slice(2)
		.map(
			(row) =>
				within(row).getAllByRole("cell")[0]?.querySelector(".font-medium")?.textContent ?? null,
		);
}

function firstDataRow(): HTMLElement {
	// Two header rows: the purse names, then the columns under them.
	const dataRow = screen.getAllByRole("row")[2];
	assert(dataRow, "The table rendered its two header rows but no workspace row.");
	return dataRow;
}

/** What a screen reader announces for each control in the workspace row, in DOM order. */
function rowControlNames() {
	return within(firstDataRow())
		.getAllByRole("button")
		.map((button) => button.getAttribute("aria-label") ?? button.textContent);
}

describe("AdminInstanceLlmUsageTable", () => {
	it("offers an accessible per-workspace detail toggle", () => {
		const onToggleDetails = vi.fn<AdminInstanceLlmUsageTableProps["onToggleDetails"]>();
		renderTable([workspace], { onToggleDetails });

		screen.getByRole("columnheader", { name: "Shared models" });
		screen.getByRole("columnheader", { name: "Own provider" });
		const toggle = screen.getByRole("button", {
			name: "Details for Example Workspace",
		});
		expect(toggle.getAttribute("aria-expanded")).toBe("false");
		// Collapsed, so there is no detail row for `aria-controls` to point at.
		expect(toggle.getAttribute("aria-controls")).toBeNull();

		fireEvent.click(toggle);
		expect(onToggleDetails).toHaveBeenCalledWith(workspace);
	});

	it("separates the shared-model budget from the workspace's own provider cap", () => {
		renderTable([
			{ ...workspace, ownProviderMonthlyBudgetUsd: 10, ownProviderBudgetVerdict: "WITHIN" },
		]);

		// The host's limit is a budget and the workspace's own is a cap, each named for whose it is.
		screen.getByRole("columnheader", { name: "Budget (Shared models)" });
		screen.getByRole("columnheader", { name: "Cap (Own provider)" });
		const row = within(firstDataRow());
		row.getByText("$25");
		row.getByText("$10");
	});

	it("shows how much of each limit is used, not just whether it is reached", () => {
		renderTable([{ ...workspace, instanceMonthlyBudgetUsd: 50, instanceTotalCostUsd: 38.2 }]);

		const row = within(firstDataRow());
		row.getByText("$38.20");
		within(capCellOf("Shared-model budget used by Example Workspace")).getByText("76% used");
		expect(screen.queryByText("Within budget")).toBeNull();
	});

	it("warns before the budget is reached", () => {
		renderTable([{ ...workspace, instanceMonthlyBudgetUsd: 50, instanceTotalCostUsd: 41 }]);

		// The amber tone alone must never carry the state (WCAG SC 1.4.1).
		const shared = within(capCellOf("Shared-model budget used by Example Workspace"));
		shared.getByText("82% used");
		shared.getByText("Near the budget");
		expect(screen.getAllByText(/^Near the /u)).toHaveLength(1);
	});

	it("names the limit that paused the workspace", () => {
		renderTable([
			{
				...workspace,
				instanceBudgetVerdict: "EXHAUSTED",
				instancePaused: true,
				ownProviderMonthlyBudgetUsd: 10,
				ownProviderTotalCostUsd: 10,
				ownProviderBudgetVerdict: "EXHAUSTED",
				ownProviderPaused: true,
			},
		]);

		within(capCellOf("Shared-model budget used by Example Workspace")).getByText("Paused");
		within(capCellOf("Provider cap used by Example Workspace")).getByText("Paused");
	});

	it("says which limit cannot be checked because some calls have no price", () => {
		renderTable([{ ...workspace, instanceBudgetVerdict: "UNVERIFIABLE", instancePaused: true }]);

		const shared = within(capCellOf("Shared-model budget used by Example Workspace"));
		shared.getByText("Paused");
		shared.getByText("No price set");
	});

	it("marks runs with no price on a purse with no budget, rather than reading as $0.00", () => {
		renderTable([
			{
				...workspace,
				instanceMonthlyBudgetUsd: undefined,
				instanceTotalCostUsd: 0,
				instanceBudgetVerdict: "UNVERIFIABLE",
			},
		]);

		// Spend, then the budget column: no budget, and the state of the spend beside it.
		const cells = within(firstDataRow()).getAllByRole("cell");
		expect(cells[1]?.textContent).toBe("$0.00");
		expect(cells[2]?.textContent).toBe("—No price set");
		expect(screen.queryByRole("progressbar", { name: /^Shared-model budget/u })).toBeNull();
	});

	it("reports a provider-cap pause even when the shared-model budget is untouched", () => {
		renderTable([
			{
				...workspace,
				instanceMonthlyBudgetUsd: undefined,
				ownProviderMonthlyBudgetUsd: 10,
				ownProviderTotalCostUsd: 10,
				ownProviderBudgetVerdict: "EXHAUSTED",
				ownProviderPaused: true,
			},
		]);

		within(capCellOf("Provider cap used by Example Workspace")).getByText("Paused");
		expect(screen.getAllByText("Paused")).toHaveLength(1);
	});

	it("keeps the provider cap read-only — it is the workspace's own money", () => {
		renderTable([
			{ ...workspace, ownProviderMonthlyBudgetUsd: 10, ownProviderBudgetVerdict: "WITHIN" },
		]);

		expect(rowControlNames()).toStrictEqual([
			"Details for Example Workspace",
			"Change budget for Example Workspace (shared models)",
		]);
	});

	it("names the budget it edits, and whether there is one yet", () => {
		renderTable([{ ...workspace, instanceMonthlyBudgetUsd: undefined }]);

		const button = within(firstDataRow()).getByRole("button", {
			name: "Set budget for Example Workspace (shared models)",
		});
		expect(button.textContent).toBe("Set budget");
	});

	it("withdraws the budget editor on a closed month and says why, once, above the table", () => {
		renderTable([workspace], { isCurrentMonth: false });

		expect(rowControlNames()).toStrictEqual(["Details for Example Workspace"]);
		screen.getByText(/applies from the moment it is saved/iu);
	});

	it("says nothing about month scope while the editors are on screen", () => {
		renderTable([workspace]);

		expect(screen.queryByText(/applies from the moment it is saved/iu)).toBeNull();
	});

	it("shows daily and run-type breakdowns for the expanded workspace", () => {
		renderTable([workspace], {
			expandedWorkspaceSlug: workspace.workspaceSlug,
			detailReport,
		});

		screen.getByRole("heading", { level: 2, name: "Example Workspace" });
		expect(
			screen
				.getByRole("button", { name: "Details for Example Workspace" })
				.getAttribute("aria-expanded"),
		).toBe("true");
		const byJobType = screen.getByRole("table", { name: "AI spend by run type" });
		within(byJobType).getByText("Heph turn");
		within(byJobType).getByText("$1.75");
		const byDay = screen.getByRole("table", { name: "AI spend by day" });
		const julyFifthRow = within(byDay).getByRole("row", { name: /^Jul 5/u });
		expect(
			within(julyFifthRow)
				.getAllByRole("cell")
				.map((cell) => cell.textContent),
		).toStrictEqual(["Jul 5", "$4.25", "$1.75", "1"]);
	});

	it("projects a near-cap month in the panel, in the third person the host is reading in", () => {
		renderTable([workspace], {
			expandedWorkspaceSlug: workspace.workspaceSlug,
			detailReport: { ...detailReport, instanceMonthlyBudgetUsd: 50, instanceTotalCostUsd: 42 },
		});

		screen.getByText("Example Workspace has used 84% of its shared-model budget");
		screen.getByText(/At this pace, the budget is reached around July 12\./u);
		expect(screen.queryByText(/of its provider cap/u)).toBeNull();
	});

	describe("display currency", () => {
		const eur: FxRateInfo = {
			currencyCode: "EUR",
			ratePerUsd: 0.878966,
			rateDate: new Date("2026-07-24T00:00:00.000Z"),
			source: "ECB",
		};

		it("converts both spend columns, not just the one the host pays for", () => {
			renderTable([workspace], { fx: eur });

			const row = within(firstDataRow());
			row.getByLabelText("about 3.74 euros");
			row.getByLabelText("about 1.54 euros");
		});

		it("stays silent about a rate nothing on the table used", () => {
			renderTable([{ ...workspace, instanceTotalCostUsd: 0, ownProviderTotalCostUsd: 0 }], {
				fx: eur,
			});

			expect(screen.queryByText(/reference rate published on/u)).toBeNull();
		});

		it("survives a month with no workspaces in it", () => {
			renderTable([], { fx: eur });

			screen.getByText("No workspaces on this instance yet");
			expect(screen.queryByText(/reference rate published on/u)).toBeNull();
		});

		it("keeps the expanded breakdown's total on the server's own figure, in USD", () => {
			renderTable([workspace], {
				fx: eur,
				expandedWorkspaceSlug: workspace.workspaceSlug,
				detailReport: {
					...detailReport,
					fx: { ...eur, currencyCode: "GBP", ratePerUsd: 0.5 },
					byDay: [
						julyFifth,
						{
							day: new Date("2026-07-06T00:00:00.000Z"),
							instanceTotalCostUsd: 4.25,
							ownProviderTotalCostUsd: 1.75,
							unpricedEventCount: 0,
							events: 1,
						},
					],
				},
			});

			const byDay = screen.getByRole("table", { name: "AI spend by day" });
			const footer = within(byDay).getByRole("row", { name: /^Total/u });
			expect(footer.textContent).not.toContain("€");
			expect(footer.textContent).not.toContain("£");
			expect(footer.textContent).toContain("$4.25");
			expect(footer.textContent).not.toContain("$8.50");
		});
	});

	describe("the workspaces table", () => {
		const many: AdminWorkspaceLlmUsage[] = Array.from({ length: 30 }, (_, index) => ({
			...workspace,
			workspaceSlug: `ws-${String(index + 1).padStart(2, "0")}`,
			displayName: `Workspace ${String(index + 1).padStart(2, "0")}`,
			instanceTotalCostUsd: index + 1,
			ownProviderTotalCostUsd: 0,
			ownProviderInUse: false,
		}));

		it("lists the most shared-model spend first, 25 to a page", () => {
			renderTable(many);

			const names = workspaceNames();
			expect(names).toHaveLength(25);
			expect(names.slice(0, 2)).toStrictEqual(["Workspace 30", "Workspace 29"]);
			expect(
				screen
					.getByRole("columnheader", { name: "Spend (Shared models)" })
					.getAttribute("aria-sort"),
			).toBe("descending");
			screen.getByRole("button", { name: "Go to page 2" });
		});

		it("shows the page the view names", () => {
			renderTable(many, { view: { ...DEFAULT_VIEW, page: 1 } });

			expect(workspaceNames()).toStrictEqual([
				"Workspace 05",
				"Workspace 04",
				"Workspace 03",
				"Workspace 02",
				"Workspace 01",
			]);
		});

		it("reads a page past the last one as the last page", () => {
			renderTable(many, { view: { ...DEFAULT_VIEW, page: 98 } });

			expect(workspaceNames()).toHaveLength(5);
			expect(workspaceNames()[0]).toBe("Workspace 05");
			screen.getByRole("button", { name: "Go to page 1" });
		});

		it("sorts by shared spend when the address names an own-provider column that is not shown", () => {
			renderTable(many, { view: { ...DEFAULT_VIEW, sort: "ownProviderSpend", desc: false } });

			expect(workspaceNames().slice(0, 2)).toStrictEqual(["Workspace 30", "Workspace 29"]);
			expect(
				screen
					.getByRole("columnheader", { name: "Spend (Shared models)" })
					.getAttribute("aria-sort"),
			).toBe("descending");
		});

		it("filters by name", () => {
			renderTable(many, { view: { ...DEFAULT_VIEW, q: "Workspace 17" } });

			expect(workspaceNames()).toStrictEqual(["Workspace 17"]);
		});

		it("matches names only, not figures", () => {
			renderTable(many, { view: { ...DEFAULT_VIEW, q: "30.00" } });

			screen.getByText("No workspaces match your search");
		});

		it("leaves out the own-provider group when no workspace uses its own provider", () => {
			renderTable(many);

			expect(screen.queryByRole("columnheader", { name: "Own provider" })).toBeNull();
			expect(screen.queryByRole("columnheader", { name: "Cap (Own provider)" })).toBeNull();
		});

		it("shows the own-provider group for a provider connected before its first call", () => {
			renderTable([...many, { ...workspace, ownProviderTotalCostUsd: 0 }]);

			screen.getByRole("columnheader", { name: "Own provider" });
		});
	});
});
