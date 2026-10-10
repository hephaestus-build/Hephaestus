import type { Meta, StoryObj } from "@storybook/react-vite";
import { format, setDate, startOfMonth, subMonths } from "date-fns";
import { expect, fn, screen, waitFor, within } from "storybook/test";

import { settledPopup } from "@/stories/overlay";
import { daysBefore, STORY_NOW } from "@/stories/story-clock";

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
		const thisMonth = startOfMonth(new Date(STORY_NOW));
		const lastMonth = subMonths(thisMonth, 1);
		const grids = popup.getAllByRole("grid");
		await expect(grids).toHaveLength(2);
		await userEvent.click(
			within(popup.getByRole("grid", { name: format(lastMonth, "MMMM yyyy") })).getByRole(
				"button",
				{ name: / 20th, /u },
			),
		);
		await userEvent.click(
			within(popup.getByRole("grid", { name: format(thisMonth, "MMMM yyyy") })).getByRole(
				"button",
				{ name: / 1st, /u },
			),
		);
		await expect(args.onPeriodChange).not.toHaveBeenCalled();
		await userEvent.click(apply);
		await expect(args.onPeriodChange).toHaveBeenCalledWith({
			kind: "custom",
			from: setDate(lastMonth, 20),
			to: thisMonth,
		});
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
		await expect(
			canvas.getByRole("button", { name: `${periodLabel(args.period)}, custom range` }),
		).toBeVisible();
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
	play: async ({ canvas, userEvent }) => {
		await expect(canvas.getByRole("combobox", { name: "Time range" })).toBeVisible();
		await userEvent.click(canvas.getByRole("button", { name: "Custom range" }));
		const grids = within(await settledPopup()).getAllByRole("grid");
		await expect(grids).toHaveLength(2);
		for (const grid of grids) {
			await expect(grid.getBoundingClientRect().left).toBeGreaterThanOrEqual(0);
			await expect(grid.getBoundingClientRect().right).toBeLessThanOrEqual(window.innerWidth);
		}
	},
};
