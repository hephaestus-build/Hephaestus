import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, userEvent, within } from "storybook/test";

import {
	ACROSS_WORKSPACE,
	ACROSS_WORKSPACE_TILES,
	EMPTY_WORKSPACE,
	MANY_GROUPS_WORKSPACE,
	NOBODY_TILES,
	NOBODY_WORKSPACE,
} from "@/stories/practices-across-the-workspace-story-data";
import { expectNoPageOverflow } from "@/stories/reflow";

import { PracticesAcrossTheWorkspacePage } from "./PracticesAcrossTheWorkspacePage";

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

const figuresSection = (canvas: {
	getByRole: (role: "heading", options: { level: number; name: string }) => HTMLElement;
}) => canvas.getByRole("heading", { level: 2, name: "Your figures" }).closest("section");

export const Default: Story = {
	play: async ({ canvas, args }) => {
		// The hints carry the response's own number, 26 in the window, and say plainly that small
		// counts show too.
		await expect(
			canvas.getByText(
				/Hephaestus sorts the 26 developers in this workspace who have a standing in the last 30 days/u,
			),
		).toBeVisible();
		await expect(
			canvas.getByText(
				/When only a few developers are counted, the band can show the value of one person\./u,
			),
		).toBeVisible();
		await expect(
			canvas.getByText(
				/Every bar shows all its counts, however small\. So a small count can let others tell where you stand\./u,
			),
		).toBeVisible();
		// A group with parts of one, two and none draws like any other.
		await expect(
			groupsTable(canvas).getByRole("img", {
				name: "28 developers with a current standing in this workspace: 1 Needs attention, 0 Mixed feedback, 2 Going well, 25 none yet. The You marker is on none yet.",
			}),
		).toBeVisible();
		// Only the tiles read the window: the toggle sits in their section, and the bars name none.
		await expect(figuresSection(canvas)).toContainElement(
			canvas.getByRole("toolbar", { name: "Time range" }),
		);
		// The window is the section's description, not its heading.
		await expect(canvas.getByText("Last 30 days", { selector: "p" })).toBeVisible();
		await expect(
			canvas.getByRole("heading", { level: 2, name: "All practice groups" }).closest("section")
				?.textContent,
		).not.toMatch(/30 days|90 days|All time/u);
		const table = groupsTable(canvas);
		await expect(
			table.getByRole("img", {
				name: "28 developers with a current standing in this workspace: 7 Needs attention, 6 Mixed feedback, 8 Going well, 7 none yet. The You marker is on Needs attention.",
			}),
		).toBeVisible();
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

/** Nobody has a standing yet: no tile has a range, and no bar counts anybody. */
export const NobodyYet: Story = {
	args: {
		state: { status: "ready", overview: NOBODY_WORKSPACE },
		tiles: { status: "ready", tiles: NOBODY_TILES },
	},
	play: async ({ canvas }) => {
		await expect(
			canvas.getByText(
				"No developer in this workspace has a standing in the last 30 days, so these figures have no typical range.",
			),
		).toBeVisible();
		await expect(canvas.getAllByText("No developer is counted here yet.")).toHaveLength(3);
		// Nobody has open feedback, so that tile says so in place of a band at nought.
		await expect(canvas.getByText("Most developers here have no open feedback.")).toBeVisible();
		// Once for the whole table, not once per group.
		await expect(canvas.getAllByText("No developer has a standing yet.")).toHaveLength(1);
	},
};

/** Many groups: the table lists every one, as the Practice profile does, with no paging. */
export const ManyGroups: Story = {
	args: { state: { status: "ready", overview: MANY_GROUPS_WORKSPACE } },
	play: async ({ canvas }) => {
		const table = groupsTable(canvas);
		await expect(table.getAllByRole("button", { name: /^Open group /u })).toHaveLength(26);
	},
};

/** Another window on its way: the last figures stay, and only their section is busy. */
export const SwitchingWindow: Story = {
	args: {
		tiles: { status: "ready", tiles: ACROSS_WORKSPACE_TILES, stale: true },
		window: "DAYS_90",
	},
	play: async ({ canvas }) => {
		const table = canvas.getByRole("table", { name: "All practice groups" });
		await expect(table).toBeVisible();
		await expect(figuresSection(canvas)).toHaveAttribute("aria-busy", "true");
		await expect(canvas.getByText("Pieces of work reviewed")).toBeVisible();
		await expect(
			canvas.getByRole("heading", { level: 2, name: "All practice groups" }).closest("section"),
		).not.toHaveAttribute("aria-busy");
	},
};

export const Empty: Story = {
	args: { state: { status: "ready", overview: EMPTY_WORKSPACE } },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("No practices set up yet")).toBeVisible();
	},
};

export const Loading: Story = {
	args: { state: { status: "loading" }, tiles: { status: "loading" } },
	play: async ({ canvas }) => {
		await expect(figuresSection(canvas)).toHaveAttribute("aria-busy", "true");
		await expect(canvas.getByRole("table", { name: "All practice groups" })).toHaveAttribute(
			"aria-busy",
			"true",
		);
	},
};

const onRetryOverview = fn();

/** Every region reads the overview, so its failure takes the page, with no window toggle to press. */
export const LoadError: Story = {
	args: { state: { status: "error", error: new Error("Network down"), onRetry: onRetryOverview } },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("We could not load the practice groups")).toBeVisible();
		await expect(canvas.queryByRole("toolbar", { name: "Time range" })).toBeNull();
		await userEvent.click(canvas.getByRole("button", { name: "Retry" }));
		await expect(onRetryOverview).toHaveBeenCalledOnce();
	},
};

const onRetryTiles = fn();

/** The window's figures failed: their section says so, and the bars, which read no window, stay. */
export const TilesLoadError: Story = {
	args: { tiles: { status: "error", error: new Error("Network down"), onRetry: onRetryTiles } },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("We could not load your figures")).toBeVisible();
		await expect(canvas.getByRole("table", { name: "All practice groups" })).toBeVisible();
		await userEvent.click(canvas.getByRole("button", { name: "Retry" }));
		await expect(onRetryTiles).toHaveBeenCalledOnce();
	},
};

export const Reflow: Story = {
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	play: expectNoPageOverflow,
};
