import type { Meta, StoryObj } from "@storybook/react";
import { expect, fn } from "storybook/test";

import type { FxRateInfo, WorkspaceLlmUsageReport } from "@/api/types.gen";
import { withStandardPage } from "@/stories/decorators";
import { expectNoPageOverflow, expectTablesScrollInPlace } from "@/test/reflow";

import { AdminLlmUsagePage } from "./AdminLlmUsagePage";

const FX_DISCLOSURE = /reference rate published on/u;
const ESTIMATE_LABEL = /^approximately /u;

const NOW = new Date("2026-07-10T12:00:00.000Z");

const baseReport: WorkspaceLlmUsageReport = {
	month: "2026-07",
	instanceMonthlyBudgetUsd: 25,
	ownProviderMonthlyBudgetUsd: undefined,
	instanceTotalCostUsd: 13.4821,
	ownProviderTotalCostUsd: 0,
	instanceBudgetVerdict: "WITHIN",
	ownProviderBudgetVerdict: "WITHIN",
	instancePaused: false,
	ownProviderPaused: false,
	unpricedEventCount: 0,
	byJobType: [
		{
			jobType: "PULL_REQUEST_REVIEW",
			instanceTotalCostUsd: 8.1034,
			ownProviderTotalCostUsd: 0,
			unpricedEventCount: 0,
			inputTokens: 1_204_331,
			outputTokens: 88_412,
			cacheReadTokens: 640_112,
			cacheWriteTokens: 120_034,
			totalCalls: 312,
			events: 41,
		},
		{
			jobType: "MENTOR_TURN",
			instanceTotalCostUsd: 3.9902,
			ownProviderTotalCostUsd: 0,
			unpricedEventCount: 0,
			inputTokens: 402_118,
			outputTokens: 61_240,
			cacheReadTokens: 210_400,
			cacheWriteTokens: 44_020,
			totalCalls: 128,
			events: 64,
		},
		{
			jobType: "ISSUE_REVIEW",
			instanceTotalCostUsd: 1.3885,
			ownProviderTotalCostUsd: 0,
			unpricedEventCount: 0,
			inputTokens: 150_221,
			outputTokens: 20_114,
			cacheReadTokens: 80_010,
			cacheWriteTokens: 12_450,
			totalCalls: 54,
			events: 12,
		},
	],
	byDay: [
		{
			day: new Date("2026-07-01"),
			instanceTotalCostUsd: 2.1,
			ownProviderTotalCostUsd: 0,
			unpricedEventCount: 0,
			events: 14,
		},
		{
			day: new Date("2026-07-02"),
			instanceTotalCostUsd: 4.83,
			ownProviderTotalCostUsd: 0,
			unpricedEventCount: 0,
			events: 31,
		},
		{
			day: new Date("2026-07-03"),
			instanceTotalCostUsd: 0.92,
			ownProviderTotalCostUsd: 0,
			unpricedEventCount: 0,
			events: 6,
		},
		{
			day: new Date("2026-07-06"),
			instanceTotalCostUsd: 5.6321,
			ownProviderTotalCostUsd: 0,
			unpricedEventCount: 0,
			events: 66,
		},
	],
};

const withOwnProvider: WorkspaceLlmUsageReport = {
	...baseReport,
	ownProviderMonthlyBudgetUsd: 10,
	ownProviderTotalCostUsd: 2.4,
	byJobType: baseReport.byJobType.map((row, index) =>
		index === 1 ? { ...row, ownProviderTotalCostUsd: 2.4 } : row,
	),
	byDay: baseReport.byDay.map((row, index) =>
		index === 3 ? { ...row, ownProviderTotalCostUsd: 2.4 } : row,
	),
};

const eurToday: FxRateInfo = {
	currencyCode: "EUR",
	ratePerUsd: 0.878966,
	rateDate: new Date("2026-07-24T00:00:00.000Z"),
	source: "ECB",
};

const meta = {
	component: AdminLlmUsagePage,
	parameters: { layout: "fullscreen" },
	decorators: [withStandardPage],
	tags: ["autodocs"],
	args: {
		month: "2026-07",
		isCurrentMonth: true,
		canGoNext: false,
		workspaceSlug: "acme",
		report: baseReport,
		isLoading: false,
		error: null,
		now: NOW,
		onRetry: fn(),
		onEditOwnProviderCap: fn(),
	},
} satisfies Meta<typeof AdminLlmUsagePage>;

export default meta;
type Story = StoryObj<typeof meta>;

export const WithinBudgetInUsd: Story = {
	play: async ({ canvas }) => {
		await expect(canvas.queryByText(FX_DISCLOSURE)).toBeNull();
		await expect(canvas.queryAllByLabelText(ESTIMATE_LABEL)).toHaveLength(0);
	},
};

export const DisplayCurrencyThisMonth: Story = {
	args: {
		report: { ...withOwnProvider, fx: eurToday },
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText(FX_DISCLOSURE)).toBeVisible();
		// `≈` announces as "tilde operator" or is dropped, so every estimate carries a spoken label.
		await expect(canvas.getAllByLabelText(ESTIMATE_LABEL).length).toBeGreaterThan(0);
	},
};

export const DisplayCurrencyClosedMonth: Story = {
	args: {
		month: "2026-06",
		isCurrentMonth: false,
		canGoNext: true,
		report: {
			...withOwnProvider,
			month: "2026-06",
			fx: {
				currencyCode: "EUR",
				ratePerUsd: 0.874312,
				rateDate: new Date("2026-06-30T00:00:00.000Z"),
				source: "ECB",
			},
		},
	},
};

