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
		practice: "Scope the change to one concern",
		lead: "The invoice rename does not belong in a CSV export.",
		stance: "gap",
	},
} satisfies Meta<typeof LandingFeedbackCard>;

export default meta;
type Story = StoryObj<typeof meta>;

/**
 * A practice the work falls short of. The stance is the card's only non-decorative icon, so it
 * carries the accessible name the rest of the scene leaves to `aria-hidden`.
 */
export const Gap: Story = {
	play: async ({ canvas }) => {
		await expect(canvas.getByLabelText(OUTCOME_DEFS.NEGATIVE.label)).toBeVisible();
	},
};

/** The same card for a practice the work does well; only the stance changes. */
export const Strength: Story = {
	args: {
		group: { color: "teal", icon: "Eye" },
		practice: "Leave specific, actionable review comments",
		lead: "Names the doubt and what would settle it.",
		stance: "strength",
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByLabelText(OUTCOME_DEFS.POSITIVE.label)).toBeVisible();
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

export const DarkMode: Story = {
	globals: { theme: "dark" },
};
