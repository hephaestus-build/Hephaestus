import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";

import { SAMPLE_PATTERN } from "@/stories/practice-profile-samples";
import { PracticePatternLine } from "./PracticePatternLine";

/**
 * The pattern of a practice, read from its open card, in the card's
 * own words: the work it rests on and the clean work since, drawn as the card's meter. A plain label
 * beside the practice's name, never a control.
 */
const meta = {
	component: PracticePatternLine,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	args: { pattern: SAMPLE_PATTERN },
} satisfies Meta<typeof PracticePatternLine>;

export default meta;
type Story = StoryObj<typeof meta>;

/** The label says the practice has open feedback, and reads its clean count as words. */
export const Default: Story = {
	play: async ({ canvas, canvasElement }) => {
		const label = canvas.getByText("Has open feedback");
		await expect(label).toBeVisible();
		await expect(canvasElement).toHaveTextContent("1 clean in a row · 2 more resolve it");
		// Nothing to press or tab to: the row's own link opens the practice.
		await expect(canvas.queryByRole("button")).toBeNull();
		await expect(canvas.queryByRole("link")).toBeNull();
		await expect(label.closest("[tabindex]")).toBeNull();
	},
};

export const Dark: Story = { globals: { theme: "dark" } };

export const Phone: Story = { globals: { viewport: { value: "mobile" } } };
