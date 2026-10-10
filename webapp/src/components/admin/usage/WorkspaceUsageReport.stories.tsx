import type { Meta, StoryContext, StoryObj } from "@storybook/react";
import { expect, fn, within } from "storybook/test";

import { PageLayout } from "@/components/layout/PageLayout";
import { withStandardPage } from "@/stories/decorators";
import { STORY_NOW } from "@/stories/story-clock";
import { precedes } from "@/test/dom";

import {
	dayOfMonth,
	eurRate,
	STORY_MONTH,
	usageReport,
	withMonthTotals,
	withOwnProvider,
	withPrecomputeUsage,
} from "./fixtures";
import { addMonths, formatMonthLabel } from "./usage-utils";
import { WorkspaceUsageReport } from "./WorkspaceUsageReport";

const FX_DISCLOSURE = /reference rate published on/u;
const ESTIMATE_LABEL = /^about /u;

const LAST_MONTH = addMonths(STORY_MONTH, -1);

const baseReport = usageReport();
const capped = withOwnProvider(baseReport);

const meta = {
	component: WorkspaceUsageReport,
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
} satisfies Meta<typeof WorkspaceUsageReport>;

export default meta;
type Story = StoryObj<typeof meta>;

/** The purse's tile, found by its name, once the preview has mounted the report. */
async function tileOf(
	canvas: StoryContext["canvas"],
	purse: "Shared models" | "Own provider",
): Promise<HTMLElement> {
	const spend = await canvas.findByRole("region", { name: /^Spend /u });
	const title = within(spend).getByText(purse);
	const tile = title.closest("[data-slot='card']");
	if (!(tile instanceof HTMLElement)) {
		throw new Error(`No tile is titled ${purse}.`);
	}
	return tile;
}

/**
 * Two equal tiles, one per purse, each against its own limit. The cap editor belongs to the section,
 * so neither tile is taller for holding it; with room left on both limits, no state is badged.
 */
export const BothCapsHealthy: Story = {
	play: async ({ canvas }) => {
		await canvas.findByRole("region", { name: "Spend this month" });
		await expect(canvas.queryByRole("status")).toBeNull();
		await expect(canvas.queryByText(FX_DISCLOSURE)).toBeNull();
		await expect(canvas.queryAllByLabelText(ESTIMATE_LABEL)).toHaveLength(0);
		const spend = within(canvas.getByRole("region", { name: "Spend this month" }));
		// The section's one action names the purse it changes: only the own provider's cap is the
		// workspace's to set.
		spend.getByRole("button", { name: "Change provider cap" });

		const shared = within(await tileOf(canvas, "Shared models"));
		await expect(shared.getByText("$13.48")).toBeVisible();
		await expect(shared.getByText("of $25")).toBeVisible();
		await expect(shared.getByText("Set by an instance admin")).toBeVisible();
		await expect(shared.getByText("54% used")).toBeVisible();
		const own = within(await tileOf(canvas, "Own provider"));
		await expect(own.getByText("of $10")).toBeVisible();
		await expect(own.getByText("Billed to you by your provider")).toBeVisible();
		await expect(canvas.queryByText(/^Near the /u)).toBeNull();
		const sharedTile = await tileOf(canvas, "Shared models");
		const ownTile = await tileOf(canvas, "Own provider");
		await expect(sharedTile.getBoundingClientRect().height).toBe(
			ownTile.getBoundingClientRect().height,
		);
	},
};

const PRECOMPUTE_CARD = "Precompute models by practice";

/** Below *By run type*, because its spend is a split of the review run types, not an addition. */
export const PrecomputeModelsByPractice: Story = {
	args: { report: withPrecomputeUsage(capped) },
	play: async ({ canvas }) => {
		const title = await canvas.findByRole("heading", { name: PRECOMPUTE_CARD });
		const runTypes = canvas.getByRole("heading", { name: "By run type" });
		await expect(precedes(runTypes, title)).toBe(true);
		const link = canvas.getByRole("link", { name: "Comments explain why" });
		await expect(link.getAttribute("href")).toMatch(/^\/w\/acme\/admin\/practices\?/u);
		canvas.getByText(
			"Decision, embedding and reranking calls from finished reviews, to date. Chat calls from scripts are in each review’s cost. A review that ran several scripts counts once in the total.",
		);
	},
};

