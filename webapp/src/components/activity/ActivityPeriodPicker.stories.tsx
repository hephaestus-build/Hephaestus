import type { Meta, StoryObj } from "@storybook/react-vite";
import { format, setDate, startOfMonth, subMonths } from "date-fns";
import { expect, fn, screen, waitFor, within } from "storybook/test";

import { settledPopup } from "@/stories/overlay";
import { expectControlOnScreen, expectWithinViewport } from "@/stories/reflow";
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

const SLACK_PX = 1;

/** The one box that scrolls the months, so a month below the fold can be reached. */
function monthsScroller(popup: HTMLElement): HTMLElement {
	const scroller = [...popup.querySelectorAll<HTMLElement>("*")].find(
		(node) =>
			node.scrollHeight > node.clientHeight + SLACK_PX &&
			["auto", "scroll"].includes(getComputedStyle(node).overflowY),
	);
	if (scroller == null) {
		throw new Error(
			"Nothing in the calendar scrolls, so a month below the fold cannot be reached.",
		);
	}
	return scroller;
}

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

/**
 * The calendar applies a range only when asked, so a half-picked range is never counted. Closing it
 * drops the draft, and on a wide screen both months and Apply fit beside each other.
 */
export const PickCustomRange: Story = {
	parameters: { viewport: { defaultViewport: "desktop" } },
	play: async ({ args, canvas, userEvent }) => {
		await expect([window.innerWidth, window.innerHeight]).toStrictEqual([1440, 900]);
		const trigger = canvas.getByRole("button", { name: "Custom range" });
		const thisMonth = startOfMonth(new Date(STORY_NOW));
		const lastMonth = subMonths(thisMonth, 1);

		await userEvent.click(trigger);
		const dismissedElement = await settledPopup();
		// Opening puts the keyboard on the day grid.
		await expect(dismissedElement.contains(document.activeElement)).toBe(true);
		const dismissed = within(dismissedElement);
		await userEvent.click(
			within(dismissed.getByRole("grid", { name: format(lastMonth, "MMMM yyyy") })).getByRole(
				"button",
				{ name: / 20 /u },
			),
		);
		await expect(dismissed.getByRole("button", { name: "Apply" })).toBeEnabled();
		await userEvent.keyboard("{Escape}");
		await waitFor(async () =>
			expect(screen.queryByRole("button", { name: "Apply" })).not.toBeInTheDocument(),
		);
		await expect(trigger).toHaveFocus();
		await expect(args.onPeriodChange).not.toHaveBeenCalled();

		await userEvent.click(trigger);
		const popupElement = await settledPopup();
		const popup = within(popupElement);
		await expect(popup.getByText("Pick the first day")).toBeVisible();
		const apply = popup.getByRole("button", { name: "Apply" });
		await expect(apply).toBeDisabled();
		const grids = popup.getAllByRole("grid");
		await expect(grids).toHaveLength(2);
		const [lastMonthGrid, thisMonthGrid] = grids;
		await expect(thisMonthGrid?.getBoundingClientRect().left).toBeGreaterThan(
			lastMonthGrid?.getBoundingClientRect().right ?? Number.POSITIVE_INFINITY,
		);
		await expectWithinViewport(popupElement);
		await expectControlOnScreen(apply);
		await userEvent.click(
			within(popup.getByRole("grid", { name: format(lastMonth, "MMMM yyyy") })).getByRole(
				"button",
				{ name: / 20 /u },
			),
		);
		await userEvent.click(
			within(popup.getByRole("grid", { name: format(thisMonth, "MMMM yyyy") })).getByRole(
				"button",
				{ name: / 1 /u },
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

/**
 * On a narrow screen the presets are one select, and the custom range stays beside it. Halfway down
 * the screen, as on an activity page, the calendar keeps to the space beside its button: its months
 * scroll, and Apply stays on screen.
 */
export const Reflow: Story = {
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	decorators: [
		(Story) => (
			<div className="pt-64">
				<Story />
			</div>
		),
	],
	play: async ({ args, canvas, userEvent }) => {
		await expect([window.innerWidth, window.innerHeight]).toStrictEqual([320, 568]);
		await expect(canvas.getByRole("combobox", { name: "Time range" })).toBeVisible();
		const trigger = canvas.getByRole("button", { name: "Custom range" });
		await expect(trigger.getBoundingClientRect().top).toBeGreaterThan(window.innerHeight / 3);
		await userEvent.click(trigger);
		const popup = await settledPopup();
		const grids = within(popup).getAllByRole("grid");
		await expect(grids).toHaveLength(2);
		for (const grid of grids) {
			await expect(grid.getBoundingClientRect().left).toBeGreaterThanOrEqual(0);
			await expect(grid.getBoundingClientRect().right).toBeLessThanOrEqual(window.innerWidth);
		}
		const apply = within(popup).getByRole("button", { name: "Apply" });
		await expectWithinViewport(popup);
		await expectControlOnScreen(apply);

		const scroller = monthsScroller(popup);
		await expect(scroller.scrollWidth).toBeLessThanOrEqual(scroller.clientWidth + SLACK_PX);
		scroller.scrollTop = scroller.scrollHeight;
		const thisMonthGrid = within(popup).getByRole("grid", {
			name: format(startOfMonth(new Date(STORY_NOW)), "MMMM yyyy"),
		});
		await waitFor(async () =>
			expect(thisMonthGrid.getBoundingClientRect().bottom).toBeLessThanOrEqual(
				scroller.getBoundingClientRect().bottom + SLACK_PX,
			),
		);
		await expectWithinViewport(thisMonthGrid);
		await expectControlOnScreen(apply);

		await userEvent.click(within(thisMonthGrid).getByRole("button", { name: / 1 /u }));
		await userEvent.click(apply);
		const firstDay = startOfMonth(new Date(STORY_NOW));
		await expect(args.onPeriodChange).toHaveBeenCalledWith({
			kind: "custom",
			from: firstDay,
			to: firstDay,
		});
	},
};
