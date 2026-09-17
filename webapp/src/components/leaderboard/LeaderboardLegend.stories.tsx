import type { Meta, StoryObj } from "@storybook/react";

import { withProvider } from "@/stories/decorators";

import { LeaderboardLegend } from "./LeaderboardLegend";

/**
 * Legend component that explains the scoring system and activity icons used in the leaderboard.
 * Provides a quick reference for understanding pull request review activities and their impact.
 */
const meta = {
	component: LeaderboardLegend,
	args: { providerType: "GITHUB" },
	tags: ["autodocs"],
	parameters: {
		layout: "centered",
		backgrounds: {
			default: "light",
		},
	},
} satisfies Meta<typeof LeaderboardLegend>;

export default meta;
type Story = StoryObj<typeof meta>;

/**
 * Default view showing the legend card with activity icons and descriptions.
 * Includes access to detailed scoring explanation via modal.
 */
export const Default: Story = {};

// --- Alternate provider variant ---

/**
 * Alternate provider — shows "merge requests" terminology and provider-native icon.
 */
export const MergeRequestProvider: Story = {
	decorators: [withProvider("GITLAB")],
	args: {
		providerType: "GITLAB",
	},
};
