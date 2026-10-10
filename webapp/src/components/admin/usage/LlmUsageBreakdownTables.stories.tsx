import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, within } from "storybook/test";

import type { LlmUsageByDay, LlmUsageByJobType, WorkspaceLlmUsageReport } from "@/api/types.gen";

import { NO_PRECOMPUTE_USAGE } from "./fixtures";
import { LlmUsageByDayTable, LlmUsageByJobTypeTable } from "./LlmUsageBreakdownTables";

const pullRequestReviewRow: LlmUsageByJobType = {
	jobType: "PULL_REQUEST_REVIEW",
	events: 128,
	inputTokens: 4_210_000,
	outputTokens: 318_000,
	cacheReadTokens: 1_900_000,
	cacheWriteTokens: 210_000,
	totalCalls: 402,
	instanceTotalCostUsd: 12.4,
	ownProviderTotalCostUsd: 0,
	unpricedEventCount: 0,
};

const jobTypeRows: LlmUsageByJobType[] = [
	pullRequestReviewRow,
	{
		jobType: "MENTOR_TURN",
		events: 61,
		inputTokens: 890_000,
		outputTokens: 141_000,
		cacheReadTokens: 0,
		cacheWriteTokens: 0,
		totalCalls: 61,
		instanceTotalCostUsd: 0,
		ownProviderTotalCostUsd: 3.15,
		unpricedEventCount: 4,
	},
];

const dayRows: LlmUsageByDay[] = [
	{
		day: new Date("2026-07-20"),
		events: 42,
		instanceTotalCostUsd: 4.1,
		ownProviderTotalCostUsd: 0.9,
		unpricedEventCount: 0,
	},
	{
		day: new Date("2026-07-21"),
		events: 77,
		instanceTotalCostUsd: 6.3,
		ownProviderTotalCostUsd: 1.25,
		unpricedEventCount: 4,
	},
	{
		day: new Date("2026-07-22"),
		events: 70,
		instanceTotalCostUsd: 2,
		ownProviderTotalCostUsd: 1,
		unpricedEventCount: 0,
	},
];

/** The footers read money and runs with no price off the envelope rather than re-adding rows, so the fixture adds up. */
function report(overrides: Partial<WorkspaceLlmUsageReport> = {}): WorkspaceLlmUsageReport {
	const byJobType = overrides.byJobType ?? jobTypeRows;
	const byDay = overrides.byDay ?? dayRows;
	const rows = byJobType.length > 0 ? byJobType : byDay;
	return {
		month: "2026-07",
		byJobType,
		byDay,
		instanceTotalCostUsd: rows.reduce((total, row) => total + row.instanceTotalCostUsd, 0),
		ownProviderTotalCostUsd: rows.reduce((total, row) => total + row.ownProviderTotalCostUsd, 0),
		unpricedEventCount: rows.reduce((total, row) => total + row.unpricedEventCount, 0),
		instanceBudgetVerdict: "WITHIN",
		instancePaused: false,
		ownProviderBudgetVerdict: "WITHIN",
		ownProviderPaused: false,
		ownProviderInUse: true,
		...NO_PRECOMPUTE_USAGE,
		...overrides,
	};
}

/**
 * Where a month's AI spend went, split by *who pays*: two purses with two separate caps, so a single
 * merged number could not be acted on. By run type, each purse heads its own Spend and Avg per run;
 * by day, each purse has one Spend column. A confirmed $0.00 averages to $0.00; only a row with no
 * runs has no average.
 *
 * Five run types and 31 days at most, so neither table sorts or pages.
 */
const meta = {
	component: LlmUsageByJobTypeTable,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	args: { report: report(), purses: ["SHARED", "OWN_PROVIDER"] },
} satisfies Meta<typeof LlmUsageByJobTypeTable>;

export default meta;
type Story = StoryObj<typeof meta>;

