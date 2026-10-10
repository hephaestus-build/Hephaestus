import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, within } from "storybook/test";

import { expectSettledVisible } from "@/stories/overlay";

import { HidePersonAction } from "./HidePersonAction";

const meta = {
	component: HidePersonAction,
	parameters: { layout: "centered" },
	tags: ["autodocs"],
	args: { name: "Ada Lovelace", pending: false, onConfirm: fn() },
} satisfies Meta<typeof HidePersonAction>;

export default meta;
type Story = StoryObj<typeof meta>;

/** Hiding asks first, and says where the person goes. */
export const Default: Story = {
	play: async ({ args, canvas, userEvent }) => {
		await userEvent.click(canvas.getByRole("button", { name: "Hide from activity" }));
		const dialog = await screen.findByRole("alertdialog");
		await expectSettledVisible(dialog);
		const step = within(dialog);
		await expect(
			step.getByRole("heading", { name: "Hide Ada Lovelace from activity?" }),
		).toBeVisible();
		await expect(args.onConfirm).not.toHaveBeenCalled();
		await userEvent.click(step.getByRole("button", { name: "Hide" }));
		await expect(args.onConfirm).toHaveBeenCalledOnce();
	},
};

export const Cancelled: Story = {
	play: async ({ args, canvas, userEvent }) => {
		await userEvent.click(canvas.getByRole("button", { name: "Hide from activity" }));
		const dialog = await screen.findByRole("alertdialog");
		await expectSettledVisible(dialog);
		await userEvent.click(within(dialog).getByRole("button", { name: "Cancel" }));
		await expect(args.onConfirm).not.toHaveBeenCalled();
	},
};

export const Hiding: Story = {
	args: { pending: true },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("button", { name: "Hiding…" })).toHaveAttribute(
			"aria-disabled",
			"true",
		);
	},
};

export const Dark: Story = { globals: { theme: "dark" } };
