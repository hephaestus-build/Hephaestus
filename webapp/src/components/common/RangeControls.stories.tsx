import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn } from "storybook/test";

import { ACTIVITY_RANGE_OPTIONS } from "@/components/activity/activity-range";
import { withStandardPage } from "@/stories/decorators";

import { RangeControls } from "./RangeControls";

const meta = {
	component: RangeControls,
	decorators: [withStandardPage],
	tags: ["autodocs"],
	args: { options: ACTIVITY_RANGE_OPTIONS, range: "30d", onRangeChange: fn(), updating: false },
} satisfies Meta<typeof RangeControls>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ args, canvas, userEvent }) => {
		// The status region is there before anything is said in it, so what it says is announced.
		await expect(canvas.getByRole("status")).toBeEmptyDOMElement();
		await userEvent.click(canvas.getByRole("button", { name: "90 days" }));
		await expect(args.onRangeChange).toHaveBeenCalledWith("90d");
	},
};

/** Past the first second of a load, the words appear where the reader just pressed. */
export const Updating: Story = {
	args: { updating: true },
	play: async ({ canvas }) => {
		await expect(await canvas.findByText("Updating…", undefined, { timeout: 3000 })).toBeVisible();
		await expect(canvas.getByRole("status")).toHaveTextContent("Updating…");
	},
};
