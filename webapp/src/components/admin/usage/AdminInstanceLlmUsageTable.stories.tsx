import type { Meta, StoryContext, StoryObj } from "@storybook/react";
import { expect, fn, within } from "storybook/test";

import type { AdminWorkspaceLlmUsage, WorkspaceLlmUsageReport } from "@/api/types.gen";
import { expectTargetSize, horizontalScrollParentOf } from "@/test/reflow";

import { AdminInstanceLlmUsageTable } from "./AdminInstanceLlmUsageTable";

const FX_DISCLOSURE = /reference rate published on/u;

async function expandedPanelFor(
	canvas: StoryContext["canvas"],
	displayName: string,
): Promise<HTMLElement> {
	const toggle = await canvas.findByRole("button", {
		name: new RegExp(`hide usage details for ${displayName}`, "iu"),
	});
	const panelId = toggle.getAttribute("aria-controls");
	const panel = panelId == null ? null : document.getElementById(panelId);
	if (panel == null) {
		throw new Error(`The expand toggle for ${displayName} points at no panel.`);
	}
	return panel;
}

const pausedOnSharedBudget: AdminWorkspaceLlmUsage = {
	workspaceSlug: "example-workspace",
	displayName: "Example Workspace",
	instanceMonthlyBudgetUsd: 25,
	instanceTotalCostUsd: 25.0142,
	instanceBudgetVerdict: "EXHAUSTED",
	instancePaused: true,
	ownProviderMonthlyBudgetUsd: 40,
	ownProviderTotalCostUsd: 6.5,
	ownProviderBudgetVerdict: "WITHIN",
	ownProviderPaused: false,
	events: 118,
};

const nearingItsOwnProviderCap: AdminWorkspaceLlmUsage = {
	workspaceSlug: "hephaestus-dev",
	displayName: "Hephaestus Dev",
	instanceMonthlyBudgetUsd: 100,
	instanceTotalCostUsd: 13.4821,
	instanceBudgetVerdict: "WITHIN",
	instancePaused: false,
	ownProviderMonthlyBudgetUsd: 25,
	ownProviderTotalCostUsd: 21.4,
	ownProviderBudgetVerdict: "WITHIN",
	ownProviderPaused: false,
	events: 74,
};

const sharedBudgetOnlyUnverifiable: AdminWorkspaceLlmUsage = {
	workspaceSlug: "launchpad",
	displayName: "Launchpad",
	instanceMonthlyBudgetUsd: 50,
	instanceTotalCostUsd: 38.2,
	instanceBudgetVerdict: "UNVERIFIABLE",
	instancePaused: false,
	ownProviderTotalCostUsd: 0,
	events: 22,
	ownProviderBudgetVerdict: "WITHIN",
	ownProviderPaused: false,
};

const uncapped: AdminWorkspaceLlmUsage = {
	workspaceSlug: "sandbox",
	displayName: "Sandbox",
	instanceTotalCostUsd: 0.42,
	instanceBudgetVerdict: "WITHIN",
	ownProviderTotalCostUsd: 0,
	events: 3,
	ownProviderBudgetVerdict: "WITHIN",
	ownProviderPaused: false,
	instancePaused: false,
};

const pausedOnItsOwnProviderCap: AdminWorkspaceLlmUsage = {
	workspaceSlug: "atelier",
	displayName: "Atelier",
	instanceTotalCostUsd: 0,
	instanceBudgetVerdict: "WITHIN",
	ownProviderMonthlyBudgetUsd: 12,
	ownProviderTotalCostUsd: 12.4,
	ownProviderBudgetVerdict: "EXHAUSTED",
	ownProviderPaused: true,
	events: 40,
	instancePaused: false,
};

const rows: AdminWorkspaceLlmUsage[] = [
	pausedOnSharedBudget,
	nearingItsOwnProviderCap,
	sharedBudgetOnlyUnverifiable,
	uncapped,
	pausedOnItsOwnProviderCap,
];

