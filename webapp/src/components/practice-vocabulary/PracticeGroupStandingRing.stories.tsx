import type { Meta, StoryObj } from "@storybook/react";
import { expect } from "storybook/test";

import { PracticeGroupStandingRing } from "./PracticeGroupStandingRing";

/**
 * The ring is `aria-hidden`, so no role reaches its arcs; the arithmetic is read off the circles
 * themselves, in the registry's order.
 */
function arcs(canvasElement: HTMLElement) {
	return [...canvasElement.querySelectorAll("circle")].map((circle) => ({
		dasharray: circle.getAttribute("stroke-dasharray"),
		dashoffset: circle.getAttribute("stroke-dashoffset"),
	}));
}

const meta = {
	component: PracticeGroupStandingRing,
	parameters: {
		layout: "centered",
		docs: {
			description: {
				component:
					"The distribution of a group's practices, drawn as one arc per standing in the registry's " +
					"worst-first order. Purely decorative — the same counts appear as text beside it — so it " +
					"is `aria-hidden`. It takes the counts the legend beside it reads, and comes in two sizes: " +
					"large beside a summary box, small in a table cell. What the stories below pin down is " +
					"the arc arithmetic, which has three cases a two-segment example never reaches.",
			},
		},
	},
	tags: ["autodocs"],
} satisfies Meta<typeof PracticeGroupStandingRing>;

export default meta;
type Story = StoryObj<typeof meta>;
/** One standing alone takes the whole circumference: no gap to leave for a neighbour it has not got. */
export const SingleSegment: Story = {
	args: { counts: { STRENGTH: 3 } },
	play: async ({ canvasElement }) => {
		await expect(arcs(canvasElement)).toStrictEqual([{ dasharray: "100 0", dashoffset: "0" }]);
	},
};

export const TwoSegments: Story = {
	args: { counts: { DEVELOPING: 1, STRENGTH: 1 } },
};
export const AllStandings: Story = {
	args: {
		counts: { DEVELOPING: 2, MIXED: 3, STRENGTH: 4, NO_OPPORTUNITY: 2, NOT_OBSERVED: 1 },
	},
};
/**
 * A share narrower than the gap between neighbours: the arc is clamped to a half-unit hairline
 * rather than inverted, and stays centred in the share it stands for.
 */
export const SliverSegment: Story = {
	args: { counts: { STRENGTH: 39, DEVELOPING: 1 } },
	play: async ({ canvasElement }) => {
		await expect(arcs(canvasElement)).toStrictEqual([
			// 1 of 40 is a 2.5 share; 2.5 - 3 is negative, so the arc is the floor, centred on it.
			{ dasharray: "0.5 99.5", dashoffset: "-1" },
			{ dasharray: "94.5 5.5", dashoffset: "-4" },
		]);
	},
};
/** Nothing counted yet: the track is a hairline circle rather than nothing at all. */
export const NoPractices: Story = {
	args: { counts: {} },
	play: async ({ canvasElement }) => {
		await expect(arcs(canvasElement)).toStrictEqual([{ dasharray: null, dashoffset: null }]);
		await expect(canvasElement.querySelector("circle")).toHaveAttribute("stroke-width", "1");
	},
};

/** The large ring, beside a summary box's counts in the page header and a group's header. */
export const Large: Story = {
	args: {
		counts: { STRENGTH: 7, MIXED: 3, DEVELOPING: 2, NO_OPPORTUNITY: 1, NOT_OBSERVED: 3 },
		size: "lg",
	},
};

/** The small ring, in a table cell, with the stroke thicker so the arcs read at that size. */
export const Small: Story = {
	args: {
		counts: { DEVELOPING: 2, MIXED: 1, STRENGTH: 2 },
		size: "sm",
	},
};
