import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn } from "storybook/test";

import { UserViewNotices } from "./UserViewNotices";

const meta = {
	component: UserViewNotices,
	tags: ["autodocs"],
	args: { state: { status: "ready", practicesEnabled: false, mentorEnabled: false } },
} satisfies Meta<typeof UserViewNotices>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ canvas }) => {
		await expect(canvas.getByText(/Practices are disabled/)).toBeVisible();
		await expect(canvas.getByText(/Heph is disabled/)).toBeVisible();
	},
};

export const PracticesOnly: Story = {
	args: { state: { status: "ready", practicesEnabled: false, mentorEnabled: true } },
	play: async ({ canvas }) => {
		await expect(canvas.queryByText(/Heph is disabled/)).toBeNull();
	},
};

export const NothingToSay: Story = {
	args: { state: { status: "ready", practicesEnabled: true, mentorEnabled: true } },
	play: async ({ canvas }) => {
		await expect(canvas.queryByRole("note")).toBeNull();
	},
};

export const Loading: Story = { args: { state: { status: "loading" } } };

/** A workspace whose settings did not load is not warned about; a guess would be a wrong claim. */
export const LoadFailed: Story = {
	args: {
		state: { status: "error", error: new Error("Workspace not found"), onRetry: fn() },
	},
	play: async ({ canvas }) => {
		await expect(canvas.queryByRole("note")).toBeNull();
	},
};
