import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, within } from "storybook/test";

import { StandingSummaryBox } from "./StandingSummaryBox";

const counts = { DEVELOPING: 2, MIXED: 3, STRENGTH: 6, NOT_OBSERVED: 1 };

const meta = {
	component: StandingSummaryBox,
	parameters: { layout: "centered" },
	tags: ["autodocs"],
	args: {
		label: "12 practices in 4 groups",
		counts,
	},
} satisfies Meta<typeof StandingSummaryBox>;

export default meta;
type Story = StoryObj<typeof meta>;

/** The ring, what it stands for, and one line per standing present, in the ring's own order. */
export const Default: Story = {
	play: async ({ canvas }) => {
		const legend = canvas.getByRole("list", { name: "Practices by standing" });
		await expect(
			within(legend)
				.getAllByRole("listitem")
				.map((item) => item.textContent),
		).toStrictEqual(["2Needs attention", "3Mixed", "6Going well", "1Not observed"]);
	},
};

/**
 * Across the page, as the practice profile's header lays it: the legend on one line that wraps,
 * and what the summary leads to at the end.
 */
export const Fill: Story = {
	parameters: { layout: "padded" },
	args: {
		layout: "fill",
		children: <span className="text-sm font-medium">See all practice groups</span>,
	},
};

/**
 * Nothing counted yet: the ring is its hairline track and there is no legend, since an empty one
 * would claim a count of nothing.
 */
export const NothingCounted: Story = {
	args: {
		label: "No practices in this group",
		counts: {},
	},
	play: async ({ canvas }) => {
		await expect(canvas.queryByRole("list")).toBeNull();
	},
};

/**
 * Loading: skeletons stand in for the ring, the label and the legend. The counts are not shown at
 * all rather than shown as zero, which would be a claim about the practices.
 */
export const Loading: Story = {
	args: { isLoading: true },
	play: async ({ canvas }) => {
		await expect(canvas.queryByText("12 practices in 4 groups")).toBeNull();
		await expect(canvas.queryByRole("list")).toBeNull();
	},
};
