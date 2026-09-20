import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, within } from "storybook/test";

import { ImpersonationBanner } from "./ImpersonationBanner";

const meta = {
	component: ImpersonationBanner,
	parameters: { layout: "fullscreen" },
	tags: ["autodocs"],
	args: {
		displayName: "Maria K.",
		writesEnabled: false,
		onEnableWrites: fn(),
		onDisableWrites: fn(),
		onExit: fn(),
	},
} satisfies Meta<typeof ImpersonationBanner>;

export default meta;
type Story = StoryObj<typeof meta>;

export const ReadOnly: Story = {};

export const WritesEnabled: Story = { args: { writesEnabled: true } };

export const Exiting: Story = { args: { writesEnabled: true, isExiting: true } };

/** Writes are enabled only through the confirmation, never by the trigger itself. */
export const EnablingWritesAsksFirst: Story = {
	play: async ({ args, canvas, userEvent }) => {
		await userEvent.click(canvas.getByRole("button", { name: "Enable writes" }));
		const dialog = await screen.findByRole("alertdialog");
		await expect(args.onEnableWrites).not.toHaveBeenCalled();
		await userEvent.click(within(dialog).getByRole("button", { name: "Enable writes" }));
		await expect(args.onEnableWrites).toHaveBeenCalledOnce();
	},
};
