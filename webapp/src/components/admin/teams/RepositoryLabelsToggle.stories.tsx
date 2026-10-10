import type { Meta, StoryObj } from "@storybook/react";

import type { LabelInfo, RepositoryInfo, TeamInfo } from "@/api/types.gen";

import { RepositoryLabelsToggle } from "./RepositoryLabelsToggle";

const meta = {
	component: RepositoryLabelsToggle,
	parameters: { layout: "centered" },
	tags: ["autodocs"],
} satisfies Meta<typeof RepositoryLabelsToggle>;

export default meta;
type Story = StoryObj<typeof meta>;

const mockRepo: RepositoryInfo = {
	id: 100,
	name: "repo",
	nameWithOwner: "org/repo",
	htmlUrl: "https://github.com/org/repo",
	hiddenFromContributions: false,
};

const mockTeam: TeamInfo = {
	id: 1,
	name: "Team Alpha",
	hidden: false,
	membershipCount: 0,
	repoPermissionCount: 0,
	slug: "team-1",
	repositories: [mockRepo],
	members: [],
	labels: [
		{
			id: 1,
			name: "bug",
			color: "d73a4a",
			repository: mockRepo,
		},
	],
};

const catalog: LabelInfo[] = [
	{
		id: 1,
		name: "bug",
		color: "d73a4a",
		repository: mockRepo,
	},
	{
		id: 2,
		name: "feature",
		color: "a2eeef",
		repository: mockRepo,
	},
	{
		id: 3,
		name: "docs",
		color: "0075ca",
		repository: mockRepo,
	},
];

export const Default: Story = {
	args: {
		team: mockTeam,
		repository: mockRepo,
		catalogLabels: catalog,
	},
};

export const WithFilter: Story = {
	args: {
		team: mockTeam,
		repository: mockRepo,
		catalogLabels: catalog,
	},
};
