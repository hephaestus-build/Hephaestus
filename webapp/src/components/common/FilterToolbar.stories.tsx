import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, userEvent } from "storybook/test";

import { FilterToolbar } from "./FilterToolbar";

const meta = {
	component: FilterToolbar,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	args: {
		hasFilter: false,
		onReset: fn(),
		children: <span>Filter controls</span>,
	},
} satisfies Meta<typeof FilterToolbar>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ canvas }) => {
		canvas.getByText("Filter controls");
		await expect(canvas.queryByRole("button", { name: "Reset" })).not.toBeInTheDocument();
	},
};

export const Filtered: Story = {
	args: { hasFilter: true },
	play: async ({ args, canvas }) => {
		await userEvent.click(canvas.getByRole("button", { name: "Reset" }));
		await expect(args.onReset).toHaveBeenCalledOnce();
	},
};
