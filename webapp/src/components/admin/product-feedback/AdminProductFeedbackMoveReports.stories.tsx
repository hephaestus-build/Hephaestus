import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, userEvent, within } from "storybook/test";

import { AdminProductFeedbackMoveReports } from "./AdminProductFeedbackMoveReports";

const meta = {
	component: AdminProductFeedbackMoveReports,
	args: { count: 3, moving: false, onMove: fn() },
	tags: ["autodocs"],
} satisfies Meta<typeof AdminProductFeedbackMoveReports>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Waiting: Story = {
	play: async ({ canvas, args }) => {
		await userEvent.click(canvas.getByRole("button", { name: "Move to GitHub" }));
		await expect(args.onMove).not.toHaveBeenCalled();
		const confirm = within(await screen.findByRole("alertdialog"));
		await userEvent.click(confirm.getByRole("button", { name: "Move to GitHub" }));
		await expect(args.onMove).toHaveBeenCalledOnce();
	},
};

export const OneReport: Story = { args: { count: 1 } };

export const Moving: Story = {
	args: { moving: true },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("button", { name: "Moving…" })).toBeDisabled();
	},
};

export const NothingToMove: Story = {
	args: { count: 0 },
	play: async ({ canvasElement }) => {
		await expect(canvasElement).toHaveTextContent("");
	},
};