export const DisplayCurrencyWithAmbiguousSymbol: Story = {
	args: {
		report: {
			...withOwnProvider,
			fx: {
				currencyCode: "CAD",
				ratePerUsd: 1.3642,
				rateDate: new Date("2026-07-24T00:00:00.000Z"),
				source: "ECB",
			},
		},
	},
};

export const BothCapsHealthy: Story = {
	args: { report: withOwnProvider },
};

export const ApproachingProviderCap: Story = {
	args: {
		report: { ...withOwnProvider, ownProviderTotalCostUsd: 8.4 },
	},
};

export const ApproachingSharedBudget: Story = {
	args: {
		report: { ...withOwnProvider, instanceTotalCostUsd: 22 },
	},
};

export const ApproachingWithoutProjection: Story = {
	args: {
		now: new Date("2026-07-02T12:00:00.000Z"),
		report: { ...withOwnProvider, ownProviderTotalCostUsd: 8.4 },
	},
};

export const ProviderCapReached: Story = {
	args: {
		report: {
			...withOwnProvider,
			ownProviderTotalCostUsd: 10.12,
			ownProviderBudgetVerdict: "EXHAUSTED",
			ownProviderPaused: true,
		},
	},
};

export const ProviderCapUnenforceable: Story = {
	args: {
		report: {
			...withOwnProvider,
			ownProviderBudgetVerdict: "UNVERIFIABLE",
			ownProviderPaused: true,
			unpricedEventCount: 7,
		},
	},
};

export const SharedBudgetReached: Story = {
	args: {
		report: {
			...withOwnProvider,
			instanceTotalCostUsd: 25.0142,
			instanceBudgetVerdict: "EXHAUSTED",
			instancePaused: true,
		},
	},
};

export const SharedBudgetUnverifiable: Story = {
	args: {
		report: {
			...withOwnProvider,
			instanceBudgetVerdict: "UNVERIFIABLE",
			instancePaused: true,
			unpricedEventCount: 7,
		},
	},
};

export const BothPaused: Story = {
	args: {
		report: {
			...withOwnProvider,
			instanceTotalCostUsd: 25.0142,
			ownProviderTotalCostUsd: 10.12,
			instanceBudgetVerdict: "EXHAUSTED",
			instancePaused: true,
			ownProviderBudgetVerdict: "EXHAUSTED",
			ownProviderPaused: true,
		},
	},
	play: async ({ canvas }) => {
		const own = await canvas.findByText("Your provider cap is reached");
		const shared = canvas.getByText("Shared-model budget reached");
		await expect(own.getBoundingClientRect().top).toBeLessThan(shared.getBoundingClientRect().top);
	},
};

export const NoProviderConnected: Story = {
	args: { report: baseReport },
};

export const ProviderUncapped: Story = {
	args: {
		report: { ...withOwnProvider, ownProviderMonthlyBudgetUsd: undefined },
	},
};

export const ZeroProviderCap: Story = {
	args: {
		report: {
			...withOwnProvider,
			ownProviderMonthlyBudgetUsd: 0,
			ownProviderTotalCostUsd: 0,
			ownProviderBudgetVerdict: "EXHAUSTED",
			ownProviderPaused: true,
		},
	},
};

export const NoSharedBudget: Story = {
	args: {
		report: { ...withOwnProvider, instanceMonthlyBudgetUsd: undefined },
	},
};

export const PastMonth: Story = {
	args: {
		month: "2026-06",
		isCurrentMonth: false,
		canGoNext: true,
		report: {
			...withOwnProvider,
			month: "2026-06",
			instanceTotalCostUsd: 25.0142,
			instanceBudgetVerdict: "EXHAUSTED",
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.queryByRole("button", { name: /^(?:Change|Set) cap$/u })).toBeNull();
		canvas.getByText(
			"A cap applies from the moment it is saved, not to the month you are reading. Step forward to this month to change it.",
		);
	},
};

export const Empty: Story = {
	args: {
		report: {
			...baseReport,
			instanceTotalCostUsd: 0,
			ownProviderTotalCostUsd: 0,
			byJobType: [],
			byDay: [],
		},
	},
};

export const CallsWithNoPriceSet: Story = {
	args: {
		report: { ...withOwnProvider, unpricedEventCount: 42 },
	},
};

export const SingleCallWithNoPriceSet: Story = {
	args: {
		report: { ...withOwnProvider, unpricedEventCount: 1 },
	},
};

export const Loading: Story = {
	args: {
		report: undefined,
		isLoading: true,
	},
};

export const RetryableServerError: Story = {
	args: {
		report: undefined,
		error: { status: 500, detail: "Couldn't build the usage report." },
	},
};

export const ForbiddenError: Story = {
	args: {
		report: undefined,
		error: { status: 403, detail: "Workspace admin access is required." },
	},
};

export const MobileReflow: Story = {
	args: {
		report: {
			...withOwnProvider,
			instanceTotalCostUsd: 25.0142,
			ownProviderTotalCostUsd: 10.12,
			instanceBudgetVerdict: "EXHAUSTED",
			instancePaused: true,
			ownProviderBudgetVerdict: "EXHAUSTED",
			ownProviderPaused: true,
		},
	},
	parameters: {
		layout: "fullscreen",
		viewport: { defaultViewport: "reflow" },
		chromatic: { viewports: [320, 375, 768, 1024] },
	},
	play: async ({ canvasElement }) => {
		await expectNoPageOverflow();
		await expectTablesScrollInPlace(canvasElement);
	},
};
