import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";

import { ClaimCurrentnessBadge } from "./ClaimCurrentness";

const meta = {
	component: ClaimCurrentnessBadge,
	args: { currentness: "STALE" },
	parameters: { layout: "centered" },
	tags: ["autodocs"],
} satisfies Meta<typeof ClaimCurrentnessBadge>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Stale: Story = {
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Historical observation")).toBeVisible();
	},
};

export const Unverifiable: Story = {
	args: { currentness: "UNVERIFIABLE" },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Unverified observation")).toBeVisible();
	},
};

/** A current observation needs no badge: only an observation that no longer holds says so. */
export const Current: Story = {
	args: { currentness: "CURRENT" },
	play: async ({ canvas }) => {
		await expect(canvas.queryByText(/^(?:Historical|Unverified) observation$/u)).toBeNull();
	},
};
