import type { Meta, StoryObj } from "@storybook/react";
import { expect, fn } from "storybook/test";

import type { WorkspaceLlmUsageReport } from "@/api/types.gen";
import { PageLayout } from "@/components/core/PageLayout";
import { withStandardPage } from "@/stories/decorators";

import { AdminLlmUsageReport } from "./AdminLlmUsageReport";

const FX_DISCLOSURE = /reference rate published on/u;

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
			instanceTotalCostUsd: 9.4919,
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
	],
	byDay: [
		{
			day: new Date("2026-07-01"),
			instanceTotalCostUsd: 6.93,
			ownProviderTotalCostUsd: 0,
			unpricedEventCount: 0,
			events: 45,
		},
		{
			day: new Date("2026-07-06"),
			instanceTotalCostUsd: 6.5521,
			ownProviderTotalCostUsd: 0,
			unpricedEventCount: 0,
			events: 60,
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
		index === 1 ? { ...row, ownProviderTotalCostUsd: 2.4 } : row,
	),
};

const meta = {
	component: AdminLlmUsageReport,
	parameters: { layout: "fullscreen" },
	decorators: [
		(Story) => (
			<PageLayout>
				<Story />
			</PageLayout>
		),
		withStandardPage,
	],
	tags: ["autodocs"],
	args: {
		report: withOwnProvider,
		month: "2026-07",
		isCurrentMonth: true,
		workspaceSlug: "acme",
		now: NOW,
		onEditOwnProviderCap: fn(),
	},
} satisfies Meta<typeof AdminLlmUsageReport>;

export default meta;
type Story = StoryObj<typeof meta>;

export const BothCapsHealthy: Story = {
	play: async ({ canvas }) => {
		await expect(canvas.queryByRole("status")).toBeNull();
		await expect(canvas.queryByText(FX_DISCLOSURE)).toBeNull();
		canvas.getByRole("button", { name: "Change cap" });
	},
};

export const NoProviderConnected: Story = {
	args: { report: baseReport },
	play: async ({ canvas }) => {
		canvas.getByText("No provider cap set · nothing has run on a provider of your own");
		canvas.getByRole("button", { name: "Set cap" });
	},
};

export const ApproachingBothCaps: Story = {
	args: {
		report: { ...withOwnProvider, instanceTotalCostUsd: 22, ownProviderTotalCostUsd: 8.4 },
	},
	play: async ({ canvas }) => {
		await expect(canvas.getAllByRole("status")).toHaveLength(2);
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

export const CallsWithNoPriceSet: Story = {
	args: {
		report: { ...withOwnProvider, unpricedEventCount: 42 },
	},
	play: async ({ canvas }) => {
		canvas.getByText("42 runs aren't counted in these totals");
	},
};

export const SingleCallWithNoPriceSet: Story = {
	args: {
		report: { ...withOwnProvider, unpricedEventCount: 1 },
	},
	play: async ({ canvas }) => {
		canvas.getByText("1 run isn't counted in these totals");
	},
};

export const DisplayCurrency: Story = {
	args: {
		report: {
			...withOwnProvider,
			fx: {
				currencyCode: "EUR",
				ratePerUsd: 0.878966,
				rateDate: new Date("2026-07-24T00:00:00.000Z"),
				source: "ECB",
			},
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText(FX_DISCLOSURE)).toBeVisible();
	},
};

export const PastMonth: Story = {
	args: {
		month: "2026-06",
		isCurrentMonth: false,
		report: { ...withOwnProvider, month: "2026-06" },
	},
	play: async ({ canvas }) => {
		await expect(canvas.queryByRole("button", { name: /^(?:Change|Set) cap$/u })).toBeNull();
	},
};

export const NoDailyBreakdown: Story = {
	args: {
		report: { ...withOwnProvider, byDay: [] },
	},
	play: async ({ canvas }) => {
		canvas.getByText("No daily breakdown yet");
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
	play: async ({ canvas }) => {
		canvas.getByText("No AI usage in July 2026");
		canvas.getByRole("link", { name: "Open AI models" });
	},
};
