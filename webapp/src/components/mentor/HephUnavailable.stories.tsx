import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, userEvent } from "storybook/test";

import { expectWithinViewport } from "@/stories/reflow";

import { HephUnavailable } from "./HephUnavailable";

/**
 * In the launcher's corner when Heph's panel fails, open or closed. Before it, the launcher and an
 * open panel both vanished until the next page load, with nothing to say why.
 */
const meta = {
	component: HephUnavailable,
	parameters: { layout: "fullscreen" },
	tags: ["autodocs"],
	args: { onRetry: fn() },
} satisfies Meta<typeof HephUnavailable>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ args, canvas }) => {
		const alert = canvas.getByRole("alert");
		await expect(alert).toHaveTextContent("We could not show Heph");
		await expect(alert).toHaveTextContent("Try again. The rest of this page still works.");
		await userEvent.click(canvas.getByRole("button", { name: "Retry" }));
		await expect(args.onRetry).toHaveBeenCalledOnce();
	},
};

export const Dark: Story = { globals: { theme: "dark" } };

/** The message keeps clear of both edges at the narrowest width. */
export const OnAPhone: Story = {
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	play: async ({ canvas }) => {
		await expectWithinViewport(canvas.getByRole("alert"));
		await expectWithinViewport(canvas.getByRole("button", { name: "Retry" }));
	},
};
