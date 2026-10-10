import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, waitFor, within } from "storybook/test";

import { settledPopup } from "@/stories/overlay";
import { daysBefore } from "@/stories/story-clock";

import { periodLabel } from "./activity-period";
import { ActivityPeriodPicker } from "./ActivityPeriodPicker";

const meta = {
	component: ActivityPeriodPicker,
	parameters: { layout: "centered" },
	tags: ["autodocs"],
	args: {
		period: { kind: "preset", preset: "90d" },
		onPeriodChange: fn(),
		updating: false,
	},
} satisfies Meta<typeof ActivityPeriodPicker>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ args, canvas, userEvent }) => {
		await expect(canvas.getByRole("button", { name: "90 days" })).toHaveAttribute(
			"aria-pressed",
			"true",
		);
		await userEvent.click(canvas.getByRole("button", { name: "All time" }));
		await expect(args.onPeriodChange).toHaveBeenCalledWith({ kind: "preset", preset: "all" });
	},
};

/** The calendar applies a range only when asked, so a half-picked range is never counted. */
export const PickCustomRange: Story = {
	play: async ({ args, canvas, userEvent }) => {
		await userEvent.click(canvas.getByRole("button", { name: "Custom range" }));
		const popup = within(await settledPopup());
		const apply = popup.getByRole("button", { name: "Apply" });
		await expect(apply).toBeDisabled();
		// Last month is wholly in the past, so every day of it can be picked.
		await userEvent.click(popup.getByRole("button", { name: /previous month/iu }));
		await userEvent.click(popup.getByRole("button", { name: / 10th, /u }));
		await userEvent.click(popup.getByRole("button", { name: / 12th, /u }));
		await expect(args.onPeriodChange).not.toHaveBeenCalled();
		await userEvent.click(apply);
		await expect(args.onPeriodChange).toHaveBeenCalledWith(
			expect.objectContaining({ kind: "custom" }),
		);
		// Applying closes the calendar.
		await waitFor(async () =>
			expect(screen.queryByRole("button", { name: "Apply" })).not.toBeInTheDocument(),
		);
	},
};

/** A custom range names its days on its button, and no preset is pressed. */
export const Custom: Story = {
	args: { period: { kind: "custom", from: daysBefore(40), to: daysBefore(10) } },
	play: async ({ args, canvas }) => {
		await expect(canvas.getByRole("button", { name: periodLabel(args.period) })).toBeVisible();
		await expect(canvas.getByRole("button", { name: "90 days" })).toHaveAttribute(
			"aria-pressed",
			"false",
		);
	},
};

/** While the previous period's figures stand in, the control says so after a moment. */
export const Updating: Story = {
	args: { updating: true },
	play: async ({ canvas }) => {
		await expect(await canvas.findByText("Updating…", undefined, { timeout: 3000 })).toBeVisible();
	},
};

/** On a narrow screen the presets are one select, and the custom range stays beside it. */
export const Reflow: Story = {
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("combobox", { name: "Time range" })).toBeVisible();
		await expect(canvas.getByRole("button", { name: "Custom range" })).toBeVisible();
	},
};
