import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, waitFor } from "storybook/test";

import { ARRIVAL_SHEEN_MS, ARRIVAL_STILL_MS, ArrivalHighlight } from "./ArrivalHighlight";

const meta = {
	component: ArrivalHighlight,
	parameters: { layout: "padded" },
	args: { onDone: fn() },
	render: (args) => (
		<div className="relative rounded-xl border p-6 text-sm">
			<ArrivalHighlight {...args} />
			The card a link arrived at.
		</div>
	),
} satisfies Meta<typeof ArrivalHighlight>;

export default meta;
type Story = StoryObj<typeof meta>;

/**
 * The sheen crosses once: a band of the mentor wash, skewed, sweeps from left to right, then the
 * highlight is gone and the caller hears it ended. No pulse, no repeat.
 */
export const SweepsOnce: Story = {
	play: async ({ args, canvasElement }) => {
		const highlight = () => canvasElement.querySelector("[data-arrival-highlight]");
		const reduced = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
		if (reduced) {
			// Nothing moves: a still wash, which goes after its time.
			await expect(highlight()).toHaveAttribute("data-arrival-highlight", "still");
			await expect(canvasElement.querySelector("[data-arrival-sheen]")).toBeNull();
		} else {
			await expect(highlight()).toHaveAttribute("data-arrival-highlight", "sheen");
			const band = canvasElement.querySelector<HTMLElement>("[data-arrival-sheen]");
			await expect(band?.className).toContain("via-mentor/15");
			await expect(band?.getAnimations()).toHaveLength(1);
			await expect(band?.getAnimations()[0]?.effect?.getComputedTiming().iterations).toBe(1);
		}
		await waitFor(
			async () => {
				await expect(highlight()).toBeNull();
			},
			{ timeout: Math.max(ARRIVAL_SHEEN_MS, ARRIVAL_STILL_MS) + 1500 },
		);
		await expect(args.onDone).toHaveBeenCalledOnce();
	},
};
