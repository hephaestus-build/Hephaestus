import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";

import { getGroupVisual, groupMark } from "./group-visuals";
import { PracticeInGroup } from "./PracticeInGroup";

const PACKAGE = getGroupVisual("Package", "sky");

const meta = {
	component: PracticeInGroup,
	parameters: { layout: "centered" },
	tags: ["autodocs"],
	args: {
		practiceName: "Explain significant decisions",
		practiceGroup: groupMark(PACKAGE),
		groupName: "Packaging work for review",
		groupIcon: PACKAGE.Icon,
		groupPill: PACKAGE.pill,
	},
	argTypes: {
		practiceGroup: { control: false },
		groupIcon: { control: false },
	},
} satisfies Meta<typeof PracticeInGroup>;

export default meta;
type Story = StoryObj<typeof meta>;

/**
 * A practice beside its group, as a feedback card's head names it: the practice's words and the
 * group's are one size, so neither reads as the larger.
 */
export const Default: Story = {
	play: async ({ canvas }) => {
		const practice = canvas.getByText("Explain significant decisions");
		const group = canvas.getByText("Packaging work for review");
		await expect(getComputedStyle(practice).fontSize).toBe(getComputedStyle(group).fontSize);
	},
};
