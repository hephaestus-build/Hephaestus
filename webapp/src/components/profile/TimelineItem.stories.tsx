import type { Meta, StoryObj } from "@storybook/react";
import { expect } from "storybook/test";

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

/** A one-line label stands level with its dot: the two share one middle. */
export const Default: Story = {
	args: {
		at: new Date(daysBefore(2)),
		label: "2 days ago",
		children: row("Something happened here."),
	},
	play: async ({ canvasElement }) => {
		const dot = canvasElement.querySelector("[data-timeline-dot]");
		const label = canvasElement.querySelector("time");
		if (dot === null || label === null) {
			throw new Error("The timeline row lost its dot or label.");
		}
		await expect(
			Math.abs(middle(dot.getBoundingClientRect()).y - textMiddle(label)),
		).toBeLessThanOrEqual(1);
	},
};

/**
 * Several rows: the rail is one line through every dot. Each row's rail runs from its top into its
 * dot, where the row before ends, and the first row has nothing above its dot.
 */
export const OneLineThroughEveryDot: Story = {
	args: { at: new Date(daysBefore(1)), label: "1 day ago", children: row("Newest.") },
	render: (args) => (
		<>
			<TimelineItem {...args} />
			<TimelineItem at={new Date(daysBefore(3))} label="3 days ago">
				{row("Older.")}
			</TimelineItem>
			<TimelineItem at={new Date(daysBefore(5))} label="5 days ago">
				{row("Oldest.")}
			</TimelineItem>
		</>
	),
	play: async ({ canvasElement }) => {
		const rows = [...canvasElement.querySelectorAll("li")];
		const railsIn = rows.map((item) => item.querySelector("[data-timeline-rail-in]"));
		const dots = rows.map((item) => item.querySelector("[data-timeline-dot]"));
		await expect(rows).toHaveLength(3);
		// Nothing above the first dot.
		await expect(railsIn[0]).not.toBeVisible();
		for (const index of [1, 2]) {
			const railIn = railsIn[index];
			const dot = dots[index];
			const before = rows[index - 1];
			if (railIn == null || dot == null || before === undefined) {
				throw new Error("A timeline row lost its rail or dot.");
			}
			const railBox = railIn.getBoundingClientRect();
			await expect(railIn).toBeVisible();
			// It starts where the row before ends and reaches the middle of this row's dot.
			await expect(
				Math.abs(railBox.top - before.getBoundingClientRect().bottom),
			).toBeLessThanOrEqual(1);
			await expect(
				Math.abs(railBox.bottom - middle(dot.getBoundingClientRect()).y),
			).toBeLessThanOrEqual(1);
		}
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

function middle(box: DOMRect) {
	return { x: box.left + box.width / 2, y: box.top + box.height / 2 };
}

/** The vertical middle of a label's lines, not of its box. */
function textMiddle(element: Element): number {
	const range = document.createRange();
	range.selectNodeContents(element);
	return middle(range.getBoundingClientRect()).y;
}

/**
 * The dot sits on the rail's axis and on the middle of the card's head (60 px high here, as one
 * observation's head is), level with the middle of the day and time beside it.
 */
export const DotOnTheCardHead: Story = {
	args: {
		at: new Date(daysBefore(1)),
		label: (
			<>
				<span className="text-sm font-semibold text-foreground">21 Oct</span>
				4:22 pm
			</>
		),
		children: (
			<div className="rounded-lg border">
				<div data-card-head className="h-[60px] px-3 py-2 text-sm">
					The card’s head, beside the dot.
				</div>
				{row("A body under the head, so the row is taller than its head.")}
			</div>
		),
	},
	render: (args) => (
		<>
			<TimelineItem {...args} />
			<TimelineItem {...args} />
		</>
	),
	play: async ({ canvasElement }) => {
		const dot = canvasElement.querySelector("[data-timeline-dot]");
		const rail = canvasElement.querySelector("[data-timeline-rail]");
		const head = canvasElement.querySelector("[data-card-head]");
		if (dot === null || rail === null || head === null) {
			throw new Error("The timeline row lost its dot, rail or head.");
		}
		const dotBox = dot.getBoundingClientRect();
		const railBox = rail.getBoundingClientRect();
		await expect(Math.abs(middle(dotBox).x - middle(railBox).x)).toBeLessThanOrEqual(0.5);
		await expect(
			Math.abs(middle(dotBox).y - middle(head.getBoundingClientRect()).y),
		).toBeLessThanOrEqual(1);
		await expect(Math.abs(railBox.top - middle(dotBox).y)).toBeLessThanOrEqual(1);
		const label = canvasElement.querySelector("time");
		if (label === null) {
			throw new Error("The timeline row lost its label.");
		}
		await expect(Math.abs(middle(dotBox).y - textMiddle(label))).toBeLessThanOrEqual(1);
	},
};
