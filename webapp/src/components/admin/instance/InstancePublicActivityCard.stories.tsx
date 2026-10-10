import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn } from "storybook/test";

import { expectUnavailable } from "@/test/controls";

import { InstancePublicActivityCard } from "./InstancePublicActivityCard";

const meta = {
	component: InstancePublicActivityCard,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	args: {
		state: { status: "ready", allowed: false, pending: false },
		onAllowedChange: fn(),
	},
} satisfies Meta<typeof InstancePublicActivityCard>;

export default meta;
type Story = StoryObj<typeof meta>;

const SWITCH = "Allow public activity pages";

export const Off: Story = {
	play: async ({ args, canvas, userEvent }) => {
		await expect(canvas.getByText("Off. No workspace can publish a page.")).toBeVisible();
		await userEvent.click(canvas.getByRole("switch", { name: SWITCH }));
		await expect(args.onAllowedChange).toHaveBeenCalledWith(true);
	},
};

export const On: Story = {
	args: { state: { status: "ready", allowed: true, pending: false } },
	play: async ({ args, canvas, userEvent }) => {
		await expect(canvas.getByRole("switch", { name: SWITCH })).toBeChecked();
		await userEvent.click(canvas.getByRole("switch", { name: SWITCH }));
		await expect(args.onAllowedChange).toHaveBeenCalledWith(false);
	},
};

export const Saving: Story = {
	args: { state: { status: "ready", allowed: true, pending: true } },
	play: async ({ canvas }) => {
		await expectUnavailable(canvas.getByRole("switch", { name: SWITCH }));
	},
};

export const Loading: Story = {
	args: { state: { status: "loading" } },
	play: async ({ canvas }) => {
		await expect(canvas.queryByRole("switch")).toBeNull();
	},
};

export const Failed: Story = {
	args: { state: { status: "error", error: new Error("Network down"), onRetry: fn() } },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("button", { name: /Retry/u })).toBeVisible();
	},
};

export const Dark: Story = {
	globals: { theme: "dark" },
};
