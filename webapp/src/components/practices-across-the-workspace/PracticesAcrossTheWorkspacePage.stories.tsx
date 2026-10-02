import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, userEvent, waitFor, within } from "storybook/test";

import {
	ACROSS_WORKSPACE,
	COLLAPSED_WORKSPACE,
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
	parameters: { layout: "padded" },
	args: {
		workspaceSlug: "aet",
		state: { status: "ready", overview: ACROSS_WORKSPACE },
		window: "TERM",
		onWindowChange: fn(),
		showWorkspace: true,
		onShowWorkspaceChange: fn(),
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
				"Course figures show the middle half of developers, from the 25th to the 75th percentile.",
			),
		).toBeVisible();
		await expect(
			canvas.getByRole("heading", { level: 2, name: "All practice groups" }),
		).toBeVisible();
		const table = groupsTable(canvas);
		await expect(
			table.getByRole("img", {
				name: "24 developers observed in this workspace this term: 6 Needs attention, 5 Mixed feedback, 8 Going well, 5 none yet. You: Needs attention.",
			}),
		).toBeVisible();
		await expect(
			table.getByRole("link", { name: "Open group Packaging work for review" }),
		).toHaveAttribute("href", expect.stringContaining("practice-group%3Areview-ready-work"));
		await userEvent.click(
			table.getByRole("button", { name: "See practices of the group Packaging work for review" }),
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

/** The workspace turned off: the reader's own figures and standings, and no figure about anyone else. */
export const WorkspaceHidden: Story = {
	args: { showWorkspace: false },
	play: async ({ canvas }) => {
		await expect(canvas.queryAllByRole("img", { name: /developers observed/u })).toHaveLength(0);
		await expect(canvas.queryByText(/Most developers here/u)).toBeNull();
		await expect(canvas.getByText(/pieces of your work reviewed, this term\.$/u)).toHaveTextContent(
			/^17 pieces of your work reviewed, this term\.$/u,
		);
	},
};

/** Each split collapses to has a standing against none yet. */
export const Collapsed: Story = {
	args: { state: { status: "ready", overview: COLLAPSED_WORKSPACE } },
	play: async ({ canvas }) => {
		await expect(canvas.getAllByRole("img", { name: /17 have a standing/u })).toHaveLength(8);
	},
};

/** Has a standing or none yet holds too few in every group, so each says only the observed total. */
export const CollapsedTotalOnly: Story = {
	args: { state: { status: "ready", overview: TOTAL_ONLY_WORKSPACE } },
	play: async ({ canvas }) => {
		await expect(
			canvas.getAllByText("Split held back: 24 developers observed this term."),
		).toHaveLength(8);
	},
};

/** Too few developers observed: every middle half, every split and every total is withheld. */
export const Withheld: Story = {
	args: { state: { status: "ready", overview: GATED_WORKSPACE } },
	play: async ({ canvas }) => {
		await expect(
			canvas.getAllByText("Needs more data before the workspace shows here."),
		).toHaveLength(4);
		await expect(canvas.getAllByText("Too few developers observed to compare yet.")).toHaveLength(
			8,
		);
		await expect(canvas.queryAllByRole("img", { name: /developers observed/u })).toHaveLength(0);
	},
};

/** More groups than a page: the table lists the first twenty and loads the rest as it is read. */
export const ManyGroups: Story = {
	args: { state: { status: "ready", overview: MANY_GROUPS_WORKSPACE } },
	play: async ({ canvas }) => {
		const table = groupsTable(canvas);
		await expect(table.getAllByRole("link", { name: /^Open group /u })).toHaveLength(20);
		// Reading to the end of the first page brings its end row into view, which shows the rest.
		table.getByText("Practice group 20").scrollIntoView();
		await waitFor(async () => {
			await expect(table.getAllByRole("link", { name: /^Open group /u })).toHaveLength(26);
		});
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
		await expect(canvas.getByText("Couldn't load the workspace")).toBeVisible();
		await expect(canvas.queryByRole("table", { name: "All practice groups" })).toBeNull();
	},
};

export const Reflow: Story = {
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	play: expectNoPageOverflow,
};
