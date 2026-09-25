import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";

import { PracticeGroupStandingRing } from "./PracticeGroupStandingRing";
import { StandingSummaryBox } from "./StandingSummaryBox";

const counts = { DEVELOPING: 2, MIXED: 3, STRENGTH: 6, NOT_OBSERVED: 1 };

const meta = {
	component: StandingSummaryBox,
	parameters: { layout: "centered" },
	tags: ["autodocs"],
	args: {
		ring: <PracticeGroupStandingRing counts={counts} />,
		label: "12 practices in 4 groups",
		counts,
	},
	argTypes: {
		// The ring is a rendered element, not a value a control can set.
		ring: { control: false },
	},
} satisfies Meta<typeof StandingSummaryBox>;

export default meta;
type Story = StoryObj<typeof meta>;

/** The ring, what it stands for, and one line per standing present, in the ring's own order. */
export const Default: Story = {
	play: async ({ canvas }) => {
		await expect(canvas.getByText("12 practices in 4 groups")).toBeVisible();
		const legend = canvas.getByRole("list", { name: "Practices by standing" });
		await expect(legend.children).toHaveLength(4);
	},
};

/**
 * Nothing counted yet: the ring is its hairline track and there is no legend, since an empty one
 * would claim a count of nothing.
 */
export const NothingCounted: Story = {
	args: {
		ring: <PracticeGroupStandingRing counts={{}} />,
		label: "No practices in this group",
		counts: {},
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText("No practices in this group")).toBeVisible();
		await expect(canvas.queryByRole("list")).toBeNull();
	},
};

/**
 * Loading: skeletons stand in for the ring, the label and the legend. The counts are not shown at
 * all rather than shown as zero, which would be a claim about the practices.
 */
export const Loading: Story = {
	args: { isLoading: true },
	play: async ({ canvas, canvasElement }) => {
		await expect(canvas.queryByText("12 practices in 4 groups")).toBeNull();
		await expect(canvas.queryByRole("list")).toBeNull();
		await expect(canvasElement.querySelectorAll('[data-slot="skeleton"]')).toHaveLength(5);
	},
};
