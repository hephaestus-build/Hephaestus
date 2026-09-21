import type { Meta, StoryObj } from "@storybook/react";
import { expect, fn, screen, userEvent } from "storybook/test";

import type { InstanceSettings } from "@/api/types.gen";

import { SilentModeCard } from "./SilentModeCard";

const released: InstanceSettings = {
	etag: '"0"',
	silentModeEngaged: false,
	silentModeChangedAt: new Date("2026-07-10T09:30:00Z"),
	silentModeChangedBy: "felixtjdietrich",
};

const engaged: InstanceSettings = {
	etag: '"0"',
	silentModeEngaged: true,
	silentModeReason: "Investigating incident #42",
	silentModeChangedAt: new Date("2026-07-16T08:00:00Z"),
	silentModeChangedBy: "felixtjdietrich",
};

const meta = {
	component: SilentModeCard,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	args: {
		settings: released,
		isPending: false,
		onEngage: fn(),
		onRelease: fn(),
	},
} satisfies Meta<typeof SilentModeCard>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Released: Story = {
	play: async ({ args, canvas }) => {
		await userEvent.click(canvas.getByRole("button", { name: /engage silent mode/iu }));
		await userEvent.type(
			await screen.findByLabelText(/why are you silencing/iu),
			"Investigating incident #42",
		);
		await userEvent.click(screen.getByRole("button", { name: /^engage silent mode$/iu }));
		await expect(args.onEngage).toHaveBeenCalledWith("Investigating incident #42");
	},
};

export const Engaged: Story = {
	args: { settings: engaged },
	play: async ({ args, canvas }) => {
		await userEvent.click(canvas.getByRole("button", { name: /release silent mode/iu }));
		const confirm = await screen.findByRole("button", { name: /^release silent mode$/iu });
		await userEvent.type(screen.getByLabelText(/type/iu), "nope");
		await userEvent.click(confirm);
		await expect(args.onRelease).not.toHaveBeenCalled();
		await expect(await screen.findByText(/does not match/iu)).toBeVisible();

		await userEvent.clear(screen.getByLabelText(/type/iu));
		await userEvent.type(screen.getByLabelText(/type/iu), "release");
		await userEvent.click(confirm);
		await expect(args.onRelease).toHaveBeenCalled();
	},
};

export const Pending: Story = {
	args: { settings: engaged, isPending: true },
};

export const ReleaseUnavailable: Story = {
	args: { settings: engaged, releaseDisabled: true },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("button", { name: /release silent mode/iu })).toBeDisabled();
	},
};
