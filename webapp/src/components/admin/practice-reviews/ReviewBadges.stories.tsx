import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";

import { ClaimCurrentnessAlert } from "./ReviewBadges";

const meta = {
	component: ClaimCurrentnessAlert,
	args: { currentness: "STALE" },
	parameters: { layout: "padded" },
	tags: ["autodocs"],
} satisfies Meta<typeof ClaimCurrentnessAlert>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Stale: Story = {
	play: async ({ canvas }) => {
		await expect(
			canvas.getByText("This was judged against an older version of the practice"),
		).toBeVisible();
	},
};

/**
 * Worded from the reader's question — whether they can trust what they are reading — rather than
 * from the record's property, so it says what is missing, why that leaves the question open, and
 * what to do about it.
 */
export const Unverifiable: Story = {
	args: { currentness: "UNVERIFIABLE" },
	play: async ({ canvas }) => {
		await expect(
			canvas.getByText("We can't tell which version of the practice this was judged against"),
		).toBeVisible();
	},
};

/**
 * A result on the current rules is the norm, and a banner saying so on every observation is a line
 * every reader learns to skip — which is what would make the exceptions above invisible.
 */
export const Current: Story = {
	args: { currentness: "CURRENT" },
	play: async ({ canvasElement }) => {
		await expect(canvasElement.querySelector('[data-slot="alert"]')).toBeNull();
	},
};
