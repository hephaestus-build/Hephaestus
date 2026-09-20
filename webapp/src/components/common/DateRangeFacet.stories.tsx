import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, userEvent } from "storybook/test";

import { DateRangeFacet } from "./DateRangeFacet";

const meta = {
	component: DateRangeFacet,
	parameters: { layout: "centered" },
	tags: ["autodocs"],
	args: { title: "Observed", value: undefined, onChange: fn() },
} satisfies Meta<typeof DateRangeFacet>;

export default meta;
type Story = StoryObj<typeof meta>;

/**
 * The trigger is named after the date it filters, not after dates in general: several screens send
 * different timestamps through this control, and "Date" on all of them tells an operator nothing.
 */
export const Empty: Story = {
	play: async ({ canvas }) => {
		await expect(await canvas.findByRole("button", { name: "Observed" })).toBeVisible();
	},
};

export const StartOnly: Story = {
	args: { value: { from: new Date(2026, 6, 1) } },
	play: async ({ canvas }) => {
		await expect(await canvas.findByText("From Jul 1, 2026")).toBeVisible();
		// The applied range is in the accessible name too, so the control reads the same to a screen
		// reader as it looks: "Observed: From Jul 1, 2026".
		canvas.getByRole("button", { name: "Observed: From Jul 1, 2026" });
	},
};

export const FullRange: Story = {
	args: { title: "Composed", value: { from: new Date(2026, 6, 1), to: new Date(2026, 6, 24) } },
	play: async ({ canvas }) => {
		await expect(await canvas.findByText("Jul 1 – Jul 24, 2026")).toBeVisible();
		canvas.getByRole("button", { name: "Composed: Jul 1 – Jul 24, 2026" });
	},
};

export const OpenedCalendar: Story = {
	play: async ({ canvas }) => {
		const trigger = await canvas.findByRole("button", { name: "Observed" });

		await expect(trigger).toHaveAttribute("aria-expanded", "false");
		await userEvent.click(trigger);
		await expect(trigger).toHaveAttribute("aria-expanded", "true");
	},
};

/**
 * Without "Clear selection" a range can be replaced by picking another but never removed, leaving an
 * operator who narrowed to one week no way back short of the toolbar's blanket Reset.
 */
export const ClearAPickedRange: Story = {
	args: { value: { from: new Date(2026, 6, 1), to: new Date(2026, 6, 24) } },
	play: async ({ args, canvas }) => {
		await userEvent.click(await canvas.findByRole("button", { name: /^Observed:/u }));
		await userEvent.click(await screen.findByRole("button", { name: "Clear selection" }));
		await expect(args.onChange).toHaveBeenCalledWith(undefined);
	},
};

export const NothingToClear: Story = {
	parameters: { chromatic: { disableSnapshot: true } },
	play: async ({ canvas }) => {
		await userEvent.click(await canvas.findByRole("button", { name: "Observed" }));
		await screen.findByRole("grid");
		await expect(screen.queryByRole("button", { name: "Clear selection" })).not.toBeInTheDocument();
	},
};