/** Heph turns ran with no price set, so the table counts them in their own column. */
export const ByJobType: Story = {
	play: async ({ canvas }) => {
		const table = within(await canvas.findByRole("table", { name: "AI spend by run type" }));
		await expect(
			table
				.getAllByRole("columnheader")
				.filter((header) => header.getAttribute("colspan") === "2")
				.map((header) => header.textContent),
		).toStrictEqual(["Shared models", "Own provider"]);
		const heph = table.getByRole("row", { name: /^Heph turn/u });
		// No priced shared spend beside runs with no price: $0.00 is not a known average.
		await expect(
			within(heph)
				.getAllByRole("cell")
				.slice(1, 7)
				.map((cell) => cell.textContent),
		).toStrictEqual(["$0.00", "—", "$3.15", "$0.05", "61", "4"]);
		const footer = table.getByRole("row", { name: /^Total/u });
		await expect(within(footer).getByRole("rowheader", { name: "Total" })).toBeVisible();
	},
};

/** Every run priced: no column of zeros under *No price set*. */
export const AllRunsPriced: Story = {
	args: { report: report({ byJobType: [pullRequestReviewRow] }) },
	play: async ({ canvas }) => {
		const table = within(await canvas.findByRole("table", { name: "AI spend by run type" }));
		await expect(table.queryByRole("columnheader", { name: "No price set" })).toBeNull();
		await expect(table.queryByRole("row", { name: /^Total/u })).toBeNull();
	},
};

/** A workspace without a provider of its own: one purse, no columns of $0. */
export const SharedModelsOnly: Story = {
	args: { purses: ["SHARED"] },
	play: async ({ canvas }) => {
		const table = within(await canvas.findByRole("table", { name: "AI spend by run type" }));
		await expect(table.queryByText("Own provider")).toBeNull();
		await expect(table.getAllByRole("columnheader", { name: "Avg per run" })).toHaveLength(1);
	},
};

/**
 * Runs on a cheap model cost a fraction of a cent each. The average column shows the four decimals
 * that run type needs on every row and the total, so the decimal points line up.
 */
export const SubCentAveragePerRun: Story = {
	args: {
		purses: ["SHARED"],
		report: report({
			byJobType: [
				pullRequestReviewRow,
				{
					...pullRequestReviewRow,
					jobType: "DOCUMENT_REVIEW",
					events: 40,
					totalCalls: 40,
					instanceTotalCostUsd: 0.02,
				},
			],
		}),
	},
	play: async ({ canvas }) => {
		const table = within(await canvas.findByRole("table", { name: "AI spend by run type" }));
		const averages = [/^Pull request review/u, /^Document review/u, /^Total/u].map((name) =>
			within(table.getByRole("row", { name }))
				.getAllByRole("cell")
				.map((cell) => cell.textContent)
				.slice(-8, -6),
		);
		await expect(averages).toStrictEqual([
			["$12.40", "$0.0969"],
			["$0.02", "$0.0005"],
			["$12.42", "$0.0739"],
		]);
	},
};

export const Loading: Story = {
	args: { report: undefined },
};

export const Empty: Story = {
	args: { report: report({ byJobType: [] }) },
	play: async ({ canvas }) => {
		await expect(await canvas.findByText("No runs")).toBeVisible();
	},
};

export const ByDay: StoryObj = {
	render: () => <LlmUsageByDayTable report={report()} purses={["SHARED", "OWN_PROVIDER"]} />,
	play: async ({ canvas }) => {
		const table = within(await canvas.findByRole("table", { name: "AI spend by day" }));
		const footer = table.getByRole("row", { name: /^Total/u });
		// The money is the report's month total. The counts add up from the days, as from the run types.
		await expect(
			within(footer)
				.getAllByRole("cell")
				.map((cell) => cell.textContent),
		).toStrictEqual(["$12.40", "$3.15", "189", "4"]);
	},
};

export const ByDayLoading: StoryObj = {
	render: () => <LlmUsageByDayTable purses={["SHARED", "OWN_PROVIDER"]} />,
};

export const ByDayEmpty: StoryObj = {
	render: () => <LlmUsageByDayTable report={report({ byDay: [] })} purses={["SHARED"]} />,
};
