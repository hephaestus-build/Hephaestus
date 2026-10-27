import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, within } from "storybook/test";

import { GROUP_COLORS_NOTE } from "@/components/practice-vocabulary/group-visuals";
import { expectSettledVisible } from "@/stories/overlay";
import { expectNoPageOverflow } from "@/stories/reflow";
import { Stateful } from "@/stories/stateful";

import { PracticeProfileGuide } from "./PracticeProfileGuide";

/**
 * Its words are fixed, so it has no loading or error state of its own: it stands while the page
 * loads, as the title and the introduction do.
 */
const meta = {
	component: PracticeProfileGuide,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	args: { open: true, onOpenChange: fn() },
	render: (args) => (
		<Stateful initial={args.open}>
			{(open, setOpen) => (
				<PracticeProfileGuide
					{...args}
					open={open}
					onOpenChange={(next) => {
						args.onOpenChange(next);
						setOpen(next);
					}}
				/>
			)}
		</Stateful>
	),
} satisfies Meta<typeof PracticeProfileGuide>;

export default meta;
type Story = StoryObj<typeof meta>;

/** A first visit: open, and every label's sentence is on the page, not only behind a hover. */
export const Default: Story = {
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("button", { name: "How to read this page" })).toHaveAttribute(
			"aria-expanded",
			"true",
		);
		// The registry's sentences, worded for one practice.
		const standings = canvas.getByRole("group", { name: "Standings" });
		await expect(
			within(standings).getByText("No review has looked at this practice in your work yet."),
		).toBeVisible();
		await expect(within(standings).getAllByText("No standing yet")).toHaveLength(2);
		// Five standings, and both marks for a standing read from earlier reviews.
		await expect(within(standings).getAllByRole("term")).toHaveLength(7);
		await expect(
			within(standings).getAllByText("Based on earlier reviews, the criteria might have changed"),
		).toHaveLength(2);
		await expect(
			within(standings).getByText(/The original criterion version is unavailable\./u),
		).toBeVisible();
		const trends = canvas.getByRole("group", { name: "Trends" });
		await expect(within(trends).getAllByRole("term")).toHaveLength(4);
		await expect(
			within(trends).getByText(
				"Recent and earlier work were compared, and the difference is too small to call.",
			),
		).toBeVisible();
	},
};

/** Closed: only the button that brings it back is left, and it does. */
export const Closed: Story = {
	args: { open: false },
	play: async ({ args, canvas, canvasElement, userEvent }) => {
		const toggle = canvas.getByRole("button", { name: "How to read this page" });
		const point = canvas.getByText(GROUP_COLORS_NOTE);
		// The trigger names its panel only while it is open, so the primitive's own slot finds it.
		const panel = canvasElement.querySelector("[data-slot='collapsible-content']");
		await expect(toggle).toHaveAttribute("aria-expanded", "false");
		await expect(point).not.toBeVisible();
		// Closed takes no room: no empty frame is left under the button.
		await expect(panel?.getBoundingClientRect().height).toBe(0);

		await userEvent.click(toggle);
		await expect(args.onOpenChange).toHaveBeenLastCalledWith(true);
		await expect(toggle).toHaveAttribute("aria-expanded", "true");
		await expectSettledVisible(point);

		await userEvent.click(toggle);
		await expect(args.onOpenChange).toHaveBeenLastCalledWith(false);
		await expect(point).not.toBeVisible();
		await expect(panel?.getBoundingClientRect().height).toBe(0);
	},
};

/** At 320 px the label lists stack and the longest label wraps, without widening the page. */
export const MobileReflow: Story = {
	parameters: {
		viewport: { defaultViewport: "reflow" },
		chromatic: { viewports: [320] },
	},
	play: expectNoPageOverflow,
};
