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
	args: { recovery: "retry", onRecover: fn() },
} satisfies Meta<typeof HephUnavailable>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ canvas }) => {
		const alert = canvas.getByRole("alert");
		await expect(alert).toHaveTextContent("We could not show Heph");
		await expect(alert).toHaveTextContent("Try again. The rest of this page still works.");
		await expect(canvas.getByRole("button", { name: "Retry" })).toBeEnabled();
	},
};

/** The panel's code did not load. The browser keeps that failure, so only a page load can retry. */
export const CodeDidNotLoad: Story = {
	args: { recovery: "reload" },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("alert")).toHaveTextContent(
			"Reload the page to try again. The rest of this page still works.",
		);
		await expect(canvas.getByRole("button", { name: "Reload page" })).toBeEnabled();
		await expect(canvas.queryByRole("button", { name: "Retry" })).not.toBeInTheDocument();
	},
};

/** The page still works, so the reader can put the message away. */
export const Closed: Story = {
	play: async ({ canvas }) => {
		await userEvent.click(canvas.getByRole("button", { name: "Close" }));
		await expect(canvas.queryByRole("alert")).not.toBeInTheDocument();
	},
};

export const Dark: Story = { globals: { theme: "dark" } };

export const CodeDidNotLoadDark: Story = {
	args: { recovery: "reload" },
	globals: { theme: "dark" },
};

/** The message keeps clear of both edges at the narrowest width. */
export const OnAPhone: Story = {
	args: { recovery: "reload" },
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	play: async ({ canvas }) => {
		await expectWithinViewport(canvas.getByRole("alert"));
		await expectWithinViewport(canvas.getByRole("button", { name: "Reload page" }));
		await expectWithinViewport(canvas.getByRole("button", { name: "Close" }));
	},
};
