import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, userEvent, within } from "storybook/test";

import {
	ACROSS_WORKSPACE,
	ACROSS_WORKSPACE_TILES,
	EMPTY_WORKSPACE,
	GATED_TILES,
	GATED_WORKSPACE,
	MANY_GROUPS_WORKSPACE,
	TOTAL_ONLY_WORKSPACE,
} from "@/stories/practices-across-the-workspace-story-data";
import { expectNoPageOverflow } from "@/stories/reflow";

import { PracticesAcrossTheWorkspacePage } from "./PracticesAcrossTheWorkspacePage";

/**
 * The reader's figures beside the workspace's middle half over the window, then every practice
 * group beside how the workspace's developers split across it by their current standing. The
 * reader shows only as the You marker on a split; a split shown as its total marks no one.
 */
const meta = {
	component: PracticesAcrossTheWorkspacePage,
	tags: ["autodocs"],
	parameters: { layout: "fullscreen" },
	args: {
		state: { status: "ready", overview: ACROSS_WORKSPACE },
		tiles: { status: "ready", tiles: ACROSS_WORKSPACE_TILES },
		window: "DAYS_30",
		onWindowChange: fn(),
		onOpenGroup: fn(),
	},
} satisfies Meta<typeof PracticesAcrossTheWorkspacePage>;

export default meta;
type Story = StoryObj<typeof meta>;

const groupsTable = (canvas: {
	getByRole: (role: "table", options: { name: string }) => HTMLElement;
}) => within(canvas.getByRole("table", { name: "All practice groups" }));

export const Default: Story = {
	play: async ({ canvas, args }) => {
		await expect(
			canvas.getByText(
				"This page shows where the developers in this workspace stand in each practice group. Your next step is in your Practice profile.",
			),
		).toBeVisible();
		await expect(
			canvas.getByRole("heading", { level: 2, name: "All practice groups" }),
		).toBeVisible();
		// The two rules, each where it applies, with the numbers the response carries.
		await expect(
			canvas.getByText(
				"Except for open feedback, the typical range is the middle half of 26 developers with a standing in the last 30 days. Your marker shows you. These tiles compare you when at least 6 other developers have a standing in this range. Until then, they show only your own value. Open feedback counts what is open now, for all developers that this page counts.",
			),
		).toBeVisible();
		await expect(
			canvas.getByText(
				"Each bar counts developers by their current standing in the group, as their Practice profile shows it. You marks your part. A bar shows its parts only if each part holds at least 3 other developers. If not, the bar shows only its total, so no one can be singled out.",
			),
		).toBeVisible();
		// The window toggle sits in the tiles' heading row, not over the bars.
		await expect(
			canvas.getByRole("heading", { level: 2, name: "Last 30 days" }).closest("section"),
		).toContainElement(canvas.getByText("Pieces of work reviewed"));
		await expect(
			canvas.getByRole("heading", { level: 2, name: "All practice groups" }).closest("section")
				?.textContent,
		).not.toMatch(/30 days|90 days|All time/u);
		await expect(canvas.queryByText(/^You:/u)).toBeNull();
		// The window's own figures, in their colours.
		await expect(
			canvas.getByRole("table", { name: "All practice groups" }).closest(".grayscale"),
		).toBeNull();
		const table = groupsTable(canvas);
		await expect(
			table.getByRole("img", {
				name: "28 developers with a current standing in this workspace: 7 Needs attention, 6 Mixed feedback, 8 Going well, 7 none yet. The You marker is on Needs attention.",
			}),
		).toBeVisible();
		// The row's one action, drawn as the reviews table draws "Open review", opens the group's level.
		await userEvent.click(
			table.getByRole("button", { name: "Open group Packaging work for review" }),
		);
		await expect(args.onOpenGroup).toHaveBeenCalledWith("review-ready-work");
	},
};

/** The row whose practices are open over the page keeps a bar on its leading edge. */
export const GroupOpen: Story = {
	args: { openGroupSlug: "review-ready-work" },
	play: async ({ canvas }) => {
		const row = groupsTable(canvas)
			.getAllByRole("row")
			.find((each) => each.textContent.startsWith("Packaging work for review"));
		await expect(row).toHaveAttribute("data-state", "open");
	},
};

