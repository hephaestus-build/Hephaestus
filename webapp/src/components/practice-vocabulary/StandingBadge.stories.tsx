import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, userEvent } from "storybook/test";

import { settledPopup } from "@/stories/overlay";

import { statusValues } from "@/components/common/status-def";
import { PRACTICE_GROUP_STANDING_DEFS } from "./practice-group-standing-defs";
import { StandingBadge } from "./StandingBadge";

/**
 * A standing is the registry's badge made focusable, so the sentence behind it is reachable by
 * keyboard as well as pointer. The badge itself is `StatusBadge`, as every enum's is.
 */
const meta = {
	component: StandingBadge,
	parameters: { layout: "centered" },
	tags: ["autodocs"],
	args: { standing: "STRENGTH", scope: "group" },
} satisfies Meta<typeof StandingBadge>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ canvas }) => {
		// The sentence is the reason the badge is a button; it portals, so it is read off the document.
		await userEvent.tab();
		await expect(canvas.getByRole("button", { name: "Going well" })).toHaveFocus();
		const tooltip = await settledPopup();
		await expect(tooltip).toHaveTextContent("Recent reviews here were almost entirely positive.");
	},
};

/**
 * The same standing on one practice: the sentence is about that practice, not about a group of
 * them.
 */
export const PracticeNotObserved: Story = {
	args: { standing: "NOT_OBSERVED", scope: "practice" },
	play: async ({ canvas }) => {
		await userEvent.hover(canvas.getByRole("button", { name: "Not observed yet" }));
		const tooltip = await settledPopup();
		await expect(tooltip).toHaveTextContent(
			"No review has observed this practice in your work yet.",
		);
	},
};

const fourPieces = {
	bundleSize: 4,
	credibilityThreshold: 0.9,
	currentOpportunities: 4,
	opportunities: 8,
	opportunitiesUntilComparable: 0,
	previousOpportunities: 4,
	ropeHalfWidth: 0.15,
};

/** A settled standing names how many pieces of work it is read from, beside the badge. */
export const WithItsWork: Story = {
	args: { support: fourPieces },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("from 4 pieces of work")).toBeVisible();
		await userEvent.hover(canvas.getByRole("button", { name: "Going well" }));
		const tooltip = await settledPopup();
		await expect(tooltip).toHaveTextContent(
			"Recent reviews here were almost entirely positive. Read from four pieces of work.",
		);
	},
};

/** Fewer than three pieces of work: the sentence behind the badge calls the standing an early read. */
export const EarlyRead: Story = {
	args: { support: { ...fourPieces, currentOpportunities: 1 } },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("from 1 piece of work")).toBeVisible();
		await userEvent.hover(canvas.getByRole("button", { name: "Going well" }));
		const tooltip = await settledPopup();
		await expect(tooltip).toHaveTextContent("An early read from one piece of work.");
		await expect(tooltip).not.toHaveTextContent("almost entirely positive");
	},
};

/** A standing no review has settled names no work, whatever support comes with it. */
export const NotSettled: Story = {
	args: { standing: "NOT_OBSERVED", support: fourPieces },
	play: async ({ canvas }) => {
		await expect(canvas.queryByText(/pieces? of work/u)).toBeNull();
	},
};

/** Every standing at once, which is where two entries sharing an icon would show. */
export const EveryStanding: Story = {
	render: (args) => (
		<div className="flex flex-wrap gap-2">
			{statusValues(PRACTICE_GROUP_STANDING_DEFS).map((standing) => (
				<StandingBadge key={standing} {...args} standing={standing} />
			))}
		</div>
	),
	play: async ({ canvas }) => {
		for (const standing of statusValues(PRACTICE_GROUP_STANDING_DEFS)) {
			canvas.getByRole("button", { name: PRACTICE_GROUP_STANDING_DEFS[standing].label });
		}
	},
};
