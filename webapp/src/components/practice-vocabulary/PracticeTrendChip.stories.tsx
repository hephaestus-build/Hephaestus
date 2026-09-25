import type { Meta, StoryObj } from "@storybook/react";
import { expect, userEvent } from "storybook/test";

import type { TrendSupport } from "@/api/types.gen";
import { settledPopup } from "@/stories/overlay";
import { wellSupported } from "@/stories/practice-profile-story-mock-data";

import { PracticeTrendChip } from "./PracticeTrendChip";

const none: TrendSupport = {
	...wellSupported,
	currentOpportunities: 2,
	previousOpportunities: 0,
	opportunities: 2,
	opportunitiesUntilComparable: 3,
};

const meta = {
	component: PracticeTrendChip,
	parameters: { layout: "centered" },
	tags: ["autodocs"],
	args: { scope: "practice" },
} satisfies Meta<typeof PracticeTrendChip>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Improving: Story = { args: { direction: "IMPROVING", support: wellSupported } };
export const Declining: Story = { args: { direction: "DECLINING", support: wellSupported } };
export const Uncertain: Story = { args: { direction: "UNCERTAIN", support: wellSupported } };
export const InsufficientEvidence: Story = {
	args: { direction: "INSUFFICIENT_EVIDENCE", support: none },
};
/** No evidence at all, so no provenance to give: the tooltip is the registry's sentence instead. */
export const WithoutSupport: Story = {
	args: { direction: "INSUFFICIENT_EVIDENCE", support: undefined },
	play: async ({ canvas }) => {
		await userEvent.hover(canvas.getByRole("button"));
		const tooltip = await settledPopup();
		await expect(tooltip).toHaveTextContent(
			"There is not yet enough reviewed work on both sides to compare.",
		);
		await expect(tooltip).not.toHaveTextContent("Based on");
	},
};
export const GroupScope: Story = {
	args: {
		direction: "IMPROVING",
		scope: "group",
		support: { ...wellSupported, comparablePractices: 3, eligiblePractices: 5 },
	},
	play: async ({ canvas }) => {
		// The tooltip is the only reason this chip is focusable, and it is the one place that says a
		// group trend pools its practices rather than comparing two stretches. It portals, so it is
		// read off the document and only once the popup has settled.
		await userEvent.hover(canvas.getByRole("button"));
		const tooltip = await settledPopup();
		await expect(tooltip).toHaveTextContent("Across eight pieces of reviewed work in this group.");
		await expect(tooltip).not.toHaveTextContent("Compared");
	},
};
