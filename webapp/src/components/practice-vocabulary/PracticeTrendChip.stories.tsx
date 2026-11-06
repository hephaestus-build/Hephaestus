import type { Meta, StoryObj } from "@storybook/react";
import { expect, userEvent } from "storybook/test";

import type { TrendSupport } from "@/api/types.gen";
import { settledPopup } from "@/stories/overlay";
import { wellSupported } from "@/stories/practice-profile-story-mock-data";

import type { TrendDirection } from "./practice-trend-defs";
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
/** No evidence at all: only the sentence on what a trend needs. */
export const WithoutSupport: Story = {
	args: { direction: "INSUFFICIENT_EVIDENCE", support: undefined },
	play: async ({ canvas }) => {
		await userEvent.hover(canvas.getByRole("button"));
		const tooltip = await settledPopup();
		await expect(tooltip).toHaveTextContent(
			"A trend needs eight reviewed pieces of work. So far there are fewer.",
		);
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
		// group trend goes practice by practice, without a bundle size. It portals, so it is read off
		// the document and only once the popup has settled.
		await userEvent.hover(canvas.getByRole("button"));
		const tooltip = await settledPopup();
		await expect(tooltip).toHaveTextContent(
			"Your recent work meets this group's practices more often than before. Compares, practice by practice,",
		);
		await expect(tooltip).not.toHaveTextContent(/four|eight/u);
	},
};

/** A newer server can send a direction outside this build's generated union. */
export const UnknownServerValue: Story = {
	args: {
		// oxlint-disable-next-line typescript/no-unsafe-type-assertion -- Simulate a newer server value outside this build's generated union.
		direction: "FUTURE_DIRECTION" as TrendDirection,
		scope: "group",
		support: wellSupported,
	},
	play: async ({ canvas }) => {
		const trigger = canvas.getByRole("button", { name: "Unknown" });
		await expect(trigger).toHaveAccessibleDescription(
			"This version of Hephaestus does not recognize this value.",
		);
		await userEvent.hover(trigger);
		const tooltip = await settledPopup();
		await expect(tooltip).toHaveTextContent(
			"This version of Hephaestus does not recognize this value.",
		);
		await expect(tooltip).not.toHaveTextContent(/Compares|meets this/u);
	},
};
