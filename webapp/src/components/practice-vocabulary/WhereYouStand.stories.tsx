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
		basis: "Based on your latest four pieces of reviewed work.",
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
		await expect(region).toHaveTextContent(PRACTICE_TREND_DEFS.IMPROVING.label);
		await expect(region).toHaveTextContent(PRACTICE_TREND_DEFS.IMPROVING.description);
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
