import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, within } from "storybook/test";

import type { LlmUsageByDay, LlmUsageByJobType, WorkspaceLlmUsageReport } from "@/api/types.gen";

import { NO_PRACTICE_SCRIPT_USAGE } from "./fixtures";
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
		...NO_PRACTICE_SCRIPT_USAGE,
		...overrides,
	};
}

const meta = {
	component: LlmUsageByJobTypeTable,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	args: { report: report(), purses: ["SHARED", "OWN_PROVIDER"] },
} satisfies Meta<typeof LlmUsageByJobTypeTable>;

export default meta;
type Story = StoryObj<typeof meta>;

export const ByJobType: Story = {
	play: async ({ canvas }) => {
		const table = within(await canvas.findByRole("table", { name: "AI spend by run type" }));
		await expect(
			table
				.getAllByRole("columnheader")
				.filter((header) => header.getAttribute("colspan") === "2")
				.map((header) => header.textContent),
		).toStrictEqual(["Shared models", "Own provider"]);
		const conversations = table.getByRole("row", { name: /^Conversation answer/u });
		await expect(
			within(conversations)
				.getAllByRole("cell")
				.slice(1, 7)
				.map((cell) => cell.textContent),
		).toStrictEqual(["$0.00", "—", "$3.15", "$0.05", "61", "4"]);
		const footer = table.getByRole("row", { name: /^Total/u });
		await expect(within(footer).getByRole("rowheader", { name: "Total" })).toBeVisible();
	},
};

/** A month of the run types a workspace records now: practice reviews and conversation turns. */
export const CurrentRunTypes: Story = {
	args: {
		purses: ["SHARED"],
		report: report({
			byJobType: [
				{
					...pullRequestReviewRow,
					jobType: "PRACTICE_REVIEW",
					events: 23,
					totalCalls: 138,
					instanceTotalCostUsd: 4.6,
				},
				{
					jobType: "MENTOR_TURN",
					events: 7,
					inputTokens: 84_000,
					outputTokens: 12_600,
					cacheReadTokens: 0,
					cacheWriteTokens: 0,
					totalCalls: 19,
					instanceTotalCostUsd: 0.42,
					ownProviderTotalCostUsd: 0,
					unpricedEventCount: 0,
				},
			],
		}),
	},
	play: async ({ canvas }) => {
		const table = within(await canvas.findByRole("table", { name: "AI spend by run type" }));
		const runs = [/^Practice review/u, /^Conversation answer/u].map((name) => {
			const cells = within(table.getByRole("row", { name }))
				.getAllByRole("cell")
				.map((cell) => cell.textContent);
			return [cells[0], cells[1], cells[3], cells.at(-1)];
		});
		await expect(runs).toStrictEqual([
			["Practice review", "$4.60", "23", "138"],
			["Conversation answer", "$0.42", "7", "19"],
		]);
	},
};

export const AllRunsPriced: Story = {
	args: { report: report({ byJobType: [pullRequestReviewRow] }) },
	play: async ({ canvas }) => {
		const table = within(await canvas.findByRole("table", { name: "AI spend by run type" }));
		await expect(table.queryByRole("columnheader", { name: "Unavailable cost" })).toBeNull();
		await expect(table.queryByRole("row", { name: /^Total/u })).toBeNull();
	},
};

export const SharedModelsOnly: Story = {
	args: { purses: ["SHARED"] },
	play: async ({ canvas }) => {
		const table = within(await canvas.findByRole("table", { name: "AI spend by run type" }));
		await expect(table.queryByText("Own provider")).toBeNull();
		await expect(table.getAllByRole("columnheader", { name: "Avg per run" })).toHaveLength(1);
	},
};

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
