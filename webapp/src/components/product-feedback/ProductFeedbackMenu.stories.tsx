import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, userEvent, within } from "storybook/test";

import { expectSettledVisible } from "@/stories/overlay";

import { ProductFeedbackMenu } from "./ProductFeedbackMenu";

const meta = {
	component: ProductFeedbackMenu,
	args: { onSendFeedback: fn(), workspaceSlug: "acme" },
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
		// The name holds every word the item shows (WCAG 2.5.3), the detail too.
		const idea = menu.getByRole("menuitem", {
			name: "Share an idea A feature or change that would help.",
		});
		await expectSettledVisible(idea);
		await expect(menu.getByText("Help make Hephaestus better")).toBeVisible();
		// The person's own reports, in the workspace the header keeps; no static link leaves the instance.
		await expect(menu.getByRole("menuitem", { name: "Your product feedback" })).toHaveAttribute(
			"href",
			"/product-feedback?chromeWorkspace=acme",
		);
		await expect(menu.queryByRole("menuitem", { name: /GitHub/u })).toBeNull();
		await userEvent.click(menu.getByRole("menuitem", { name: /^Report a bug /u }));
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
