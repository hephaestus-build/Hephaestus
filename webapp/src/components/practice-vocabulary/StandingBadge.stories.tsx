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