/**
 * A part holds too few in every group: a neutral bar with the total and its label in every row,
 * and no row says anything of the reader.
 */
export const AllTotalOnly: Story = {
	args: { state: { status: "ready", overview: TOTAL_ONLY_WORKSPACE } },
	play: async ({ canvas }) => {
		await expect(groupsTable(canvas).getAllByText("Split held back")).toHaveLength(8);
		await expect(groupsTable(canvas).getAllByText("28 developers")).toHaveLength(8);
		await expect(canvas.queryByText(/^You:/u)).toBeNull();
		await expect(groupsTable(canvas).queryByText("You")).toBeNull();
	},
};

/**
 * Too few developers with a standing: every middle half, every split and their total are held back
 * by the server, and the page says only how many pieces of the reader's own work were reviewed.
 */
export const Withheld: Story = {
	args: {
		state: { status: "ready", overview: GATED_WORKSPACE },
		tiles: { status: "ready", tiles: GATED_TILES },
	},
	play: async ({ canvas }) => {
		// No count of them, where the server held the total back.
		await expect(canvas.queryByText(/\d+ developers\s+with a standing/u)).toBeNull();
		await expect(
			canvas.getAllByText("Needs more data before the workspace shows here."),
		).toHaveLength(4);
		await expect(canvas.getAllByText("Held back so no one can be singled out.")).toHaveLength(8);
		await expect(
			canvas.queryAllByRole("img", { name: /developers with a standing/u }),
		).toHaveLength(0);
	},
};

/** Many groups: the table lists every one, as the Practice profile does, with no paging. */
export const ManyGroups: Story = {
	args: { state: { status: "ready", overview: MANY_GROUPS_WORKSPACE } },
	play: async ({ canvas }) => {
		const table = groupsTable(canvas);
		await expect(table.getAllByRole("button", { name: /^Open group /u })).toHaveLength(26);
		await expect(table.queryByRole("button", { name: /^Show more/u })).toBeNull();
	},
};

/**
 * Another window's figures on their way: the last tiles stay, drained of their colours under the
 * new heading, and only their section says it is busy. The bars count the current standing, so
 * the window leaves them as they are.
 */
export const SwitchingWindow: Story = {
	args: {
		tiles: { status: "ready", tiles: ACROSS_WORKSPACE_TILES, stale: true },
		window: "DAYS_90",
	},
	play: async ({ canvas }) => {
		const table = canvas.getByRole("table", { name: "All practice groups" });
		await expect(table).toBeVisible();
		await expect(
			canvas.getByRole("heading", { level: 2, name: "Last 90 days" }).closest("section"),
		).toHaveAttribute("aria-busy", "true");
		await expect(
			canvas.getByRole("heading", { level: 2, name: "All practice groups" }).closest("section"),
		).not.toHaveAttribute("aria-busy");
		await expect(table.closest(".grayscale")).toBeNull();
		await expect(canvas.getByText("Pieces of work reviewed").closest(".grayscale")).not.toBeNull();
	},
};

export const Empty: Story = {
	args: { state: { status: "ready", overview: EMPTY_WORKSPACE } },
	play: async ({ canvas }) => {
		// The Practice profile's words for the same state.
		await expect(canvas.getByText("No practices set up yet")).toBeVisible();
	},
};

export const Loading: Story = {
	args: { state: { status: "loading" }, tiles: { status: "loading" } },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Loading the figures")).toHaveClass("sr-only");
		await expect(canvas.getByRole("table", { name: "All practice groups" })).toHaveAttribute(
			"aria-busy",
			"true",
		);
	},
};

export const LoadError: Story = {
	args: { state: { status: "error", error: new Error("Network down"), onRetry: fn() } },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("We could not load the workspace")).toBeVisible();
		await expect(canvas.queryByRole("table", { name: "All practice groups" })).toBeNull();
	},
};

/** The window's tiles failed: their section says so, and the bars, which read no window, stay. */
export const TilesLoadError: Story = {
	args: { tiles: { status: "error", error: new Error("Network down"), onRetry: fn() } },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("We could not load the figures")).toBeVisible();
		await expect(canvas.getByRole("table", { name: "All practice groups" })).toBeVisible();
	},
};

export const Reflow: Story = {
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	play: expectNoPageOverflow,
};
