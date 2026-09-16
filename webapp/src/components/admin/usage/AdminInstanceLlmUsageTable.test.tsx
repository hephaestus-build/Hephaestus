import { fireEvent, render, screen, within } from "@testing-library/react";
import { assert, describe, expect, it, vi } from "vitest";

import type {
	AdminWorkspaceLlmUsage,
	FxRateInfo,
	LlmUsageByDay,
	WorkspaceLlmUsageReport,
} from "@/api/types.gen";

import {
	AdminInstanceLlmUsageTable,
	type AdminInstanceLlmUsageTableProps,
} from "./AdminInstanceLlmUsageTable";

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
	unpricedEventCount: 0,
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

function renderTable(
	rows: AdminWorkspaceLlmUsage[],
	overrides: { isCurrentMonth?: boolean; fx?: FxRateInfo } = {},
) {
	return render(
		<AdminInstanceLlmUsageTable
			rows={rows}
			month="2026-07"
			now={new Date("2026-07-10T12:00:00.000Z")}
			fx={overrides.fx}
			isCurrentMonth={overrides.isCurrentMonth ?? true}
			isLoading={false}
			error={null}
			expandedWorkspaceSlug={null}
			isDetailLoading={false}
			detailError={null}
			onToggleDetails={vi.fn<() => void>()}
			onEditSharedModelBudget={vi.fn<() => void>()}
		/>,
	);
}

function firstDataRow(): HTMLElement {
	const [, dataRow] = screen.getAllByRole("row");
	assert(dataRow, "The table rendered its header but no workspace row.");
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
		render(
			<AdminInstanceLlmUsageTable
				rows={[workspace]}
				month="2026-07"
				now={new Date("2026-07-10T12:00:00.000Z")}
				isCurrentMonth
				isLoading={false}
				error={null}
				expandedWorkspaceSlug={null}
				isDetailLoading={false}
				detailError={null}
				onToggleDetails={onToggleDetails}
				onEditSharedModelBudget={vi.fn<() => void>()}
			/>,
		);

		screen.getByRole("columnheader", { name: "Shared-model spend" });
		screen.getByRole("columnheader", { name: "Provider spend" });
		const toggle = screen.getByRole("button", {
			name: "View usage details for Example Workspace",
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

		screen.getByRole("columnheader", { name: "Shared-model budget" });
		screen.getByRole("columnheader", { name: "Provider cap" });
		const row = within(firstDataRow());
		row.getByText("$25");
		row.getByText("$10");
	});

	it("shows how much of each cap is used, not just whether it is reached", () => {
		renderTable([{ ...workspace, instanceMonthlyBudgetUsd: 50, instanceTotalCostUsd: 38.2 }]);

		const row = within(firstDataRow());
		row.getByText("$38.20 · 76%");
		row.getByRole("progressbar", { name: "Shared-model budget used by Example Workspace" });
		expect(screen.queryByText("Within budget")).toBeNull();
	});

	it("warns before the cap is reached", () => {
		renderTable([{ ...workspace, instanceMonthlyBudgetUsd: 50, instanceTotalCostUsd: 41 }]);

		const row = within(firstDataRow());
		row.getByText("Near cap · shared models");
		// The amber tone alone must never carry the state (WCAG SC 1.4.1).
		row.getByText("$41.00 · 82% · Near cap");
	});

	it("names the cap that paused the workspace", () => {
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

		const row = within(firstDataRow());
		row.getByText("Paused · shared models");
		row.getByText("Paused · own provider");
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

		const row = within(firstDataRow());
		row.getByText("Paused · own provider");
		expect(row.queryByText("Paused · shared models")).toBeNull();
	});

	it("keeps the provider cap read-only — it is the workspace's own money", () => {
		renderTable([
			{ ...workspace, ownProviderMonthlyBudgetUsd: 10, ownProviderBudgetVerdict: "WITHIN" },
		]);

		expect(rowControlNames()).toStrictEqual([
			"View usage details for Example Workspace",
			"Set budget for Example Workspace (shared models)",
		]);
	});

	it("withdraws the budget editor on a closed month and says why, once, above the table", () => {
		renderTable([workspace], { isCurrentMonth: false });

		expect(rowControlNames()).toStrictEqual(["View usage details for Example Workspace"]);
		screen.getByText(/applies from the moment it is saved/iu);
	});

	it("says nothing about month scope while the editors are on screen", () => {
		renderTable([workspace]);

		expect(screen.queryByText(/applies from the moment it is saved/iu)).toBeNull();
	});

	it("shows daily and run-type breakdowns for the expanded workspace", () => {
		render(
			<AdminInstanceLlmUsageTable
				rows={[workspace]}
				month="2026-07"
				now={new Date("2026-07-10T12:00:00.000Z")}
				isCurrentMonth
				isLoading={false}
				error={null}
				expandedWorkspaceSlug={workspace.workspaceSlug}
				detailReport={detailReport}
				isDetailLoading={false}
				detailError={null}
				onToggleDetails={vi.fn<() => void>()}
				onEditSharedModelBudget={vi.fn<() => void>()}
			/>,
		);

		expect(
			screen
				.getByRole("button", { name: "Hide usage details for Example Workspace" })
				.getAttribute("aria-expanded"),
		).toBe("true");
		const byJobType = screen.getByRole("table", { name: "AI spend by run type" });
		within(byJobType).getByText("Mentor turn");
		within(byJobType).getByText("$1.75");
		const byDay = screen.getByRole("table", { name: "AI spend by day" });
		within(byDay).getByText("Jul 5");
		within(byDay).getByText("$4.25");
	});

	it("projects a near-cap month in the panel, in the third person the host is reading in", () => {
		render(
			<AdminInstanceLlmUsageTable
				rows={[workspace]}
				month="2026-07"
				now={new Date("2026-07-10T12:00:00.000Z")}
				isCurrentMonth
				isLoading={false}
				error={null}
				expandedWorkspaceSlug={workspace.workspaceSlug}
				detailReport={{
					...detailReport,
					instanceMonthlyBudgetUsd: 50,
					instanceTotalCostUsd: 42,
				}}
				isDetailLoading={false}
				detailError={null}
				onToggleDetails={vi.fn<() => void>()}
				onEditSharedModelBudget={vi.fn<() => void>()}
			/>,
		);

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
			row.getByLabelText("approximately 3.74 euros");
			row.getByLabelText("approximately 1.54 euros");
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

		it("hands the table's own rate and the server's own total to the expanded breakdown", () => {
			render(
				<AdminInstanceLlmUsageTable
					rows={[workspace]}
					month="2026-07"
					now={new Date("2026-07-10T12:00:00.000Z")}
					fx={eur}
					isCurrentMonth
					isLoading={false}
					error={null}
					expandedWorkspaceSlug={workspace.workspaceSlug}
					detailReport={{
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
					}}
					isDetailLoading={false}
					detailError={null}
					onToggleDetails={vi.fn<() => void>()}
					onEditSharedModelBudget={vi.fn<() => void>()}
				/>,
			);

			const byDay = screen.getByRole("table", { name: "AI spend by day" });
			const footer = within(byDay).getByRole("row", { name: /^Total/u });
			expect(footer.textContent).toContain("€");
			expect(footer.textContent).not.toContain("£");
			expect(footer.textContent).toContain("$4.25");
			expect(footer.textContent).not.toContain("$8.50");
		});
	});
});
