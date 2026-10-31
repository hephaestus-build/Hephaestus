import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, userEvent, within } from "storybook/test";

import { expectSettledVisible } from "@/stories/overlay";

import { ProductFeedbackMenu } from "./ProductFeedbackMenu";

const meta = {
	component: ProductFeedbackMenu,
	args: { onSendFeedback: fn() },
	decorators: [
		(Story) => (
			<div className="flex justify-end p-4">
				<Story />
			</div>
		),
	],
	tags: ["autodocs"],
} satisfies Meta<typeof ProductFeedbackMenu>;
export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ canvas, args }) => {
		await userEvent.click(canvas.getByRole("button", { name: "Product feedback" }));
		const menu = within(await screen.findByRole("menu"));
		const idea = menu.getByRole("menuitem", { name: "Share an idea" });
		await expectSettledVisible(idea);
		// The detail is announced as a description, not folded into the item's name.
		await expect(idea).toHaveAccessibleDescription("A feature or change that would help.");
		await expect(menu.getByText("Help make Hephaestus better")).toBeVisible();
		await expect(menu.getByRole("menuitem", { name: /Open an issue on GitHub/u })).toHaveAttribute(
			"href",
			"https://github.com/hephaestus-build/Hephaestus/issues/new/choose",
		);
		await userEvent.click(menu.getByRole("menuitem", { name: "Report a bug" }));
		await expect(args.onSendFeedback).toHaveBeenCalledWith("BUG");
	},
};

/** On a narrow screen the label folds away; the accessible name stays. */
export const Compact: Story = {
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("button", { name: "Product feedback" })).toBeVisible();
	},
};
