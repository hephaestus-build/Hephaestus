import type { Meta, StoryObj } from "@storybook/react";
import { HttpResponse, http } from "msw";
import { expect, fn } from "storybook/test";

import { withStandardPage } from "@/stories/decorators";

import type { FeatureValues } from "./AdminFeaturesSettings";
import { AdminSettingsPage } from "./AdminSettingsPage";

const allOff: FeatureValues = {
	mentorEnabled: false,
	leaderboardEnabled: false,
	progressionEnabled: false,
	leaguesEnabled: false,
};

const membershipRead = [
	http.get("*/workspaces/:workspaceSlug/members/me", () =>
		HttpResponse.json({ role: "OWNER", userLogin: "ada" }),
	),
];

const meta = {
	component: AdminSettingsPage,
	parameters: {
		// One MSW worker answers a whole Docs page, so each story gets its own frame until MSW goes.
		docs: { story: { inline: false, height: "600px" } },
		layout: "fullscreen",
		msw: { handlers: membershipRead },
		chromatic: { viewports: [320, 1440] },
	},
	decorators: [withStandardPage],
	tags: ["autodocs"],
	args: {
		isResettingLeagues: false,
		onResetLeagues: fn(),
		features: allOff,
		isSavingFeatures: false,
		onToggleFeature: fn(),
		workspaceSlug: "ase",
	},
} satisfies Meta<typeof AdminSettingsPage>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ canvas }) => {
		await expect(canvas.queryByText(/reset and recalculate leagues/i)).not.toBeInTheDocument();
		await canvas.findByRole("button", { name: /^delete workspace$/i });
	},
};

export const ResettingLeagues: Story = {
	args: { isResettingLeagues: true, features: { ...allOff, leaguesEnabled: true } },
};

export const LeaguesEnabled: Story = {
	args: { features: { ...allOff, leaguesEnabled: true } },
};

export const WorkspaceUnresolved: Story = {
	args: { workspaceSlug: undefined },
};
