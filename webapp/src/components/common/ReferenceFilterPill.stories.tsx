import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, userEvent } from "storybook/test";

import { ReferenceFilterPill } from "./ReferenceFilterPill";

const meta = {
	component: ReferenceFilterPill,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	args: { label: "Actor", id: 7, onClear: fn() },
} satisfies Meta<typeof ReferenceFilterPill>;

export default meta;
type Story = StoryObj<typeof meta>;

export const WithName: Story = {
	args: { name: "Grace Hopper" },
	play: async ({ canvas }) => {
		canvas.getByText("Actor: Grace Hopper");
	},
};

export const IdOnly: Story = {
	args: {},
	play: async ({ canvas }) => {
		canvas.getByText("Actor: #7");
	},
};

export const ClearsTheFilter: Story = {
	args: { name: "Grace Hopper" },
	play: async ({ canvas, args }) => {
		await userEvent.click(
			canvas.getByRole("button", { name: /clear actor filter \(Grace Hopper\)/iu }),
		);
		await expect(args.onClear).toHaveBeenCalledOnce();
	},
};
