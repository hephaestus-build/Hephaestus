import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn } from "storybook/test";

import { ServerTooOldNotice } from "./ServerTooOldNotice";

const meta = {
	component: ServerTooOldNotice,
	args: {
		instanceHost: "hephaestus.example",
		serverVersion: "0.95.1",
		oldestServer: "1.0.0",
		checking: false,
		onCheckAgain: fn(),
	},
} satisfies Meta<typeof ServerTooOldNotice>;

export default meta;
type Story = StoryObj<typeof meta>;

/** The server says which version it runs. */
export const KnownVersion: Story = {
	play: async ({ canvas, args, userEvent }) => {
		await expect(canvas.getByText(/runs version 0\.95\.1/u)).toBeVisible();
		await expect(canvas.getByText(/needs version 1\.0\.0 or newer/u)).toBeVisible();
		await userEvent.click(canvas.getByRole("button", { name: "Check again" }));
		await expect(args.onCheckAgain).toHaveBeenCalledOnce();
	},
};

/** A server from before 1.0 does not say, and the text does not guess. */
export const UnknownVersion: Story = {
	args: { serverVersion: undefined },
	play: async ({ canvas }) => {
		await expect(canvas.getByText(/runs a version older than 1\.0\.0/u)).toBeVisible();
	},
};

/** A check is on its way; the control cannot send a second one. */
export const Checking: Story = {
	args: { checking: true },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("button", { name: "Checking…" })).toBeDisabled();
	},
};
