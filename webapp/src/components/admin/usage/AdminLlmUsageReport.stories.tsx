import type { Meta, StoryObj } from "@storybook/react";
import { expect, fn } from "storybook/test";

import { PageLayout } from "@/components/layout/PageLayout";
import { withStandardPage } from "@/stories/decorators";
import { STORY_NOW } from "@/stories/story-clock";

import { AdminLlmUsageReport } from "./AdminLlmUsageReport";
import { dayOfMonth, eurRate, STORY_MONTH, usageReport, withOwnProvider } from "./story-mock-data";
import { addMonths } from "./usage-utils";

const FX_DISCLOSURE = /reference rate published on/u;
const ESTIMATE_LABEL = /^approximately /u;

const LAST_MONTH = addMonths(STORY_MONTH, -1);

const baseReport = usageReport();
const capped = withOwnProvider(baseReport);

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
		report: capped,
		month: STORY_MONTH,
		isCurrentMonth: true,
		workspaceSlug: "acme",
		now: new Date(STORY_NOW),
		onEditOwnProviderCap: fn(),
	},
} satisfies Meta<typeof AdminLlmUsageReport>;

export default meta;
type Story = StoryObj<typeof meta>;

export const BothCapsHealthy: Story = {
	play: async ({ canvas }) => {
		await expect(canvas.queryByRole("status")).toBeNull();
		await expect(canvas.queryByText(FX_DISCLOSURE)).toBeNull();
		await expect(canvas.queryAllByLabelText(ESTIMATE_LABEL)).toHaveLength(0);
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

export const ProviderUncapped: Story = {
	args: {
		report: { ...capped, ownProviderMonthlyBudgetUsd: undefined },
	},
	play: async ({ canvas }) => {
		canvas.getByText("No provider cap set · billed to you by your provider");
		await expect(canvas.queryByRole("progressbar", { name: "Your provider cap used" })).toBeNull();
	},
};

export const NoSharedBudget: Story = {
	args: {
		report: { ...capped, instanceMonthlyBudgetUsd: undefined },
	},
	play: async ({ canvas }) => {
		canvas.getByText("No shared-model budget set by your host");
		await expect(
			canvas.queryByRole("progressbar", { name: "Shared-model budget used" }),
		).toBeNull();
	},
};

export const ApproachingBothCaps: Story = {
	args: {
		report: { ...capped, instanceTotalCostUsd: 22, ownProviderTotalCostUsd: 8.4 },
	},
	play: async ({ canvas }) => {
		await expect(canvas.getAllByRole("status")).toHaveLength(2);
	},
};

/** Two days into the month is too little pace to project a month end from. */
export const ApproachingWithoutProjection: Story = {
	args: {
		now: dayOfMonth(STORY_MONTH, 2),
		report: { ...capped, ownProviderTotalCostUsd: 8.4 },
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("status").textContent).not.toMatch(/At this pace/u);
	},
};

export const ProviderCapUnenforceable: Story = {
	args: {
		report: {
			...capped,
			ownProviderBudgetVerdict: "UNVERIFIABLE",
			ownProviderPaused: true,
			unpricedEventCount: 7,
		},
	},
	play: async ({ canvas }) => {
		canvas.getByText("Your provider cap can't be enforced");
	},
};

export const SharedBudgetUnverifiable: Story = {
	args: {
		report: {
			...capped,
			instanceBudgetVerdict: "UNVERIFIABLE",
			instancePaused: true,
			unpricedEventCount: 7,
		},
	},
	play: async ({ canvas }) => {
		canvas.getByText("Shared-model spend can't be verified");
	},
};

export const BothPaused: Story = {
	args: {
		report: {
			...capped,
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

/** A $0 cap is a supported state: nothing may run, and the meter reads full. */
export const ZeroProviderCap: Story = {
	args: {
		report: {
			...capped,
			ownProviderMonthlyBudgetUsd: 0,
			ownProviderTotalCostUsd: 0,
			ownProviderBudgetVerdict: "EXHAUSTED",
			ownProviderPaused: true,
		},
	},
	play: async ({ canvas }) => {
		canvas.getByText("100% used · Paused");
	},
};

export const CallsWithNoPriceSet: Story = {
	args: {
		report: { ...capped, unpricedEventCount: 42 },
	},
	play: async ({ canvas }) => {
		canvas.getByText("42 runs aren't counted in these totals");
	},
};

export const SingleCallWithNoPriceSet: Story = {
	args: {
		report: { ...capped, unpricedEventCount: 1 },
	},
	play: async ({ canvas }) => {
		canvas.getByText("1 run isn't counted in these totals");
	},
};

export const DisplayCurrency: Story = {
	args: {
		report: { ...capped, fx: eurRate },
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText(FX_DISCLOSURE)).toBeVisible();
		// `≈` announces as "tilde operator" or is dropped, so every estimate carries a spoken label.
		await expect(canvas.getAllByLabelText(ESTIMATE_LABEL).length).toBeGreaterThan(0);
	},
};

/** A currency whose symbol is also the dollar sign has to be told apart by its code. */
export const DisplayCurrencyWithAmbiguousSymbol: Story = {
	args: {
		report: {
			...capped,
			fx: { ...eurRate, currencyCode: "CAD", ratePerUsd: 1.3642 },
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.getAllByLabelText(ESTIMATE_LABEL).length).toBeGreaterThan(0);
	},
};

export const DisplayCurrencyClosedMonth: Story = {
	args: {
		month: LAST_MONTH,
		isCurrentMonth: false,
		report: {
			...withOwnProvider(usageReport(LAST_MONTH)),
			fx: { ...eurRate, ratePerUsd: 0.874312, rateDate: dayOfMonth(LAST_MONTH, 28) },
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText(FX_DISCLOSURE)).toBeVisible();
	},
};

/** A closed month never pauses anything and offers no cap editor: caps apply from today. */
export const PastMonth: Story = {
	args: {
		month: LAST_MONTH,
		isCurrentMonth: false,
		report: {
			...withOwnProvider(usageReport(LAST_MONTH)),
			instanceTotalCostUsd: 25.0142,
			instanceBudgetVerdict: "EXHAUSTED",
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.queryByRole("button", { name: /^(?:Change|Set) cap$/u })).toBeNull();
		await expect(canvas.queryByRole("alert")).toBeNull();
		canvas.getByText(
			"A cap applies from the moment it is saved, not to the month you are reading. Step forward to this month to change it.",
		);
	},
};

export const NoDailyBreakdown: Story = {
	args: {
		report: { ...capped, byDay: [] },
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
		canvas.getByText(/^No AI usage in /u);
		canvas.getByRole("link", { name: "Open AI models" });
	},
};
