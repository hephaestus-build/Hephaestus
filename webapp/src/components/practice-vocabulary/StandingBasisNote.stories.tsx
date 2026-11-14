import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, within } from "storybook/test";

import { settledPopup } from "@/stories/overlay";

import { StandingBasisNote } from "./StandingBasisNote";

/**
 * Beside a standing on the Practice profile and on Practices across the workspace: whether it includes
 * earlier observations. Nothing shows for a standing from current reviews.
 */
const meta = {
	component: StandingBasisNote,
	tags: ["autodocs"],
	parameters: { layout: "padded" },
	args: { basis: "EARLIER_REVIEWS" },
} satisfies Meta<typeof StandingBasisNote>;

export default meta;
type Story = StoryObj<typeof meta>;

/**
 * The words say the point and wrap on a narrow screen. The (i) after them holds the rest of the
 * registry's sentence: what such a standing leaves out.
 */
export const EarlierReviews: Story = {
	play: async ({ canvas, userEvent }) => {
		await expect(
			canvas.getByText("Based on earlier reviews, the criteria might have changed"),
		).toBeVisible();
		await userEvent.click(canvas.getByRole("button", { name: "About this standing" }));
		await expect(
			within(await settledPopup()).getByText(/No trend or feedback quotes are shown/u),
		).toBeVisible();
	},
};

/** The reviews recorded no practice version: the same words, and the (i) says the version is gone. */
export const CriteriaUnknown: Story = {
	args: { basis: "EARLIER_REVIEWS_CRITERIA_UNKNOWN" },
	play: async ({ canvas, userEvent }) => {
		await userEvent.click(canvas.getByRole("button", { name: "About this standing" }));
		await expect(
			within(await settledPopup()).getByText(/The original criterion version is unavailable/u),
		).toBeVisible();
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
