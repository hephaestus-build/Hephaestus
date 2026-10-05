import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, userEvent } from "storybook/test";

import {
	threeWay,
	TOTAL_ONLY,
	WITHHELD,
} from "@/stories/practices-across-the-workspace-story-data";

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
	row("craft", "Writing maintainable code", undefined, TOTAL_ONLY),
	row("testing", "Testing your changes", "NOT_OBSERVED", threeWay([6, 6, 8])),
];

const TOTAL_ONLY_NAME =
	"28 developers with a current standing in this workspace. The split is held back so no one can be singled out.";

/**
 * One table for practice groups and for practices: the caller decides the subject cell and the
 * row link. Each split shape has its own stories on `WorkspaceSplitBar`; these pin what the table
 * adds, which is that every shape fills the same column.
 */
const meta = {
	component: WorkspaceComparisonTable,
	tags: ["autodocs"],
	parameters: { layout: "padded" },
	args: {
		"aria-label": "All practice groups",
		subjectHead: "Practice group",
		state: { status: "ready", rows: ROWS },
		rowLink: () => ({ text: "Open group", onOpen }),
		empty: { title: "No practices set up yet", description: "They appear once set up." },
	},
	argTypes: { rowLink: { control: false } },
} satisfies Meta<typeof WorkspaceComparisonTable>;

export default meta;
type Story = StoryObj<typeof meta>;

/** Both shapes in one table: split and total only, the reader marked only on a split. */
export const Default: Story = {
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("table", { name: "All practice groups" })).toBeVisible();
		await expect(
			canvas.getByRole("columnheader", { name: "Developers in this workspace" }),
		).toBeVisible();
		await expect(canvas.getAllByRole("img")).toHaveLength(4);
		// A split and a total only bar fill the same width, the whole bar column.
		const split = canvas.getAllByRole("img")[0]?.getBoundingClientRect().width;
		const totalOnly = canvas
			.getByRole("img", { name: TOTAL_ONLY_NAME })
			.getBoundingClientRect().width;
		await expect(split).toBe(totalOnly);
		await expect(
			canvas.getByRole("img", { name: /4 Needs attention, 9 Mixed feedback, 9 Going well/u }),
		).toBeVisible();
		// The total only row names its total and says the split is held back, and marks no one.
		const craft = canvas.getAllByRole("row").find((each) => each.textContent.startsWith("Writing"));
		await expect(craft?.textContent).toContain("Split held back");
		await expect(craft?.textContent).toContain("28 developers");
		await expect(craft?.textContent).not.toContain("You");
		await userEvent.click(canvas.getByRole("button", { name: "Open group Handling failure well" }));
		await expect(onOpen).toHaveBeenCalledOnce();
	},
};

/** Every split held back: the reason shows once above the table, not once per row. */
export const AllHeldBack: Story = {
	args: {
		state: { status: "ready", rows: ROWS.map((each) => ({ ...each, split: WITHHELD })) },
	},
	play: async ({ canvas }) => {
		await expect(canvas.getAllByText("Held back so no one can be singled out.")).toHaveLength(1);
		await expect(
			canvas.getByRole("table", { name: "All practice groups" }),
		).toHaveAccessibleDescription("Held back so no one can be singled out.");
		await expect(canvas.getAllByRole("button", { name: /^Open group /u })).toHaveLength(4);
	},
};

/** Each loading row in the shape of the row it stands for, its link cell left empty. */
export const Loading: Story = {
	args: { state: { status: "loading" } },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("table")).toHaveAttribute("aria-busy", "true");
		await expect(canvas.queryAllByRole("button")).toHaveLength(0);
	},
};

export const Empty: Story = {
	args: { state: { status: "ready", rows: [] } },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("No practices set up yet")).toBeVisible();
	},
};
