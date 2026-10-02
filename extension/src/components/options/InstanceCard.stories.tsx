import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn } from "storybook/test";

import { InstanceCard } from "~/components/options/InstanceCard";

const meta = {
	component: InstanceCard,
	tags: ["autodocs"],
	args: { host: "hephaestus.build", hosted: true, disconnecting: false, onDisconnect: fn() },
} satisfies Meta<typeof InstanceCard>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Hosted: Story = {
	play: async ({ canvas, userEvent, args }) => {
		await expect(canvas.getByText("Hosted Hephaestus")).toBeVisible();
		await userEvent.click(canvas.getByRole("button", { name: "Change instance…" }));
		await userEvent.click(canvas.getByRole("button", { name: "Keep it" }));
		await expect(args.onDisconnect).not.toHaveBeenCalled();
		await userEvent.click(canvas.getByRole("button", { name: "Change instance…" }));
		await userEvent.click(canvas.getByRole("button", { name: "Disconnect" }));
		await expect(args.onDisconnect).toHaveBeenCalledOnce();
	},
};

export const SelfHosted: Story = {
	args: { host: "hephaestus.lrz.example", hosted: false },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Self-hosted instance")).toBeVisible();
	},
};

export const Disconnecting: Story = {
	args: { disconnecting: true },
	play: async ({ canvas, userEvent }) => {
		await userEvent.click(canvas.getByRole("button", { name: "Change instance…" }));
		await expect(canvas.getByRole("button", { name: "Disconnecting…" })).toBeDisabled();
	},
};