/** A month in which no script called a model has no card, not an empty one. */
export const NoPrecomputeCalls: Story = {
	play: async ({ canvas }) => {
		await expect(await canvas.findByText("By run type")).toBeVisible();
		await expect(canvas.queryByText(PRECOMPUTE_CARD)).toBeNull();
	},
};

/**
 * The workspace has no provider of its own in use: its tile says where one is connected, no table
 * has its columns, and no cap is offered for a purse that is not in use.
 */
export const NoProviderConnected: Story = {
	args: { report: baseReport },
	play: async ({ canvas }) => {
		const own = within(await tileOf(canvas, "Own provider"));
		own.getByText(/^Work on a provider you connect in/u);
		await expect(canvas.queryByRole("button", { name: /cap/u })).toBeNull();
		const runTypes = within(canvas.getByRole("table", { name: "AI spend by run type" }));
		await expect(runTypes.queryByRole("columnheader", { name: "Own provider" })).toBeNull();
	},
};

export const ProviderUncapped: Story = {
	args: {
		report: { ...capped, ownProviderMonthlyBudgetUsd: undefined },
	},
	play: async ({ canvas }) => {
		const own = within(await tileOf(canvas, "Own provider"));
		own.getByText("No cap set");
		own.getByText("Billed to you by your provider");
		await expect(canvas.queryByRole("progressbar", { name: "Your provider cap used" })).toBeNull();
		canvas.getByRole("button", { name: "Set provider cap" });
	},
};

/**
 * A provider connected with a model turned on, before its first call: the purse is in use, so its
 * columns read $0.00 and its cap can be set before any spend.
 */
export const ConnectedBeforeFirstSpend: Story = {
	args: { report: { ...baseReport, ownProviderInUse: true } },
	play: async ({ canvas }) => {
		const own = within(await tileOf(canvas, "Own provider"));
		await expect(own.getByText("$0.00")).toBeVisible();
		own.getByText("No cap set");
		await expect(canvas.getByRole("button", { name: "Set provider cap" })).toBeVisible();
		const runTypes = within(canvas.getByRole("table", { name: "AI spend by run type" }));
		await expect(runTypes.getByRole("columnheader", { name: "Own provider" })).toBeVisible();
	},
};

export const NoSharedBudget: Story = {
	args: {
		report: { ...capped, instanceMonthlyBudgetUsd: undefined },
	},
	play: async ({ canvas }) => {
		const shared = within(await tileOf(canvas, "Shared models"));
		shared.getByText("No budget set");
		await expect(shared.queryByText("Set by an instance admin")).toBeNull();
		await expect(
			canvas.queryByRole("progressbar", { name: "Shared-model budget used" }),
		).toBeNull();
	},
};

/** Each tile badges its own limit in its own word; the pace alerts above say when each is reached. */
export const ApproachingBothCaps: Story = {
	args: {
		report: withMonthTotals(capped, { instanceTotalCostUsd: 22, ownProviderTotalCostUsd: 8.4 }),
	},
	play: async ({ canvas }) => {
		await expect(await canvas.findAllByRole("status")).toHaveLength(2);
		await expect(
			within(await tileOf(canvas, "Shared models")).getByText("Near the budget"),
		).toBeVisible();
		await expect(
			within(await tileOf(canvas, "Own provider")).getByText("Near the cap"),
		).toBeVisible();
		await expect(within(await tileOf(canvas, "Own provider")).getByText("84% used")).toBeVisible();
	},
};

/** Two days into the month is too little pace to project a month end from. */
export const ApproachingWithoutProjection: Story = {
	args: {
		now: dayOfMonth(STORY_MONTH, 2),
		report: withMonthTotals(capped, { ownProviderTotalCostUsd: 8.4 }),
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("status").textContent).not.toMatch(/At this pace/u);
	},
};

export const ProviderCapUnenforceable: Story = {
	args: {
		report: {
			...withMonthTotals(capped, { unpricedEventCount: 7 }),
			ownProviderBudgetVerdict: "UNVERIFIABLE",
			ownProviderPaused: true,
		},
	},
	play: async ({ canvas }) => {
		canvas.getByText("Your provider cap cannot be enforced");
		const own = within(await tileOf(canvas, "Own provider"));
		own.getByText("Paused");
		own.getByText("No price set");
	},
};

