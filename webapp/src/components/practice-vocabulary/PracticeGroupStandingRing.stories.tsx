import type { Meta, StoryObj } from "@storybook/react";

import { PracticeGroupStandingRing } from "./PracticeGroupStandingRing";

const meta = {
	title: "Shared/Practice vocabulary/Practice group standing ring",
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
export const SingleSegment: Story = {
	args: { counts: { STRENGTH: 3 } },
};

export const TwoSegments: Story = {
	args: { counts: { DEVELOPING: 1, STRENGTH: 1 } },
};
export const AllStandings: Story = {
	args: {
		counts: { DEVELOPING: 2, MIXED: 3, STRENGTH: 4, NO_OPPORTUNITY: 2, NOT_OBSERVED: 1 },
	},
};
export const SliverSegment: Story = {
	args: { counts: { STRENGTH: 39, DEVELOPING: 1 } },
};
/** Nothing counted yet: the track is a hairline circle rather than nothing at all. */
export const NoPractices: Story = {
	args: { counts: {} },
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
