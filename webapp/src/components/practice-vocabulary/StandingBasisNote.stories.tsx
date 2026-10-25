import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, userEvent } from "storybook/test";

import { StandingBasisNote } from "./StandingBasisNote";

/**
 * Beside a standing on the Practice profile and on Practices across the workspace: whether it includes
 * earlier assessments. Nothing shows for a standing from current reviews.
 */
const meta = {
	component: StandingBasisNote,
	tags: ["autodocs"],
	parameters: { layout: "padded" },
	args: { basis: "EARLIER_REVIEWS" },
} satisfies Meta<typeof StandingBasisNote>;

export default meta;
type Story = StoryObj<typeof meta>;

/** The words are the whole note: nothing to hover, tap or focus, and no tooltip opens. */
export const EarlierReviews: Story = {
	play: async ({ canvas }) => {
		const mark = canvas.getByText("Based on earlier reviews, the criteria might have changed");
		await expect(mark).toBeVisible();
		await expect(canvas.queryByRole("button")).toBeNull();
		await userEvent.hover(mark);
		await expect(canvas.queryByRole("tooltip")).toBeNull();
	},
};

/** The reviews recorded no practice version: the same words, as the criteria may differ either way. */
export const CriteriaUnknown: Story = {
	args: { basis: "EARLIER_REVIEWS_CRITERIA_UNKNOWN" },
	play: async ({ canvas }) => {
		await expect(
			canvas.getByText("Based on earlier reviews, the criteria might have changed"),
		).toBeVisible();
		await expect(canvas.queryByRole("button")).toBeNull();
	},
};

/** A standing from current reviews needs no words. */
export const CurrentReviews: Story = {
	args: { basis: "CURRENT_REVIEWS" },
	play: async ({ canvas }) => {
		await expect(canvas.queryByText(/^Based on earlier reviews/u)).toBeNull();
	},
};

/** No verdict, no basis. */
export const NoVerdict: Story = {
	args: { basis: null },
	play: async ({ canvas }) => {
		await expect(canvas.queryByText(/^Based on earlier reviews/u)).toBeNull();
	},
};

export const Dark: Story = {
	args: { basis: "EARLIER_REVIEWS_CRITERIA_UNKNOWN" },
	globals: { theme: "dark" },
};