export const SharedBudgetUnverifiable: Story = {
	args: {
		report: {
			...withMonthTotals(capped, { unpricedEventCount: 7 }),
			instanceBudgetVerdict: "UNVERIFIABLE",
			instancePaused: true,
		},
	},
	play: async ({ canvas }) => {
		canvas.getByText("Shared-model spend cannot be verified");
	},
};

export const BothPaused: Story = {
	args: {
		report: {
			...withMonthTotals(capped, { instanceTotalCostUsd: 25.0142, ownProviderTotalCostUsd: 10.12 }),
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
		await expect(within(await tileOf(canvas, "Shared models")).getByText("Paused")).toBeVisible();
		await expect(within(await tileOf(canvas, "Own provider")).getByText("Paused")).toBeVisible();
	},
};

/** A $0 cap is a supported state: nothing may run, and the meter reads full. */
export const ZeroProviderCap: Story = {
	args: {
		report: {
			...withMonthTotals(capped, { ownProviderTotalCostUsd: 0 }),
			ownProviderMonthlyBudgetUsd: 0,
			ownProviderBudgetVerdict: "EXHAUSTED",
			ownProviderPaused: true,
		},
	},
	play: async ({ canvas }) => {
		const own = within(await tileOf(canvas, "Own provider"));
		own.getByText("100% used");
		own.getByText("Paused");
	},
};

/**
 * A $0 cap on a provider that is not in use holds nothing back, so the page names no cap state. It
 * only says where a provider is connected.
 */
export const ZeroProviderCapNotInUse: Story = {
	args: {
		report: {
			...baseReport,
			ownProviderMonthlyBudgetUsd: 0,
			ownProviderBudgetVerdict: "EXHAUSTED",
			ownProviderPaused: true,
		},
	},
	play: async ({ canvas }) => {
		const own = within(await tileOf(canvas, "Own provider"));
		own.getByText(/^Work on a provider you connect in/u);
		await expect(canvas.queryByText("Your provider cap is reached")).toBeNull();
		await expect(canvas.queryByText("Paused")).toBeNull();
		await expect(canvas.queryByText(/^Near the /u)).toBeNull();
		await expect(canvas.queryByRole("alert")).toBeNull();
		await expect(canvas.queryByRole("status")).toBeNull();
	},
};

export const RunsWithNoPriceSet: Story = {
	args: {
		report: withMonthTotals(capped, { unpricedEventCount: 42 }),
	},
	play: async ({ canvas }) => {
		canvas.getByText("42 runs have no price");
	},
};

export const SingleRunWithNoPriceSet: Story = {
	args: {
		report: withMonthTotals(capped, { unpricedEventCount: 1 }),
	},
	play: async ({ canvas }) => {
		canvas.getByText("1 run has no price");
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
			...withMonthTotals(withOwnProvider(usageReport(LAST_MONTH)), {
				instanceTotalCostUsd: 25.0142,
			}),
			instanceBudgetVerdict: "EXHAUSTED",
		},
	},
	play: async ({ canvas }) => {
		await canvas.findByRole("region", { name: `Spend in ${formatMonthLabel(LAST_MONTH)}` });
		await expect(canvas.queryByText("Paused")).toBeNull();
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

/**
 * The limit states, meters and muted headers keep their meaning on the dark theme: the shared-model
 * budget is reached, and the provider cap is paused because some calls have no price.
 */
export const Dark: Story = {
	args: {
		report: {
			...withMonthTotals(withPrecomputeUsage(capped), {
				instanceTotalCostUsd: 25.0142,
				unpricedEventCount: 7,
			}),
			instanceBudgetVerdict: "EXHAUSTED",
			instancePaused: true,
			ownProviderBudgetVerdict: "UNVERIFIABLE",
			ownProviderPaused: true,
		},
	},
	globals: { theme: "dark" },
	play: async ({ canvas }) => {
		await expect(await canvas.findByRole("heading", { name: PRECOMPUTE_CARD })).toBeVisible();
		await expect(
			canvas.getByRole("progressbar", { name: "Shared-model budget used" }),
		).toBeVisible();
		await expect(within(await tileOf(canvas, "Shared models")).getByText("Paused")).toBeVisible();
		const own = within(await tileOf(canvas, "Own provider"));
		await expect(own.getByText("Paused")).toBeVisible();
		await expect(own.getByText("No price set")).toBeVisible();
	},
};
