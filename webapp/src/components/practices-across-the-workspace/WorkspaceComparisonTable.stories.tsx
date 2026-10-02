import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, userEvent } from "storybook/test";

import { Button } from "@/components/ui/button";
import { collapsed, threeWay, WITHHELD } from "@/stories/practices-across-the-workspace-story-data";

import { type ComparisonRow, WorkspaceComparisonTable } from "./WorkspaceComparisonTable";

const row = (
	key: string,
	name: string,
	yourStanding: ComparisonRow["yourStanding"],
	split: ComparisonRow["split"],
): ComparisonRow => ({
	key,
	name,
	subject: <span className="font-medium">{name}</span>,
	yourStanding,
	split,
});

const ROWS: ComparisonRow[] = [
	row("acting", "Acting on review feedback", "MIXED", threeWay([5, 7, 7])),
	row("failure", "Handling failure well", "DEVELOPING", collapsed(19, 5)),
	row("craft", "Writing maintainable code", "STRENGTH", WITHHELD),
	row("testing", "Testing your changes", "NOT_OBSERVED", threeWay([5, 6, 7])),
];

/**
 * One table for practice groups and for practices: the caller decides the subject cell and the
 * actions, the table draws the split with the reader's place on it and the end of a long list.
 */
const meta = {
	component: WorkspaceComparisonTable,
	tags: ["autodocs"],
	parameters: { layout: "padded" },
	args: {
		"aria-label": "All practice groups",
		subjectHead: "Practice group",
		rows: ROWS,
		scope: "group",
		context: { window: "TERM", readerCounted: true, observedDevelopers: 24, minimumOthers: 5 },
		showWorkspace: true,
		actions: (each: ComparisonRow) => (
			<Button variant="outline" size="xs" aria-label={`Open group ${each.name}`}>
				Open group
			</Button>
		),
		noun: "practice groups",
		empty: { title: "No practice groups here yet", description: "They appear once set up." },
	},
	argTypes: { actions: { control: false } },
} satisfies Meta<typeof WorkspaceComparisonTable>;

export default meta;
type Story = StoryObj<typeof meta>;

/** Every shape in one table: split, collapsed, held back with its total, and a reader not counted. */
export const Default: Story = {
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("table", { name: "All practice groups" })).toBeVisible();
		await expect(canvas.getAllByRole("img")).toHaveLength(3);
		await expect(canvas.getByText("19 have a standing, 5 none yet; split held back")).toBeVisible();
		await expect(
			canvas.getByText("Split held back: 24 developers observed this term."),
		).toBeVisible();
		await expect(
			canvas.getByRole("button", { name: "Open group Handling failure well" }),
		).toBeVisible();
	},
};

export const Collapsed: Story = {
	args: { rows: ROWS.map((each) => ({ ...each, split: collapsed(17, 7) })) },
	play: async ({ canvas }) => {
		await expect(
			canvas.getAllByText("17 have a standing, 7 none yet; split held back"),
		).toHaveLength(4);
	},
};

export const CollapsedTotalOnly: Story = {
	args: { rows: ROWS.map((each) => ({ ...each, split: WITHHELD })) },
	play: async ({ canvas }) => {
		await expect(
			canvas.getAllByText("Split held back: 24 developers observed this term."),
		).toHaveLength(4);
		await expect(canvas.queryByRole("img")).toBeNull();
	},
};

/** Too few others observed: no split and no total, only the reader's own word in each row. */
export const Withheld: Story = {
	args: {
		rows: ROWS.map((each) => ({ ...each, split: WITHHELD })),
		context: { window: "TERM", readerCounted: true, observedDevelopers: 5, minimumOthers: 5 },
	},
	play: async ({ canvas }) => {
		await expect(canvas.getAllByText("Too few developers observed to compare yet.")).toHaveLength(
			4,
		);
	},
};

/** The workspace turned off: the reader's own standing as a badge, and no split. */
export const WorkspaceHidden: Story = {
	args: { showWorkspace: false },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("columnheader", { name: "Your standing" })).toBeVisible();
		await expect(canvas.queryByRole("img")).toBeNull();
		await expect(canvas.getByRole("button", { name: "Not observed yet" })).toBeVisible();
	},
};

export const Loading: Story = {
	args: { rows: [], isLoading: true },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("table")).toHaveAttribute("aria-busy", "true");
	},
};

export const Empty: Story = {
	args: { rows: [] },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("No practice groups here yet")).toBeVisible();
	},
};

/** The end of the list while the next rows come in. */
export const LoadingMore: Story = {
	args: { more: { hasMore: true, isLoadingMore: true, onLoadMore: fn() } },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Loading more practice groups…")).toBeVisible();
	},
};

/** A load that failed is the one case that asks, with the press offered back. */
export const LoadMoreFailed: Story = {
	args: {
		more: {
			hasMore: true,
			isLoadingMore: false,
			loadMoreError: new Error("offline"),
			onLoadMore: fn(),
		},
	},
	play: async ({ canvas, args }) => {
		await expect(canvas.getByText("Could not load more practice groups.")).toBeVisible();
		await userEvent.click(canvas.getByRole("button", { name: "Show more practice groups" }));
		await expect(args.more?.onLoadMore).toHaveBeenCalledOnce();
	},
};
