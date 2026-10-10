import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn } from "storybook/test";

import { expectNoPageOverflow } from "@/stories/reflow";
import { expectUnavailable } from "@/test/controls";

import { PublicActivitySection } from "./PublicActivitySection";

const meta = {
	component: PublicActivitySection,
	parameters: { layout: "centered" },
	tags: ["autodocs"],
	args: { visible: true, onVisibleChange: fn() },
} satisfies Meta<typeof PublicActivitySection>;

export default meta;
type Story = StoryObj<typeof meta>;

const SWITCH = "Show me on public activity pages";

/** A person shows by default; one press hides them everywhere, and the switch says so at once. */
export const Visible: Story = {
	play: async ({ args, canvas, userEvent }) => {
		const control = canvas.getByRole("switch", { name: SWITCH });
		await expect(control).toBeChecked();
		await userEvent.click(control);
		await expect(args.onVisibleChange).toHaveBeenCalledWith(false);
	},
};

export const Hidden: Story = {
	args: { visible: false },
	play: async ({ args, canvas, userEvent }) => {
		const control = canvas.getByRole("switch", { name: SWITCH });
		await expect(control).not.toBeChecked();
		await userEvent.click(control);
		await expect(args.onVisibleChange).toHaveBeenCalledWith(true);
	},
};

export const Loading: Story = {
	args: { isLoading: true },
	play: async ({ canvas }) => {
		await expectUnavailable(canvas.getByRole("switch", { name: SWITCH }));
	},
};

export const Failed: Story = {
	args: { isError: true, error: new Error("Network down"), onRetry: fn() },
	play: async ({ args, canvas, userEvent }) => {
		await expect(canvas.queryByRole("switch")).not.toBeInTheDocument();
		await userEvent.click(canvas.getByRole("button", { name: /Retry/u }));
		await expect(args.onRetry).toHaveBeenCalledOnce();
	},
};

export const Dark: Story = { globals: { theme: "dark" } };

export const Reflow: Story = {
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	play: expectNoPageOverflow,
};