const detailReport: WorkspaceLlmUsageReport = {
	month: "2026-07",
	instanceMonthlyBudgetUsd: 25,
	instanceTotalCostUsd: 25.0142,
	instanceBudgetVerdict: "EXHAUSTED",
	instancePaused: true,
	ownProviderMonthlyBudgetUsd: 40,
	ownProviderTotalCostUsd: 1.5,
	ownProviderBudgetVerdict: "WITHIN",
	ownProviderPaused: false,
	unpricedEventCount: 0,
	byJobType: [
		{
			jobType: "PULL_REQUEST_REVIEW",
			instanceTotalCostUsd: 25.0142,
			ownProviderTotalCostUsd: 1.5,
			unpricedEventCount: 0,
			inputTokens: 80_000,
			outputTokens: 12_000,
			cacheReadTokens: 10_000,
			cacheWriteTokens: 2000,
			totalCalls: 42,
			events: 18,
		},
	],
	byDay: [
		{
			day: new Date("2026-07-05T00:00:00.000Z"),
			instanceTotalCostUsd: 25.0142,
			ownProviderTotalCostUsd: 1.5,
			unpricedEventCount: 0,
			events: 18,
		},
	],
};

/**
 * Every workspace's AI spend for one month, against both caps: the shared-model budget the host
 * grants — editable here — and the workspace's own provider cap, read-only because it is their money.
 */
const meta = {
	component: AdminInstanceLlmUsageTable,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	args: {
		rows,
		month: "2026-07",
		now: new Date("2026-07-10T12:00:00.000Z"),
		isCurrentMonth: true,
		isLoading: false,
		error: null,
		onRetry: fn(),
		fx: undefined,
		expandedWorkspaceSlug: null,
		isDetailLoading: false,
		detailError: null,
		onRetryDetail: fn(),
		onToggleDetails: fn(),
		onEditSharedModelBudget: fn(),
	},
} satisfies Meta<typeof AdminInstanceLlmUsageTable>;

export default meta;
type Story = StoryObj<typeof meta>;

export const AllCapCombinationsInUsd: Story = {
	play: async ({ canvas }) => {
		await expect(canvas.queryByText(FX_DISCLOSURE)).toBeNull();
	},
};

export const Expanded: Story = {
	args: {
		expandedWorkspaceSlug: pausedOnSharedBudget.workspaceSlug,
		detailReport,
	},
};

export const ExpandedNearCap: Story = {
	args: {
		expandedWorkspaceSlug: nearingItsOwnProviderCap.workspaceSlug,
		detailReport: {
			...detailReport,
			instanceMonthlyBudgetUsd: 50,
			instanceTotalCostUsd: 42,
			instanceBudgetVerdict: "WITHIN",
			instancePaused: false,
		},
	},
};

export const DisplayCurrencyThisMonth: Story = {
	args: {
		fx: {
			currencyCode: "EUR",
			ratePerUsd: 0.878966,
			rateDate: new Date("2026-07-24T00:00:00.000Z"),
			source: "ECB",
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText(FX_DISCLOSURE)).toBeVisible();
		canvas.getByLabelText("approximately 21.99 euros");
	},
};

export const DisplayCurrencyClosedMonth: Story = {
	args: {
		isCurrentMonth: false,
		fx: {
			currencyCode: "EUR",
			ratePerUsd: 0.874312,
			rateDate: new Date("2026-06-30T00:00:00.000Z"),
			source: "ECB",
		},
	},
};

/**
 * WCAG 2.2 SC 1.4.10: the rollup takes the data-table exception and scrolls sideways, but the
 * breakdown must not nest inside that scroller — two scrollers to read one number is
 * two-dimensional scrolling.
 */
export const ExpandedMobileReflow: Story = {
	args: {
		expandedWorkspaceSlug: pausedOnSharedBudget.workspaceSlug,
		detailReport,
	},
	parameters: {
		viewport: { defaultViewport: "reflow" },
		chromatic: { viewports: [320, 375, 1024] },
	},
	play: async ({ canvas, canvasElement }) => {
		const panel = await expandedPanelFor(canvas, pausedOnSharedBudget.displayName);
		const rollupScroller = horizontalScrollParentOf(
			canvas.getByRole("table", { name: /Per-workspace AI spend/u }),
		);

		await expect(rollupScroller.contains(panel)).toBe(false);
		await expect(panel.scrollWidth).toBeLessThanOrEqual(canvasElement.clientWidth + 1);
	},
};

/**
 * WCAG 2.2 SC 2.5.8: a header line box leaves these triggers under 24 px, and the Spacing exception
 * would rest on column widths nothing here controls — so they carry their own target size.
 */
