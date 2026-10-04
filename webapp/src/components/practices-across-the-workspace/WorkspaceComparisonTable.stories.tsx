import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, userEvent } from "storybook/test";

import { threeWay, WITHHELD } from "@/stories/practices-across-the-workspace-story-data";

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

const onOpen = fn();

const ROWS: ComparisonRow[] = [
	row("acting", "Acting on review feedback", "MIXED", threeWay([6, 7, 7])),
	row("failure", "Handling failure well", "DEVELOPING", threeWay([4, 9, 9])),
	row("craft", "Writing maintainable code", "STRENGTH", WITHHELD),
	row("testing", "Testing your changes", "NOT_OBSERVED", threeWay([6, 6, 8])),
];

const CONTEXT = {
	window: "DAYS_30",
	readerCounted: true,
	developersWithAStanding: 28,
	minimumOthers: 3,
} as const;

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
		state: { status: "ready", rows: ROWS, context: CONTEXT },
		rowLink: () => ({ text: "Open group", onOpen }),
		noun: "practice groups",
		empty: { title: "No practice groups yet", description: "They appear once set up." },
	},
	argTypes: { rowLink: { control: false } },
} satisfies Meta<typeof WorkspaceComparisonTable>;

export default meta;
type Story = StoryObj<typeof meta>;

/** Both shapes in one table: split and held back, with a reader not counted. */
export const Default: Story = {
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("table", { name: "All practice groups" })).toBeVisible();
		await expect(
			canvas.getByRole("columnheader", { name: "Developers in this workspace" }),
		).toBeVisible();
		await expect(canvas.getAllByRole("img")).toHaveLength(3);
		// A split and a held back track fill the same width, the whole bar column.
		const split = canvas.getAllByRole("img")[0]?.getBoundingClientRect().width;
		const heldBack = canvas
			.getByText("Held back so no one can be singled out.")
			.parentElement?.getBoundingClientRect().width;
		await expect(split).toBe(heldBack);
		await expect(
			canvas.getByRole("img", { name: /4 Needs attention, 9 Mixed feedback, 9 Going well/u }),
		).toBeVisible();
		await expect(canvas.getByText("Held back so no one can be singled out.")).toBeVisible();
		await userEvent.click(canvas.getByRole("button", { name: "Open group Handling failure well" }));
		await expect(onOpen).toHaveBeenCalledOnce();
	},
};

/** Every split held back: an empty track and one short reason per row, the reader's word beside it. */
export const Withheld: Story = {
	args: {
		state: {
			status: "ready",
			rows: ROWS.map((each) => ({ ...each, split: WITHHELD })),
			context: { ...CONTEXT, developersWithAStanding: undefined },
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.getAllByText("Held back so no one can be singled out.")).toHaveLength(4);
		await expect(canvas.queryByRole("img")).toBeNull();
	},
};

export const Loading: Story = {
	args: { state: { status: "loading" } },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("table")).toHaveAttribute("aria-busy", "true");
	},
};

export const Empty: Story = {
	args: { state: { status: "ready", rows: [], context: CONTEXT } },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("No practice groups yet")).toBeVisible();
	},
};

const onShowMore = fn();

/** A list longer than a page ends in a press that shows the next one. */
export const ShowMore: Story = {
	args: {
		state: {
			status: "ready",
			rows: ROWS,
			context: CONTEXT,
			more: { hasMore: true, onShowMore },
		},
	},
	play: async ({ canvas }) => {
		await userEvent.click(canvas.getByRole("button", { name: "Show more practice groups" }));
		await expect(onShowMore).toHaveBeenCalledOnce();
	},
};
