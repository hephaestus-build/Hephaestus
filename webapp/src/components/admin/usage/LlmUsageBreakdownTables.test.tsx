import { render, screen, within } from "@testing-library/react";
import type { ReactElement } from "react";
import { describe, expect, it } from "vitest";

import type { LlmUsageByDay, WorkspaceLlmUsageReport } from "@/api/types.gen";
import type { Purse } from "@/components/practice-vocabulary/purse-defs";

import { NO_PRECOMPUTE_USAGE } from "./fixtures";
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
 * made of: the rows come to $9.00 and $3.00, while the report's month totals say $4.25 and $1.75. The
 * rows have 3 runs with no price, while the month has 2: one run can have usage on two days.
 */
const report: WorkspaceLlmUsageReport = {
	month: "2026-07",
	instanceTotalCostUsd: 4.25,
	ownProviderTotalCostUsd: 1.75,
	instanceBudgetVerdict: "WITHIN",
	instancePaused: false,
	ownProviderBudgetVerdict: "WITHIN",
	ownProviderPaused: false,
	ownProviderInUse: true,
	unpricedEventCount: 2,
	...NO_PRECOMPUTE_USAGE,
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

const BOTH: Purse[] = ["SHARED", "OWN_PROVIDER"];

function totalsRowOf(tableName: string): HTMLElement {
	const table = screen.getByRole("table", { name: tableName });
	return within(table).getByRole("row", { name: /^Total/u });
}

function cellTexts(row: HTMLElement): (string | null)[] {
	return within(row)
		.getAllByRole("cell")
		.map((cell) => cell.textContent);
}

describe("usage breakdown totals", () => {
	it.each<[string, () => ReactElement, string]>([
		["day", () => <LlmUsageByDayTable report={report} purses={BOTH} />, "AI spend by day"],
		[
			"run type",
			() => <LlmUsageByJobTypeTable report={report} purses={BOTH} />,
			"AI spend by run type",
		],
	])("prints the server's month spend, not a re-addition of the %s rows", (_name, table, name) => {
		render(table());

		const footer = totalsRowOf(name);
		expect(footer.textContent).toContain("$4.25");
		expect(footer.textContent).toContain("$1.75");
		expect(footer.textContent).not.toContain("$9.00");
		expect(footer.textContent).not.toContain("$3.00");
	});

	it("adds the runs up itself, takes the runs with no price from the month, and averages each purse on its own", () => {
		render(<LlmUsageByJobTypeTable report={report} purses={BOTH} />);

		const footer = totalsRowOf("AI spend by run type");
		within(footer).getByRole("rowheader", { name: "Total" });
		// $4.25 and $1.75 over 30 runs: never one average of the two purses summed.
		expect(cellTexts(footer)).toStrictEqual([
			"$4.25",
			"$0.14",
			"$1.75",
			"$0.06",
			"30",
			"2",
			"4,000",
			"2,000",
			"200",
			"600",
			"36",
		]);
	});

	it("shows cache reads and writes separately", () => {
		render(<LlmUsageByJobTypeTable report={report} purses={BOTH} />);

		const table = screen.getByRole("table", { name: "AI spend by run type" });
		within(table).getByRole("columnheader", { name: "Cache reads" });
		within(table).getByRole("columnheader", { name: "Cache writes" });
		const cells = cellTexts(within(table).getByRole("row", { name: /Pull request review/u }));
		expect(cells[8]).toBe("600");
		expect(cells[9]).toBe("50");
	});

	it("drops the footer for a single row rather than restating the line above it", () => {
		render(<LlmUsageByDayTable report={{ ...report, byDay: [julyFifth] }} purses={BOTH} />);

		const table = screen.getByRole("table", { name: "AI spend by day" });
		expect(within(table).queryByRole("row", { name: /^Total/u })).toBeNull();
	});
});

describe("usage breakdown columns", () => {
	it("puts each purse's name over its own spend and average", () => {
		render(<LlmUsageByJobTypeTable report={report} purses={BOTH} />);

		const table = screen.getByRole("table", { name: "AI spend by run type" });
		const groups = within(table)
			.getAllByRole("columnheader")
			.filter((header) => header.getAttribute("colspan") === "2");
		expect(groups.map((group) => [group.textContent, group.getAttribute("scope")])).toStrictEqual([
			["Shared models", "col"],
			["Own provider", "col"],
		]);
		expect(within(table).getAllByRole("columnheader", { name: "Avg per run" })).toHaveLength(2);
	});

	it("shows each purse's spend by day, with no daily average", () => {
		render(<LlmUsageByDayTable report={report} purses={BOTH} />);

		const table = screen.getByRole("table", { name: "AI spend by day" });
		expect(
			within(table)
				.getAllByRole("columnheader")
				.map((header) => header.textContent),
		).toStrictEqual(["Day", "Shared models", "Own provider", "Runs", "No price set"]);
		expect(cellTexts(totalsRowOf("AI spend by day"))).toStrictEqual(["$4.25", "$1.75", "30", "2"]);
	});

	it("leaves out the own-provider group when the page has no own-provider spend or cap", () => {
		render(<LlmUsageByDayTable report={report} purses={["SHARED"]} />);

		const table = screen.getByRole("table", { name: "AI spend by day" });
		expect(within(table).queryByText("Own provider")).toBeNull();
		expect(cellTexts(totalsRowOf("AI spend by day"))).toStrictEqual(["$4.25", "30", "2"]);
	});

	it("shows runs with no price only while some row has one", () => {
		const priced = {
			...report,
			byJobType: report.byJobType.map((row) => ({ ...row, unpricedEventCount: 0 })),
		};
		render(<LlmUsageByJobTypeTable report={priced} purses={BOTH} />);

		const table = screen.getByRole("table", { name: "AI spend by run type" });
		expect(within(table).queryByRole("columnheader", { name: "No price set" })).toBeNull();
	});

	it("prints nothing spent as $0.00 and averages it to $0.00, not a dash", () => {
		const free = {
			...report,
			byJobType: report.byJobType.map((row) => ({
				...row,
				ownProviderTotalCostUsd: 0,
				unpricedEventCount: 0,
			})),
		};
		render(<LlmUsageByJobTypeTable report={free} purses={BOTH} />);

		const row = screen.getByRole("row", { name: /^Pull request review/u });
		expect(cellTexts(row).slice(1, 5)).toStrictEqual(["$4.50", "$0.45", "$0.00", "$0.00"]);
	});

	it("leaves the average out where runs with no price stand beside no priced spend", () => {
		const unpriced = {
			...report,
			byJobType: report.byJobType.map((row) => ({ ...row, ownProviderTotalCostUsd: 0 })),
		};
		render(<LlmUsageByJobTypeTable report={unpriced} purses={BOTH} />);

		const row = screen.getByRole("row", { name: /^Pull request review/u });
		expect(cellTexts(row).slice(3, 5)).toStrictEqual(["$0.00", "—"]);
	});
});
