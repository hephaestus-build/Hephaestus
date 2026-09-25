import type { Meta, StoryObj } from "@storybook/react";

import { daysBefore } from "@/stories/story-clock";

import { TimelineItem } from "./TimelineItem";

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

/** The last row of a collapsed list: the rail continues as a dashed tail, saying more follows. */
export const ContinuingTail: Story = {
	args: {
		at: new Date(daysBefore(4)),
		label: "4 days ago",
		tailContinues: true,
		children: row("The newest of the entries below the fold."),
	},
};
