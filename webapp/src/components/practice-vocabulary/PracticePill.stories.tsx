import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, userEvent } from "storybook/test";

import { expectTouchTarget } from "@/test/controls";

import { PracticePill } from "./PracticePill";

const meta = {
	component: PracticePill,
	parameters: { layout: "centered" },
	tags: ["autodocs"],
	args: {
		name: "Explain significant decisions",
		onOpen: fn(),
	},
} satisfies Meta<typeof PracticePill>;

export default meta;
type Story = StoryObj<typeof meta>;

/** Somewhere to open the practice: the name is the control, and a keyboard reaches it. */
export const Default: Story = {
	play: async ({ args, canvas }) => {
		const pill = canvas.getByRole("button", { name: "Explain significant decisions" });
		await expectTouchTarget(pill);
		await userEvent.click(pill);
		await expect(args.onOpen).toHaveBeenCalledOnce();
	},
};

/** Nowhere to open it: the pill is a word, so there is no control for a keyboard to land on. */
export const Plain: Story = {
	args: { onOpen: undefined },
	play: async ({ canvas }) => {
		await expect(canvas.queryByRole("button")).toBeNull();
		await expect(canvas.getByText("Explain significant decisions")).toBeVisible();
	},
};

/**
 * A practice named at the length the catalogue allows: the pill keeps to its column and the name
 * truncates rather than pushing the row it sits in wider.
 */
export const LongestName: Story = {
	args: {
		name: "Explain significant decisions where the reasoning cannot be recovered from the diff alone",
	},
	decorators: [
		(Story) => (
			<div className="w-60 border p-2">
				<Story />
			</div>
		),
	],
	play: async ({ canvas }) => {
		const name = canvas.getByText(/Explain significant decisions where/u);
		await expect(name.scrollWidth).toBeGreaterThan(name.clientWidth);
	},
};
