import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";

import type {
	FxRateInfo,
	LlmUsageByDay,
	LlmUsageByJobType,
	WorkspaceLlmUsageReport,
} from "@/api/types.gen";

import { LlmUsageByDayTable, LlmUsageByJobTypeTable } from "./LlmUsageBreakdownTables";

const eur: FxRateInfo = {
	currencyCode: "EUR",
	ratePerUsd: 0.88,
	rateDate: new Date("2026-07-24"),
	source: "ECB",
};

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
		unpricedEventCount: 2,
	},
	{
		day: new Date("2026-07-22"),
		events: 70,
		instanceTotalCostUsd: 2,
		ownProviderTotalCostUsd: 1,
		unpricedEventCount: 0,
	},
];

/** The footers read money off the envelope rather than re-adding rows, so the fixture adds up. */
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
		...overrides,
	};
}

/**
 * Where a month's AI spend went, split by *who pays*: two purses with two separate caps, so a single
 * merged number could not be acted on.
 */
const meta = {
	component: LlmUsageByJobTypeTable,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	args: { report: report(), fx: eur },
} satisfies Meta<typeof LlmUsageByJobTypeTable>;

export default meta;
type Story = StoryObj<typeof meta>;

export const ByJobType: Story = {
	play: async ({ canvas }) => {
		await expect(await canvas.findByText("PR review")).toBeVisible();
		await expect(await canvas.findByText("Mentor turn")).toBeVisible();
	},
};

export const SingleRowHasNoTotals: Story = {
	args: { report: report({ byJobType: [pullRequestReviewRow] }) },
};

export const Loading: Story = {
	args: { report: undefined },
};

export const Empty: Story = {
	args: { report: report({ byJobType: [] }) },
};

export const UsdOnly: Story = {
	args: { fx: null },
};

export const ByDay: StoryObj = {
	render: () => <LlmUsageByDayTable report={report()} fx={eur} />,
};

export const ByDayLoading: StoryObj = {
	render: () => <LlmUsageByDayTable fx={eur} />,
};

export const ByDayEmpty: StoryObj = {
	render: () => <LlmUsageByDayTable report={report({ byDay: [] })} fx={eur} />,
};
