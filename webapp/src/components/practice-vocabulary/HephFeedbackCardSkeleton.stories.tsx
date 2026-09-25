import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";

import { HephFeedbackCardSkeleton } from "./HephFeedbackCard";

/**
 * Heph's card while the overview loads: the card at rest in outline, so the page does not jump
 * when it lands. Its own file because it takes no props, so the card's args have nothing to drive.
 */
const meta = {
	component: HephFeedbackCardSkeleton,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
} satisfies Meta<typeof HephFeedbackCardSkeleton>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ canvas }) => {
		// The mark stays while the card fills, and it is not a control.
		await expect(canvas.getByRole("img", { name: "Heph, AI mentor" })).toBeVisible();
		await expect(canvas.queryByRole("button")).toBeNull();
	},
};
