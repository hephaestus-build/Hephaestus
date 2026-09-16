import type { Meta, StoryObj } from "@storybook/react";
import { expect, userEvent } from "storybook/test";
import type { TrendSupport } from "@/api/types.gen";
import { settledPopup } from "@/stories/overlay";
import { PracticeTrendChip } from "./PracticeTrendChip";

const wellSupported: TrendSupport = {
	currentOpportunities: 4,
	previousOpportunities: 4,
	opportunitiesUntilComparable: 0,
	calendarSpanDays: 9,
	bundleSize: 4,
	ropeHalfWidth: 0.15,
	credibilityThreshold: 0.9,
};

const none: TrendSupport = {
	...wellSupported,
	currentOpportunities: 2,
	previousOpportunities: 0,
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
		await expect(tooltip).toHaveTextContent("Across 8 pieces of reviewed work in this group.");
		await expect(tooltip).not.toHaveTextContent("Compared");
	},
};
