import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, within } from "storybook/test";

import { activeShare, ownShares } from "@/components/support-share/fixtures";
import { withStandardPage } from "@/stories/decorators";

import { SupportSharesSection } from "./SupportSharesSection";

const meta = {
	component: SupportSharesSection,
	parameters: { layout: "fullscreen" },
	decorators: [withStandardPage],
	tags: ["autodocs"],
	args: { state: { status: "ready", shares: ownShares }, onRevoke: fn() },
} satisfies Meta<typeof SupportSharesSection>;

export default meta;
type Story = StoryObj<typeof meta>;

/** Each share says who reads it, until when, and every read; only an active one can be revoked. */
export const Default: Story = {
	play: async ({ args, canvas, userEvent }) => {
		const shares = within(canvas.getByRole("list", { name: "Your support shares" }));
		await expect(shares.getAllByRole("listitem")).toHaveLength(3);
		await expect(shares.getByText(/Read 2 times/u)).toBeVisible();
		await expect(shares.getAllByRole("button", { name: "Revoke" })).toHaveLength(1);
		await userEvent.click(shares.getByRole("button", { name: "Revoke" }));
		await expect(args.onRevoke).toHaveBeenCalledWith(activeShare.id);
	},
};

export const Empty: Story = {
	args: { state: { status: "ready", shares: [] } },
	play: async ({ canvas }) => {
		await expect(canvas.getByText(/You have not shared a question/u)).toBeVisible();
		await expect(canvas.queryByRole("button", { name: "Revoke" })).toBeNull();
	},
};

export const Loading: Story = {
	args: { state: { status: "loading" } },
	play: async ({ canvas }) => {
		await expect(canvas.getByLabelText("Loading support shares…")).toBeVisible();
	},
};

export const LoadFailed: Story = {
	args: { state: { status: "error", error: { status: 500 }, onRetry: fn() } },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("We could not load your support shares")).toBeVisible();
	},
};

export const Revoking: Story = {
	args: { revoking: activeShare.id },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("button", { name: "Revoke" })).toHaveAttribute(
			"aria-disabled",
			"true",
		);
	},
};
