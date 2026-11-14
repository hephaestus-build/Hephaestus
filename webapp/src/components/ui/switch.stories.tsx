import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, waitFor } from "storybook/test";

import { Switch } from "@/components/ui/switch";

/** The spinner is decoration, hidden from assistive technology, so no query by role finds it. */
const SPINNER = '[data-slot="spinner"]';

/**
 * `pending` marks a switch whose change is in flight: it turns read-only at once and keeps its
 * focus, and its spinner shows in the thumb only after a second.
 */
const meta = {
	component: Switch,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	args: { "aria-label": "Show contributors", onCheckedChange: fn() },
} satisfies Meta<typeof Switch>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {};

/** A change that has taken longer than a second: the spinner shows inside the switch, which stays put. */
export const Pending: Story = {
	args: { pending: true, defaultChecked: true },
	play: async ({ args, canvas, userEvent }) => {
		const control = canvas.getByRole("switch", { name: "Show contributors" });
		const before = control.getBoundingClientRect().left;
		await waitFor(async () => expect(control.querySelector(SPINNER)).toBeVisible(), {
			timeout: 3000,
		});
		await expect(control.getBoundingClientRect().left).toBe(before);
		await userEvent.click(control);
		await expect(args.onCheckedChange).not.toHaveBeenCalled();
		await expect(control).toBeChecked();
	},
};
