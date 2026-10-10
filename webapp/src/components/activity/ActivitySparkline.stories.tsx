import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";

import { PEOPLE, spanOf } from "@/stories/activity-story-data";

import { weekStarts } from "./activity-tally";
import { ActivitySparkline } from "./ActivitySparkline";

const meta = {
	component: ActivitySparkline,
	parameters: { layout: "centered" },
	tags: ["autodocs"],
	args: { weeks: PEOPLE[0]?.weeks ?? [], span: spanOf("30d") },
} satisfies Meta<typeof ActivitySparkline>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("img")).toHaveAccessibleName(
			/^Busiest week: \d+ contributions?$/u,
		);
	},
};

/** A quiet period draws a flat line at zero and says so. */
export const Empty: Story = {
	args: { weeks: [] },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("img", { name: "No contributions" })).toBeVisible();
	},
};

const YEAR = spanOf("1y");

/** A year of weeks still fits the cell: the line takes every week. */
export const Year: Story = {
	args: {
		span: YEAR,
		weeks: weekStarts(YEAR.from, YEAR.to)
			.slice(10, 12)
			.map((start) => ({ start, contributions: 4 })),
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("img", { name: "Busiest week: 4 contributions" })).toBeVisible();
	},
};
