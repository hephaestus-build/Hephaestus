import type { Meta, StoryObj } from "@storybook/react";
import { expect } from "storybook/test";
import { OUTCOME_DEFS } from "@/components/practice-vocabulary/outcome-defs";
import { LandingFeedbackCard, LandingStatePill } from "./LandingVisuals";

const meta = {
	component: LandingFeedbackCard,
	parameters: { layout: "centered" },
	tags: ["autodocs"],
	args: {
		group: { color: "sky", icon: "Package" },
		practice: "Keep the change reviewable",
		lead: "The invoice rename does not belong in a CSV export.",
	},
} satisfies Meta<typeof LandingFeedbackCard>;

export default meta;
type Story = StoryObj<typeof meta>;

/**
 * A practice the work falls short of. The outcome is the card's only non-decorative icon, so it
 * carries the accessible name the rest of the scene leaves to `aria-hidden`.
 */
export const Gap: Story = {
	play: async ({ canvas }) => {
		await expect(canvas.getByLabelText(OUTCOME_DEFS.NOT_MET.label)).toBeVisible();
	},
};

/**
 * An open issue and a pull request ready for review both read "Open", the way GitHub labels them —
 * the icon is what tells them apart, so the two are not interchangeable in the scene.
 */
export const WorkStates: Story = {
	render: () => (
		<div className="flex gap-2">
			<LandingStatePill state="open" />
			<LandingStatePill state="ready" />
			<LandingStatePill state="merged" />
		</div>
	),
	play: async ({ canvas }) => {
		await expect(canvas.getAllByText("Open")).toHaveLength(2);
		await expect(canvas.getByText("Merged")).toBeVisible();
	},
};

/** The provider's purple on its own dark tint misses 4.5:1 at this size, so the dark theme uses plain text. */
export const WorkStatesInDarkMode: Story = {
	...WorkStates,
	globals: { theme: "dark" },
};

export const DarkMode: Story = {
	globals: { theme: "dark" },
};
