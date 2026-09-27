import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn } from "storybook/test";

import { ConsentPrompt } from "~/components/options/ConsentPrompt";

const meta = {
	component: ConsentPrompt,
	tags: ["autodocs"],
	args: {
		instanceHost: "hephaestus.build",
		webAppOrigin: "https://hephaestus.build",
		checking: false,
		onCheckAgain: fn(),
	},
} satisfies Meta<typeof ConsentPrompt>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ canvas, userEvent, args }) => {
		await expect(canvas.getByRole("link", { name: /Open Hephaestus/u })).toHaveAttribute(
			"href",
			"https://hephaestus.build",
		);
		await userEvent.click(canvas.getByRole("button", { name: "I have accepted it" }));
		await expect(args.onCheckAgain).toHaveBeenCalledOnce();
	},
};

export const Checking: Story = {
	args: { checking: true },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("button", { name: "Checking…" })).toBeDisabled();
	},
};
