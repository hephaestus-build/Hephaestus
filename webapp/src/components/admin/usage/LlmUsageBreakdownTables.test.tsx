import { render, screen, within } from "@testing-library/react";
import type { ReactElement } from "react";
import { describe, expect, it } from "vitest";

import type { LlmUsageByDay, WorkspaceLlmUsageReport } from "@/api/types.gen";

import { LlmUsageByDayTable, LlmUsageByJobTypeTable } from "./LlmUsageBreakdownTables";

const julyFifth: LlmUsageByDay = {
	day: new Date("2026-07-05T00:00:00.000Z"),
	instanceTotalCostUsd: 4.5,
	ownProviderTotalCostUsd: 1.5,
	unpricedEventCount: 1,
	events: 10,
};

/**
 * Deliberately inconsistent with its own rows, which is the only way to see which number a footer is
 * made of: the rows come to $9.00 and $3.00, while the report's month totals say $4.25 and $1.75.
 */
const report: WorkspaceLlmUsageReport = {
	month: "2026-07",
	instanceTotalCostUsd: 4.25,
	ownProviderTotalCostUsd: 1.75,
	instanceBudgetVerdict: "WITHIN",
	instancePaused: false,
	ownProviderBudgetVerdict: "WITHIN",
	ownProviderPaused: false,
	unpricedEventCount: 3,
	byDay: [
		julyFifth,
		{
			day: new Date("2026-07-06T00:00:00.000Z"),
			instanceTotalCostUsd: 4.5,
			ownProviderTotalCostUsd: 1.5,
			unpricedEventCount: 2,
			events: 20,
		},
	],
	byJobType: [
		{
			jobType: "PULL_REQUEST_REVIEW",
			instanceTotalCostUsd: 4.5,
			ownProviderTotalCostUsd: 1.5,
			unpricedEventCount: 1,
			inputTokens: 1000,
			outputTokens: 200,
			cacheReadTokens: 600,
			cacheWriteTokens: 50,
			totalCalls: 12,
			events: 10,
		},
		{
			jobType: "MENTOR_TURN",
			instanceTotalCostUsd: 4.5,
			ownProviderTotalCostUsd: 1.5,
			unpricedEventCount: 2,
			inputTokens: 3000,
			outputTokens: 400,
			cacheReadTokens: 1400,
			cacheWriteTokens: 150,
			totalCalls: 24,
			events: 20,
		},
	],
};

function totalsRowOf(tableName: string): HTMLElement {
	const table = screen.getByRole("table", { name: tableName });
	return within(table).getByRole("row", { name: /^Total/u });
}

describe("usage breakdown totals", () => {
	it.each<[string, () => ReactElement, string]>([
		["day", () => <LlmUsageByDayTable report={report} />, "AI spend by day"],
		["run type", () => <LlmUsageByJobTypeTable report={report} />, "AI spend by run type"],
	])("prints the server's month spend, not a re-addition of the %s rows", (_name, table, name) => {
		render(table());

		const footer = totalsRowOf(name);
		expect(footer.textContent).toContain("$4.25");
		expect(footer.textContent).toContain("$1.75");
		expect(footer.textContent).not.toContain("$9.00");
		expect(footer.textContent).not.toContain("$3.00");
	});

	it("still adds the counts up itself — integers sum exactly, and the report carries no token totals", () => {
		render(<LlmUsageByJobTypeTable report={report} />);

		const footer = totalsRowOf("AI spend by run type");
		const cells = within(footer).getAllByRole("cell");
		expect(cells.map((cell) => cell.textContent)).toStrictEqual([
			"Total",
			"$4.25",
			"$1.75",
			"—",
			"3",
			"4,000",
			"2,000",
			"200",
			"600",
			"36",
			"30",
		]);
	});

	it("shows cache reads and writes separately", () => {
		render(<LlmUsageByJobTypeTable report={report} />);

		const table = screen.getByRole("table", { name: "AI spend by run type" });
		within(table).getByRole("columnheader", { name: "Cache reads" });
		within(table).getByRole("columnheader", { name: "Cache writes" });
		const cells = within(within(table).getByRole("row", { name: /PR review/u })).getAllByRole(
			"cell",
		);
		expect(cells[6]?.textContent).toBe("600");
		expect(cells[7]?.textContent).toBe("50");
	});

	it("drops the footer for a single row rather than restating the line above it", () => {
		render(<LlmUsageByDayTable report={{ ...report, byDay: [julyFifth] }} />);

		const table = screen.getByRole("table", { name: "AI spend by day" });
		expect(within(table).queryByRole("row", { name: /^Total/u })).toBeNull();
	});
});
