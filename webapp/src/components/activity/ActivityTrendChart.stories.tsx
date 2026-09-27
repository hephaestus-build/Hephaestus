import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, within } from "storybook/test";

import {
	OVERVIEW,
	QUIET_OVERVIEW,
	readyOverview,
	YEAR_OVERVIEW,
} from "@/stories/activity-story-data";
import { withProvider, withStandardPage } from "@/stories/decorators";
import { expectNoPageOverflow } from "@/stories/reflow";

import { ActivityTrendChart } from "./ActivityTrendChart";

/**
 * A tile grown into a chart: the same bars in the same tones, now with a date axis and a count axis
 * from zero, and the category's chips as the legend. Only kinds of one category share the axis.
 */
const meta = {
	component: ActivityTrendChart,
	decorators: [withStandardPage],
	tags: ["autodocs"],
	args: {
		state: readyOverview(OVERVIEW),
		category: "reviews",
		providerType: "GITHUB",
	},
} satisfies Meta<typeof ActivityTrendChart>;

export default meta;
type Story = StoryObj<typeof meta>;

/**
 * How many bars of one series sit in the same column as a bar of another: stacked series share
 * every column they both have a bar in, grouped series share none.
 */
function sharedColumns(root: HTMLElement): number {
	const columns = [...root.querySelectorAll(".recharts-bar")].map(
		(series) =>
			new Set(
				[...series.querySelectorAll(".recharts-rectangle")].map((bar) =>
					Math.round(bar.getBoundingClientRect().left),
				),
			),
	);
	const [first, ...rest] = columns;
	return [...(first ?? [])].filter((left) => rest.some((other) => other.has(left))).length;
}

export const Default: Story = {
	play: async ({ canvas, canvasElement }) => {
		const figure = canvas.getByRole("figure");
		// The bars are hidden from assistive technology; the figure is named by their numbers.
		await expect(figure).toHaveAccessibleName(/^18 reviews; busiest day \w+ \d+ \w+, \d+$/u);
		// The legend is the category's chips, each saying what it counts.
		for (const phrase of [
			"11 approvals",
			"3 reviews requesting changes",
			"4 comment-only reviews",
		]) {
			await expect(within(figure).getByRole("img", { name: phrase })).toBeVisible();
		}
		await expect(canvasElement.querySelector("[data-slot=chart]")).toHaveAttribute(
			"aria-hidden",
			"true",
		);
		// One series per kind of the category; a review's verdicts partition it, so they stack.
		await expect(canvasElement.querySelectorAll(".recharts-bar")).toHaveLength(3);
		await expect(sharedColumns(canvasElement)).toBeGreaterThan(0);
	},
};

/** Two kinds of one tone: the second is drawn lighter, so the stack still reads as two. */
export const Comments: Story = { args: { category: "comments" } };

/** Opened, merged and closed are steps of one lifecycle: side by side, never stacked. */
export const TwelveMonths: Story = {
	args: { state: readyOverview(YEAR_OVERVIEW, "1y"), category: "pull-requests" },
	play: async ({ canvas, canvasElement }) => {
		await expect(sharedColumns(canvasElement)).toBe(0);
		await expect(canvas.getByRole("figure")).toHaveAccessibleName(/busiest month \w+ \d{4}/u);
	},
};

export const Empty: Story = {
	args: { state: readyOverview(QUIET_OVERVIEW) },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("figure")).toHaveAccessibleName("0 reviews");
	},
};

export const GitLab: Story = {
	decorators: [withProvider("GITLAB")],
	args: { providerType: "GITLAB", category: "pull-requests" },
};

export const Dark: Story = { globals: { theme: "dark" } };

export const Reflow: Story = {
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	play: async () => {
		await expectNoPageOverflow();
	},
};

export const Loading: Story = {
	args: { state: { status: "loading" } },
	play: async ({ canvas }) => {
		await expect(canvas.queryByRole("figure")).not.toBeInTheDocument();
	},
};

const onRetry = fn();

export const Failed: Story = {
	args: { state: { status: "error", error: new Error("Network down"), onRetry } },
	play: async ({ canvas, userEvent }) => {
		await userEvent.click(canvas.getByRole("button", { name: /Retry/u }));
		await expect(onRetry).toHaveBeenCalledOnce();
	},
};
