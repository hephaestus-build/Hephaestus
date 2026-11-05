import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, userEvent, within } from "storybook/test";

import { ProductFeedbackButton } from "./ProductFeedbackButton";

const meta = {
	component: ProductFeedbackButton,
	args: { onSendFeedback: fn(), workspaceSlug: "acme" },
	tags: ["autodocs"],
} satisfies Meta<typeof ProductFeedbackButton>;
export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ canvas }) => {
		await expect(
			canvas.getByRole("button", { name: "Send feedback about Hephaestus" }),
		).toBeVisible();
		await expect(canvas.queryByRole("menu")).toBeNull();
		await userEvent.click(canvas.getByRole("button", { name: "Feedback options" }));
		await expect(
			within(await screen.findByRole("menu")).getByRole("menuitem", { name: "Your reports" }),
		).toHaveAttribute("href", "/product-feedback?chromeWorkspace=acme");
	},
};

export const Compact: Story = {
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	play: async ({ canvas }) => {
		await userEvent.click(canvas.getByRole("button", { name: "Feedback options" }));
		const menu = within(await screen.findByRole("menu"));
		await expect(menu.getByRole("menuitem", { name: "Send feedback" })).toBeVisible();
		await expect(menu.getByRole("menuitem", { name: "Your reports" })).toBeVisible();
	},
};
