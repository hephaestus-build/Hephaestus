import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";

import { Disclosure } from "~/components/common/Disclosure";

const meta = {
	component: Disclosure,
	tags: ["autodocs"],
	args: {
		summary: "Use a self-hosted instance",
		children: <p className="text-sm">The address form lives here.</p>,
	},
} satisfies Meta<typeof Disclosure>;

export default meta;
type Story = StoryObj<typeof meta>;

/** Closed by default; its summary is the native control, focusable and operable without script. */
export const Closed: Story = {
	play: async ({ canvas, userEvent }) => {
		await expect(canvas.getByText("The address form lives here.")).not.toBeVisible();
		await userEvent.tab();
		await expect(canvas.getByText("Use a self-hosted instance")).toHaveFocus();
		await userEvent.click(canvas.getByText("Use a self-hosted instance"));
		await expect(canvas.getByText("The address form lives here.")).toBeVisible();
	},
};

export const Open: Story = {
	args: { defaultOpen: true },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("The address form lives here.")).toBeVisible();
	},
};
