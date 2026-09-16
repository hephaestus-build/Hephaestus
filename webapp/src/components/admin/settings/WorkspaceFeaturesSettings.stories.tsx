import type { Meta, StoryObj } from "@storybook/react";
import { fn } from "storybook/test";

import { WorkspaceFeaturesSettings, type FeatureValues } from "./WorkspaceFeaturesSettings";

const allOff: FeatureValues = {
	mentorEnabled: false,
	leaderboardEnabled: false,
	progressionEnabled: false,
	leaguesEnabled: false,
};

const meta = {
	component: WorkspaceFeaturesSettings,
	parameters: { layout: "centered" },
	tags: ["autodocs"],
	args: {
		values: allOff,
		isSaving: false,
		onToggle: fn(),
	},
} satisfies Meta<typeof WorkspaceFeaturesSettings>;

export default meta;
type Story = StoryObj<typeof meta>;

export const AllDisabled: Story = {};

export const AllEnabled: Story = {
	args: {
		values: {
			...allOff,
			mentorEnabled: true,
			leaderboardEnabled: true,
			progressionEnabled: true,
			leaguesEnabled: true,
		},
	},
};

export const Saving: Story = {
	args: {
		values: {
			...allOff,
			mentorEnabled: true,
		},
		isSaving: true,
	},
};
