import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, userEvent, within } from "storybook/test";

import {
	ACROSS_WORKSPACE,
	EMPTY_WORKSPACE,
	GATED_WORKSPACE,
	MANY_GROUPS_WORKSPACE,
	TOTAL_ONLY_WORKSPACE,
} from "@/stories/practices-across-the-workspace-story-data";
import { expectNoPageOverflow } from "@/stories/reflow";

import { PracticesAcrossTheWorkspacePage } from "./PracticesAcrossTheWorkspacePage";

/**
 * The reader's figures beside the workspace's middle half, then every practice group beside how the
 * workspace's observed developers split across it. The reader's own standing is the You marker on a
 * split, or a word where the marker cannot say it, so a split held back says nothing the word does
 * not.
 */
const meta = {
	component: PracticesAcrossTheWorkspacePage,
	tags: ["autodocs"],
	parameters: { layout: "fullscreen" },
	args: {
		state: { status: "ready", overview: ACROSS_WORKSPACE },
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
				"See where your practices stand among the developers in this workspace, so you can choose what to work on next. Standings move with your next pieces of reviewed work; open a group to see your next step.",
			),
		).toBeVisible();
		await expect(
			canvas.getByRole("heading", { level: 2, name: "All practice groups" }),
		).toBeVisible();
		// The two rules, each where it applies, with the numbers the response carries.
		await expect(
			canvas.getByText(
				"The typical range is the middle half of 28 developers observed in the last 30 days; your marker shows you. A tile compares you once at least 6 other developers have reviewed work in this window; until then it shows only your own value.",
			),
		).toBeVisible();
		await expect(
			canvas.getByText(
				"Each bar counts developers by their standing in the group, and You marks yours. A part shows only when it holds at least 3 other developers; otherwise it is merged or held back, so no one can be singled out.",
			),
		).toBeVisible();
		const table = groupsTable(canvas);
		await expect(
			table.getByRole("img", {
				name: "28 developers observed in this workspace in the last 30 days: 7 Needs attention, 6 Mixed feedback, 8 Going well, 7 none yet. You: Needs attention.",
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

/** A part holds too few in every group: a dashed track in every row, the total said once. */
export const AllHeldBack: Story = {
	args: { state: { status: "ready", overview: TOTAL_ONLY_WORKSPACE } },
	play: async ({ canvas }) => {
		await expect(canvas.getAllByText("Held back: too few developers to compare yet.")).toHaveLength(
			8,
		);
	},
};

/**
 * Too few developers observed: every middle half, every split and the observed total are held back
 * by the server, and the page says only how many pieces of the reader's own work were reviewed.
 */
export const Withheld: Story = {
	args: { state: { status: "ready", overview: GATED_WORKSPACE } },
	play: async ({ canvas }) => {
		await expect(canvas.queryByText(/developers\s+observed,/u)).toBeNull();
		await expect(
			canvas.getAllByText("Needs more data before the workspace shows here."),
		).toHaveLength(4);
		await expect(canvas.getAllByText("Held back: too few developers to compare yet.")).toHaveLength(
			8,
		);
		await expect(canvas.queryAllByRole("img", { name: /developers observed/u })).toHaveLength(0);
	},
};

/** More groups than a page: the table lists the first twenty and shows the rest when asked. */
export const ManyGroups: Story = {
	args: { state: { status: "ready", overview: MANY_GROUPS_WORKSPACE } },
	play: async ({ canvas }) => {
		const table = groupsTable(canvas);
		await expect(table.getAllByRole("button", { name: /^Open group /u })).toHaveLength(20);
		await userEvent.click(table.getByRole("button", { name: "Show more practice groups" }));
		await expect(table.getAllByRole("button", { name: /^Open group /u })).toHaveLength(26);
		await expect(table.queryByRole("button", { name: "Show more practice groups" })).toBeNull();
	},
};

/** Another window's figures on their way: the last ones stay, and both sections say they are busy. */
export const SwitchingWindow: Story = {
	args: { state: { status: "ready", overview: ACROSS_WORKSPACE, stale: true }, window: "DAYS_90" },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("table", { name: "All practice groups" })).toBeVisible();
		await expect(
			canvas.getByRole("heading", { level: 2, name: "All practice groups" }).closest("section"),
		).toHaveAttribute("aria-busy", "true");
	},
};

export const Empty: Story = {
	args: { state: { status: "ready", overview: EMPTY_WORKSPACE } },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("No practice groups here yet")).toBeVisible();
	},
};

export const Loading: Story = {
	args: { state: { status: "loading" } },
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
		await expect(canvas.getByText("Could not load the workspace")).toBeVisible();
		await expect(canvas.queryByRole("table", { name: "All practice groups" })).toBeNull();
	},
};

export const Reflow: Story = {
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	play: expectNoPageOverflow,
};
