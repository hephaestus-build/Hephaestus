import type { Meta, StoryObj } from "@storybook/react";

import type { LabelInfo, RepositoryInfo, TeamInfo } from "@/api/types.gen";

import { RepositoryCard } from "./RepositoryCard";

const meta = {
	component: RepositoryCard,
	parameters: { layout: "centered" },
	tags: ["autodocs"],
} satisfies Meta<typeof RepositoryCard>;

export default meta;
type Story = StoryObj<typeof meta>;

const repo: RepositoryInfo = {
	id: 100,
	name: "repo",
	nameWithOwner: "org/repo",
	htmlUrl: "https://github.com/org/repo",
	description: "A sample repository",
	hiddenFromContributions: false,
};

const team: TeamInfo = {
	id: 1,
	name: "Platform",
	hidden: false,
	membershipCount: 5,
	repoPermissionCount: 3,
	slug: "team-1",
	repositories: [repo],
	members: [],
	labels: [
		{
			id: 1,
			name: "bug",
			color: "d73a4a",
			repository: repo,
		},
	],
};

const catalog: LabelInfo[] = [
	{ id: 1, name: "bug", color: "d73a4a", repository: repo },
	{ id: 2, name: "feature", color: "a2eeef", repository: repo },
	{ id: 3, name: "docs", color: "0075ca", repository: repo },
];

export const Default: Story = {
	args: {
		repository: repo,
		team,
		catalogLabels: catalog,
	},
};
