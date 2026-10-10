import type { Meta, StoryContext, StoryObj } from "@storybook/react";
import { expect, fn, within } from "storybook/test";

import type { AdminWorkspaceLlmUsage, WorkspaceLlmUsageReport } from "@/api/types.gen";
import { expectTargetSize, horizontalScrollParentOf } from "@/stories/reflow";
import { StatefulPatch } from "@/stories/stateful";

import { AdminInstanceLlmUsageTable } from "./AdminInstanceLlmUsageTable";
import { commentQualityUsage, NO_PRECOMPUTE_USAGE, notAttributedUsage } from "./fixtures";

const FX_DISCLOSURE = /reference rate published on/u;

async function expandedPanelFor(
	canvas: StoryContext["canvas"],
	displayName: string,
): Promise<HTMLElement> {
	const toggle = await canvas.findByRole("button", {
		name: new RegExp(`^details for ${displayName}`, "iu"),
		expanded: true,
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
	ownProviderInUse: true,
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
	ownProviderInUse: true,
	events: 74,
};

/** A capped purse with a call that has no price is paused: a cap that cannot be checked is no cap. */
const sharedBudgetOnlyUnverifiable: AdminWorkspaceLlmUsage = {
	workspaceSlug: "launchpad",
	displayName: "Launchpad",
	instanceMonthlyBudgetUsd: 50,
	instanceTotalCostUsd: 38.2,
	instanceBudgetVerdict: "UNVERIFIABLE",
	instancePaused: true,
	ownProviderTotalCostUsd: 0,
	ownProviderInUse: false,
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
	ownProviderInUse: false,
	events: 3,
	ownProviderBudgetVerdict: "WITHIN",
	ownProviderPaused: false,
	instancePaused: false,
};

/** No cap, so calls with no price pause nothing, but $0.00 is still not the whole spend. */
const uncappedUnpriced: AdminWorkspaceLlmUsage = {
	...uncapped,
	workspaceSlug: "workbench",
	displayName: "Workbench",
	instanceTotalCostUsd: 0,
	instanceBudgetVerdict: "UNVERIFIABLE",
	events: 6,
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
	ownProviderInUse: true,
	events: 40,
	instancePaused: false,
};

const rows: AdminWorkspaceLlmUsage[] = [
	pausedOnSharedBudget,
	nearingItsOwnProviderCap,
	sharedBudgetOnlyUnverifiable,
	uncapped,
	uncappedUnpriced,
	pausedOnItsOwnProviderCap,
];

/** The usage details of one workspace's row: its run types and days add up to the row's figures. */
function detailReportFor(row: AdminWorkspaceLlmUsage): WorkspaceLlmUsageReport {
	const { workspaceSlug: _workspaceSlug, displayName: _displayName, events, ...limits } = row;
	const figures = {
		instanceTotalCostUsd: row.instanceTotalCostUsd,
		ownProviderTotalCostUsd: row.ownProviderTotalCostUsd,
		unpricedEventCount: 0,
		events,
	};
	return {
		...limits,
		month: "2026-07",
		unpricedEventCount: 0,
		...NO_PRECOMPUTE_USAGE,
		byJobType: [
			{
				jobType: "PULL_REQUEST_REVIEW",
				...figures,
				inputTokens: events * 4000,
				outputTokens: events * 600,
				cacheReadTokens: events * 500,
				cacheWriteTokens: events * 100,
				totalCalls: events * 3,
			},
		],
		byDay: [{ day: new Date("2026-07-05T00:00:00.000Z"), ...figures }],
	};
}

const detailReport = detailReportFor(pausedOnSharedBudget);

/** Thirty workspaces, the most shared-model spend at "Team 30": more than one page of 25. */
const thirtyWorkspaces: AdminWorkspaceLlmUsage[] = Array.from({ length: 30 }, (_, index) => {
	const number = String(index + 1).padStart(2, "0");
	return {
		...uncapped,
		workspaceSlug: `team-${number}`,
		displayName: `Team ${number}`,
		instanceTotalCostUsd: index + 1,
		events: (index + 1) * 3,
	};
});

/** The workspace names in the table, top to bottom. `find`: the preview mounts after the play starts. */
async function workspaceNames(canvas: StoryContext["canvas"]): Promise<string[]> {
	const table = await canvas.findByRole("table", { name: /^Per-workspace AI spend/u });
	return within(table)
		.getAllByRole("row")
		.slice(2)
		.map((row) => within(row).getAllByRole("cell")[0]?.querySelector("div")?.textContent ?? "");
}

/**
 * Every workspace's AI spend for one month, against both limits: the shared-model budget the host
 * grants — editable here — and the workspace's own provider cap, read-only because it is their money.
 * The table sorts, filters by name and pages at 25, because an instance can hold many workspaces.
 */
const meta = {
	component: AdminInstanceLlmUsageTable,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	render: (args) => (
		<StatefulPatch initial={args.view}>
			{(view, patch) => (
				<AdminInstanceLlmUsageTable
					{...args}
					view={view}
					onViewChange={(next) => {
						args.onViewChange(next);
						patch(next);
					}}
				/>
			)}
		</StatefulPatch>
	),
	args: {
		view: { q: "", sort: "sharedSpend", desc: true, page: 0 },
		onViewChange: fn(),
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

/** The shared-model budget cell of a workspace's row. */
function sharedBudgetCellOf(canvas: StoryContext["canvas"], displayName: string): HTMLElement {
	const row = canvas.getByRole("row", { name: new RegExp(`^${displayName}`, "u") });
	const cell = within(row).getAllByRole("cell")[2];
	if (cell == null) {
		throw new Error(`The row of ${displayName} has no shared-model budget cell.`);
	}
	return cell;
}

/**
 * Each limit cell badges its own state. Launchpad leads with the most shared-model spend; a call with
 * no price pauses its budget. Workbench has no budget, so its calls with no price pause nothing, and
 * the badge says that its $0.00 is not the whole spend.
 */
export const AllCapCombinationsInUsd: Story = {
	play: async ({ canvas }) => {
		await expect(await workspaceNames(canvas)).toStrictEqual([
			"Launchpad",
			"Example Workspace",
			"Hephaestus Dev",
			"Sandbox",
			"Atelier",
			"Workbench",
		]);
		await expect(canvas.queryByText(FX_DISCLOSURE)).toBeNull();
		const launchpad = within(sharedBudgetCellOf(canvas, "Launchpad"));
		await expect(launchpad.getByText("Paused")).toBeVisible();
		await expect(launchpad.getByText("No price set")).toBeVisible();
		const workbench = sharedBudgetCellOf(canvas, "Workbench");
		await expect(workbench.textContent).toBe("—No price set");
		await expect(within(workbench).getByText("No price set")).toBeVisible();
		await expect(canvas.queryByRole("columnheader", { name: "Status" })).toBeNull();
	},
};

/** The row's button names the budget it edits and whether one is set, and speech control can say it. */
export const SharedModelBudgetActions: Story = {
	play: async ({ canvas }) => {
		await canvas.findByRole("table", { name: /^Per-workspace AI spend/u });
		for (const [workspace, action] of [
			["Launchpad", "Change budget"],
			["Sandbox", "Set budget"],
		] as const) {
			const button = canvas.getByRole("button", {
				name: `${action} for ${workspace} (shared models)`,
			});
			await expect(button).toHaveTextContent(new RegExp(`^${action}$`, "u"));
		}
	},
};

/** The table names its first page of 25 and offers the next. */
export const FirstPage: Story = {
	args: { rows: thirtyWorkspaces },
	play: async ({ canvas, userEvent }) => {
		await expect(await workspaceNames(canvas)).toHaveLength(25);
		await userEvent.click(canvas.getByRole("button", { name: "Go to page 2" }));
		await expect(await workspaceNames(canvas)).toStrictEqual([
			"Team 05",
			"Team 04",
			"Team 03",
			"Team 02",
			"Team 01",
		]);
	},
};

export const PageTwo: Story = {
	args: { rows: thirtyWorkspaces, view: { q: "", sort: "sharedSpend", desc: true, page: 1 } },
	play: async ({ canvas }) => {
		await expect(await canvas.findByRole("button", { name: "Go to page 2" })).toHaveAttribute(
			"aria-current",
			"page",
		);
		await expect(await workspaceNames(canvas)).toHaveLength(5);
	},
};

/** The name filter narrows the rows and resets to page one; Reset brings every workspace back. */
export const FilteredByName: Story = {
	args: { rows: thirtyWorkspaces },
	play: async ({ canvas, userEvent }) => {
		await userEvent.type(await canvas.findByLabelText("Search workspaces"), "Team 2");
		await expect(await workspaceNames(canvas)).toHaveLength(10);
		await expect(canvas.queryByRole("navigation", { name: /pagination/iu })).toBeNull();

		await userEvent.click(canvas.getByRole("button", { name: "Reset" }));
		await expect(await workspaceNames(canvas)).toHaveLength(25);
	},
};

/** The field takes no more than the address keeps, so a long search is never cleared by itself. */
export const LongSearch: Story = {
	args: { rows: thirtyWorkspaces },
	play: async ({ canvas, userEvent }) => {
		const search = await canvas.findByLabelText("Search workspaces");
		await userEvent.click(search);
		await userEvent.paste("x".repeat(250));
		await expect(search).toHaveValue("x".repeat(200));
		await expect(await canvas.findByText("No workspaces match your search")).toBeVisible();
	},
};

export const NoNameMatches: Story = {
	args: { rows: thirtyWorkspaces, view: { q: "Atlas", sort: "sharedSpend", desc: true, page: 0 } },
	play: async ({ canvas }) => {
		await expect(await canvas.findByText("No workspaces match your search")).toBeVisible();
	},
};

/** Only leaf columns sort; the purse names group their columns and carry no `aria-sort`. */
export const SortByName: Story = {
	play: async ({ canvas, userEvent }) => {
		const headers = await canvas.findAllByRole("columnheader");
		const groups = headers.filter((header) => header.getAttribute("colspan") === "2");
		await expect(groups.map((group) => group.getAttribute("scope"))).toStrictEqual(["col", "col"]);
		await expect(groups.some((group) => group.hasAttribute("aria-sort"))).toBe(false);

		const name = canvas.getByRole("columnheader", { name: "Workspace" });
		await userEvent.click(within(name).getByRole("button", { name: "Workspace" }));
		await expect(name).toHaveAttribute("aria-sort", "ascending");
		const [first] = await workspaceNames(canvas);
		await expect(first).toBe("Atelier");
	},
};

/** No workspace has an own provider, so the table has no columns of $0 for one. */
export const SharedModelsOnly: Story = {
	args: { rows: [uncapped, sharedBudgetOnlyUnverifiable] },
	play: async ({ canvas }) => {
		await canvas.findByRole("table", { name: /^Per-workspace AI spend/u });
		await expect(canvas.queryByRole("columnheader", { name: "Own provider" })).toBeNull();
	},
};

/** Most workspaces have no precompute calls, and the panel then has no section for them. */
export const Expanded: Story = {
	args: {
		expandedWorkspaceSlug: pausedOnSharedBudget.workspaceSlug,
		detailReport,
	},
	play: async ({ canvas }) => {
		const element = await expandedPanelFor(canvas, pausedOnSharedBudget.displayName);
		const panel = within(element);
		// No tinted box around the tables: a rule above, the workspace's name, then the sections.
		await expect(getComputedStyle(element).backgroundColor).toBe("rgba(0, 0, 0, 0)");
		await expect(
			panel.getByRole("heading", { level: 2, name: pausedOnSharedBudget.displayName }),
		).toBeVisible();
		await expect(panel.getByText("Usage details")).toBeVisible();
		await expect(
			panel.getAllByRole("heading", { level: 3 }).map((heading) => heading.textContent),
		).toStrictEqual(["By run type", "By day"]);
		await expect(
			panel.queryByRole("heading", { name: "Precompute models by practice" }),
		).toBeNull();
	},
};

/**
 * The same per-practice table as the workspace console, below the two breakdowns. Practice names are
 * plain text here: an instance admin does not have to belong to the workspace.
 */
export const ExpandedWithPrecomputeModels: Story = {
	args: {
		expandedWorkspaceSlug: pausedOnSharedBudget.workspaceSlug,
		detailReport: {
			...detailReport,
			byPractice: [commentQualityUsage, notAttributedUsage],
			precomputeTotal: {
				reviews: 19,
				calls: 426,
				inputTokens: 99_500,
				outputTokens: 1280,
				instanceTotalCostUsd: 0.37,
				ownProviderTotalCostUsd: 0,
				unpricedEventCount: 0,
			},
		},
	},
	// Wide enough for two columns, where the breakdowns would sit side by side if they could.
	parameters: { viewport: { defaultViewport: "desktop" } },
	play: async ({ canvas }) => {
		const panel = within(await expandedPanelFor(canvas, pausedOnSharedBudget.displayName));
		const table = panel.getByRole("table", { name: "Precompute model spend by practice" });
		await expect(within(table).getByText("Comments explain why")).toBeVisible();
		await expect(within(table).queryByRole("link")).toBeNull();
		await expect(within(table).getByRole("row", { name: /^Total/u }).textContent).toContain(
			"$0.37",
		);
		// Each table gets the panel's width, so no header is cut off at the edge of a scroller.
		for (const name of [
			"AI spend by run type",
			"AI spend by day",
			"Precompute model spend by practice",
		]) {
			const scroller = horizontalScrollParentOf(panel.getByRole("table", { name }));
			await expect(scroller.scrollWidth, name).toBeLessThanOrEqual(scroller.clientWidth + 1);
		}
	},
};

/** The details warn about the provider cap that the row shows near its limit, at the row's figures. */
export const ExpandedNearCap: Story = {
	args: {
		expandedWorkspaceSlug: nearingItsOwnProviderCap.workspaceSlug,
		detailReport: detailReportFor(nearingItsOwnProviderCap),
	},
	play: async ({ canvas }) => {
		const panel = within(await expandedPanelFor(canvas, nearingItsOwnProviderCap.displayName));
		const runTypes = within(panel.getByRole("table", { name: "AI spend by run type" }));
		const row = runTypes.getByRole("row", { name: /^Pull request review/u });
		// The details add up to the row above them: $13.48 shared, $21.40 own provider, 74 runs.
		await expect(within(row).getByText("$13.48")).toBeVisible();
		await expect(within(row).getByText("$21.40")).toBeVisible();
		await expect(within(row).getByText("74")).toBeVisible();
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
		canvas.getByLabelText("about 21.99 euros");
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
		for (const name of ["Budget (Shared models)", "Cap (Own provider)"]) {
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
				ownProviderInUse: true,
				events: 210,
			},
		],
	},
	play: async ({ canvas }) => {
		// One badge per limit, each beside the meter it reads, so the amber never carries the state alone.
		await canvas.findByRole("table", { name: /^Per-workspace AI spend/u });
		for (const [meter, badge] of [
			["Shared-model budget used by Close Call", "Near the budget"],
			["Provider cap used by Close Call", "Near the cap"],
		] as const) {
			const cell = canvas.getByRole("progressbar", { name: meter }).closest("td");
			await expect(cell).not.toBeNull();
			const label = within(cell ?? document.body).getByText(badge);
			await expect(label).toBeVisible();
			// The whole state, never cut to "Near the bu…".
			await expect(label.scrollWidth).toBeLessThanOrEqual(label.clientWidth);
		}
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
				ownProviderInUse: true,
				events: 96,
			},
		],
	},
	play: async ({ canvas }) => {
		await expect(await canvas.findAllByText("Paused")).toHaveLength(2);
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
				ownProviderInUse: false,
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
		await expect(canvas.queryByRole("button", { name: /budget for/u })).toBeNull();
		await expect(canvas.getAllByRole("button", { name: /^Details for/u })).toHaveLength(
			rows.length,
		);
	},
};

/** Every limit state keeps its words and icon on the dark theme, so the tone never carries it alone. */
export const Dark: Story = {
	globals: { theme: "dark" },
	play: async ({ canvas }) => {
		await canvas.findByRole("table", { name: /^Per-workspace AI spend/u });
		await expect(
			within(sharedBudgetCellOf(canvas, "Example Workspace")).getByText("Paused"),
		).toBeVisible();
		await expect(
			within(sharedBudgetCellOf(canvas, "Launchpad")).getByText("No price set"),
		).toBeVisible();
		await expect(
			within(
				canvas
					.getByRole("progressbar", { name: "Provider cap used by Hephaestus Dev" })
					.closest("td") ?? document.body,
			).getByText("Near the cap"),
		).toBeVisible();
		await expect(
			within(sharedBudgetCellOf(canvas, "Workbench")).getByText("No price set"),
		).toBeVisible();
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
		error: { status: 500, detail: "We could not roll up AI usage." },
	},
};

export const ForbiddenError: Story = {
	args: {
		rows: [],
		error: { status: 403, detail: "Instance admin access is required." },
	},
};
