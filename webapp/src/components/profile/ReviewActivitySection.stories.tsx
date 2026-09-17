import type { Meta, StoryObj } from "@storybook/react";
import { expect, fn } from "storybook/test";

import { reviewActivity } from "./fixtures";
import { ReviewActivitySection } from "./ReviewActivitySection";

const meta = {
	component: ReviewActivitySection,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	args: {
		providerType: "GITHUB",
		reviewActivity,
		isLoading: false,
		emptyMessage: "No review activity that counts yet. Try a wider timeframe.",
		canViewAll: true,
		onViewAll: fn(),
	},
} satisfies Meta<typeof ReviewActivitySection>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ args, canvas, userEvent }) => {
		await expect(canvas.getByRole("heading", { name: "Review activity" })).toBeVisible();
		await userEvent.click(canvas.getByRole("button", { name: "View all review activity" }));
		await expect(args.onViewAll).toHaveBeenCalledOnce();
	},
};

export const Loading: Story = {
	args: { isLoading: true, canViewAll: false },
	play: async ({ canvas }) => {
		await expect(
			canvas.getByRole("heading", { name: "Review activity" }).closest("[aria-busy]"),
		).toHaveAttribute("aria-busy", "true");
		// The fixture rows are still in `args`: loading takes precedence over data already given.
		await expect(canvas.queryByText("Add new feature to dashboard")).toBeNull();
	},
};

export const Empty: Story = {
	args: { reviewActivity: [], canViewAll: false },
	play: async ({ canvas }) => {
		await expect(
			canvas.getByText("No review activity that counts yet. Try a wider timeframe."),
		).toBeVisible();
		await expect(canvas.queryByRole("button", { name: "View all review activity" })).toBeNull();
	},
};
