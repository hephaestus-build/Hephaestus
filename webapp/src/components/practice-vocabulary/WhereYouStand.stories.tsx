import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";

import type { TrendSupport } from "@/api/types.gen";

import { PRACTICE_TREND_DEFS } from "./practice-trend-defs";
import { WhereYouStand } from "./WhereYouStand";

const support: TrendSupport = {
	bundleSize: 4,
	calendarSpanDays: 21,
	credibilityThreshold: 0.8,
	currentOpportunities: 4,
	opportunities: 8,
	opportunitiesUntilComparable: 0,
	previousOpportunities: 4,
	ropeHalfWidth: 0.1,
};

/**
 * The standing and the trend behind a level's header, each with the registry's sentence printed
 * beside it rather than hidden in a tooltip.
 */
const meta = {
	component: WhereYouStand,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	args: {
		standing: "STRENGTH",
		basis: "Your latest work counts most, and older work counts less.",
		direction: "IMPROVING",
		support,
		scope: "practice",
	},
} satisfies Meta<typeof WhereYouStand>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ canvas }) => {
		const region = canvas.getByRole("region", { name: "Where you stand" });
		await expect(region).toHaveTextContent(
			"Recent reviews here were almost entirely positive. Read from four pieces of work. Your latest work counts most, and older work counts less.",
		);
		await expect(region).toHaveTextContent(PRACTICE_TREND_DEFS.IMPROVING.label);
		await expect(region).toHaveTextContent(PRACTICE_TREND_DEFS.IMPROVING.description);
	},
};

/** Below three pieces of work the sentence is an early read, not the registry's pattern. */
export const EarlyRead: Story = {
	args: {
		basis: undefined,
		direction: "INSUFFICIENT_EVIDENCE",
		support: { ...support, currentOpportunities: 1, previousOpportunities: 0, opportunities: 1 },
	},
	play: async ({ canvas }) => {
		const region = canvas.getByRole("region", { name: "Where you stand" });
		await expect(region).toHaveTextContent("An early read from one piece of work.");
		await expect(region).not.toHaveTextContent("almost entirely positive");
	},
};

/**
 * A direction with no evidence behind it is no direction: the chip and the sentence both fall back
 * to "not enough to compare yet", so the two can never say different things.
 */
export const DirectionWithoutSupport: Story = {
	args: { support: undefined },
	play: async ({ canvas }) => {
		const region = canvas.getByRole("region", { name: "Where you stand" });
		await expect(region).toHaveTextContent(PRACTICE_TREND_DEFS.INSUFFICIENT_EVIDENCE.label);
		await expect(region).toHaveTextContent(PRACTICE_TREND_DEFS.INSUFFICIENT_EVIDENCE.description);
		await expect(region).not.toHaveTextContent(PRACTICE_TREND_DEFS.IMPROVING.description);
	},
};

/**
 * A standing no review has settled has no trend at all: a direction over no verdict would be a
 * claim about nothing.
 */
export const NotObserved: Story = {
	args: { standing: "NOT_OBSERVED", basis: undefined, direction: undefined, support: undefined },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("region", { name: "Where you stand" })).not.toHaveTextContent(
			PRACTICE_TREND_DEFS.INSUFFICIENT_EVIDENCE.label,
		);
	},
};

/** Only a review of past work judged it, and such a review never moves a trend. */
export const ReadFromPastWork: Story = {
	args: {
		basis:
			"Read from a review that you asked for or a review of your past work. Neither moves a trend.",
		direction: "INSUFFICIENT_EVIDENCE",
		support: {
			...support,
			currentOpportunities: 0,
			opportunities: 0,
			opportunitiesUntilComparable: 8,
			previousOpportunities: 0,
		},
	},
	play: async ({ canvas }) => {
		const region = canvas.getByRole("region", { name: "Where you stand" });
		await expect(region).toHaveTextContent("No new work has been reviewed yet.");
	},
};
