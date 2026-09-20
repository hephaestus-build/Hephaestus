import type { Meta, StoryObj } from "@storybook/react";

import { LeaderboardFilter } from "./LeaderboardFilter";

const meta = {
	component: LeaderboardFilter,
	tags: ["autodocs"],
	args: { selectedMode: "INDIVIDUAL" },
} satisfies Meta<typeof LeaderboardFilter>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	args: {
		teamOptions: [
			{ value: "Frontend", label: "Frontend" },
			{ value: "Backend", label: "Backend" },
			{ value: "DevOps", label: "DevOps" },
			{ value: "QA", label: "QA" },
			{ value: "Design", label: "Design" },
		],
		selectedTeam: "all",
		selectedSort: "SCORE",
	},
};

export const WithSelectedFilters: Story = {
	args: {
		teamOptions: [
			{ value: "Frontend", label: "Frontend" },
			{ value: "Backend", label: "Backend" },
			{ value: "DevOps", label: "DevOps" },
			{ value: "QA", label: "QA" },
			{ value: "Design", label: "Design" },
		],
		selectedTeam: "Frontend",
		selectedSort: "LEAGUE_POINTS",
	},
};
