import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn } from "storybook/test";

import { ProductFeedbackButton } from "./ProductFeedbackButton";

const meta = {
	component: ProductFeedbackButton,
	args: { onSendFeedback: fn() },
	tags: ["autodocs"],
} satisfies Meta<typeof ProductFeedbackButton>;
export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ canvas }) => {
		await expect(
			canvas.getByRole("button", { name: "Send feedback about Hephaestus" }),
		).toBeVisible();
		await expect(canvas.queryByRole("button", { name: "Feedback options" })).toBeNull();
	},
};

export const Compact: Story = {
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	play: async ({ canvas }) => {
		await expect(
			canvas.getByRole("button", { name: "Send feedback about Hephaestus" }),
		).toBeVisible();
	},
};