export const HelpHeaderTargetSize: Story = {
	play: async ({ canvas }) => {
		for (const name of ["Shared-model budget", "Provider cap"]) {
			const header = canvas.getByRole("columnheader", { name });
			await expectTargetSize(within(header).getByRole("button"));
		}
	},
};

/** The state a binary in-budget pill could never show, and the one an admin can still act on. */
export const NearCap: Story = {
	args: {
		rows: [
			{
				workspaceSlug: "close-call",
				displayName: "Close Call",
				instanceMonthlyBudgetUsd: 50,
				instanceTotalCostUsd: 41,
				instanceBudgetVerdict: "WITHIN",
				instancePaused: false,
				ownProviderMonthlyBudgetUsd: 30,
				ownProviderTotalCostUsd: 27.6,
				ownProviderBudgetVerdict: "WITHIN",
				ownProviderPaused: false,
				events: 210,
			},
		],
	},
};

export const PausedByInstanceCap: Story = {
	args: { rows: [pausedOnSharedBudget] },
};

export const PausedByProviderCap: Story = {
	args: { rows: [pausedOnItsOwnProviderCap] },
};

export const PausedByBothCaps: Story = {
	args: {
		rows: [
			{
				workspaceSlug: "full-stop",
				displayName: "Full Stop",
				instanceMonthlyBudgetUsd: 20,
				instanceTotalCostUsd: 20.5,
				instanceBudgetVerdict: "EXHAUSTED",
				instancePaused: true,
				ownProviderMonthlyBudgetUsd: 15,
				ownProviderTotalCostUsd: 15,
				ownProviderBudgetVerdict: "EXHAUSTED",
				ownProviderPaused: true,
				events: 96,
			},
		],
	},
};

/** A $0 budget is a supported state: 100% used and paused immediately, not "no budget". */
export const ZeroInstanceCap: Story = {
	args: {
		rows: [
			{
				workspaceSlug: "frozen",
				displayName: "Frozen",
				instanceMonthlyBudgetUsd: 0,
				instanceTotalCostUsd: 0,
				instanceBudgetVerdict: "EXHAUSTED",
				instancePaused: true,
				ownProviderTotalCostUsd: 0,
				ownProviderBudgetVerdict: "WITHIN",
				ownProviderPaused: false,
				events: 0,
			},
		],
	},
};

export const NoProviderCapsSet: Story = {
	args: {
		rows: rows.map((row) => ({
			...row,
			ownProviderMonthlyBudgetUsd: undefined,
			ownProviderBudgetVerdict: "WITHIN" as const,
			ownProviderPaused: false,
		})),
	},
};

/**
 * Verdicts are computed from the workspace's *current* caps, so a finished month can only show a
 * neutral dash — and the budget editor goes with them, since saving one from here would change what
 * runs today.
 */
export const PastMonth: Story = {
	args: { isCurrentMonth: false },
	play: async ({ canvas }) => {
		await expect(canvas.queryByRole("button", { name: /^Set budget for/u })).toBeNull();
		await expect(canvas.getAllByRole("button", { name: /^View usage details for/u })).toHaveLength(
			rows.length,
		);
	},
};

export const Empty: Story = {
	args: { rows: [] },
};

/** The rate belongs to the month, so it survives a month with no rows to read it off. */
export const EmptyOnDisplayCurrencyInstance: Story = {
	args: {
		rows: [],
		fx: {
			currencyCode: "EUR",
			ratePerUsd: 0.878966,
			rateDate: new Date("2026-07-24T00:00:00.000Z"),
			source: "ECB",
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText("No workspaces on this instance yet")).toBeVisible();
		await expect(canvas.queryByText(FX_DISCLOSURE)).toBeNull();
	},
};

export const Loading: Story = {
	args: { rows: [], isLoading: true },
};

/** A 5xx is retryable, so the alert offers a Retry; the 403 story is the contrast. */
export const RetryableServerError: Story = {
	args: {
		rows: [],
		error: { status: 500, detail: "Couldn't roll up AI usage." },
	},
};

export const ForbiddenError: Story = {
	args: {
		rows: [],
		error: { status: 403, detail: "Instance admin access is required." },
	},
};
