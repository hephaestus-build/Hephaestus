import type { Meta, StoryObj } from "@storybook/react";

import { daysBefore } from "@/stories/story-clock";

import { TimelineItem } from "./TimelineItem";

/**
 * One row on a vertical timeline: a label column, a dot on a connecting rail, then the content.
 * Rows without a label join the group above — same rail, no dot of their own.
 */
const meta = {
	component: TimelineItem,
	tags: ["autodocs"],
	parameters: { layout: "padded" },
	decorators: [
		(Story) => (
			<ol className="flex flex-col">
				<Story />
			</ol>
		),
	],
} satisfies Meta<typeof TimelineItem>;

export default meta;
type Story = StoryObj<typeof meta>;

const row = (text: string) => <div className="rounded-lg border p-3 text-sm">{text}</div>;

export const Default: Story = {
	args: {
		at: new Date(daysBefore(2)),
		label: "2 days ago",
		children: row("Something happened here."),
	},
};

/** A grouped row: no label and no dot, because the row above already names this moment. */
export const GroupedRow: Story = {
	args: {
		children: row("Same moment as the row above."),
	},
};

/** The last row of a collapsed list: the rail continues as a dashed tail, saying more follows. */
export const ContinuingTail: Story = {
	args: {
		at: new Date(daysBefore(4)),
		label: "4 days ago",
		tailContinues: true,
		children: row("The newest of the entries below the fold."),
	},
};
