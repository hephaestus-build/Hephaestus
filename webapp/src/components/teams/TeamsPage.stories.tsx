import type { Meta, StoryObj } from "@storybook/react";

import type { TeamInfo } from "@/api/types.gen";
import { withStandardPage } from "@/stories/decorators";
import { expectNoPageOverflow } from "@/stories/reflow";

import { TeamsPage } from "./TeamsPage";

const mockTeams: TeamInfo[] = [
	{
		id: 1,
		name: "Frontend",
		description: "",
		htmlUrl: "https://github.com/orgs/example/teams/frontend",
		organization: "example",
		privacy: "SECRET",
		membershipCount: 2,
		repoPermissionCount: 0,
		parentId: undefined,
		repositories: [],
		labels: [],
		members: [
			{
				id: 1,
				login: "johndoe",
				name: "John Doe",
				avatarUrl: "https://avatars.githubusercontent.com/u/1?v=4",
				htmlUrl: "https://github.com/johndoe",
			},
			{
				id: 2,
				login: "janedoe",
				name: "Jane Doe",
				avatarUrl: "https://avatars.githubusercontent.com/u/2?v=4",
				htmlUrl: "https://github.com/janedoe",
			},
		],
		hidden: false,
	},
	{
		id: 2,
		name: "Backend",
		description: "",
		htmlUrl: "https://github.com/orgs/example/teams/backend",
		organization: "example",
		privacy: "SECRET",
		membershipCount: 1,
		repoPermissionCount: 0,
		parentId: undefined,
		repositories: [],
		labels: [],
		members: [
			{
				id: 3,
				login: "bobsmith",
				name: "Bob Smith",
				avatarUrl: "https://avatars.githubusercontent.com/u/3?v=4",
				htmlUrl: "https://github.com/bobsmith",
			},
		],
		hidden: false,
	},
	{
		id: 3,
		name: "DevOps",
		description: "",
		htmlUrl: "https://github.com/orgs/example/teams/devops",
		organization: "example",
		privacy: "SECRET",
		membershipCount: 0,
		repoPermissionCount: 0,
		parentId: undefined,
		repositories: [],
		labels: [],
		members: [],
		hidden: false,
	},
	{
		id: 4,
		name: "Hidden Team",
		description: "",
		htmlUrl: "https://github.com/orgs/example/teams/hidden",
		organization: "example",
		privacy: "SECRET",
		membershipCount: 1,
		repoPermissionCount: 0,
		parentId: 2,
		repositories: [],
		labels: [],
		members: [
			{
				id: 4,
				login: "hiddenuser",
				name: "Hidden User",
				avatarUrl: "https://avatars.githubusercontent.com/u/4?v=4",
				htmlUrl: "https://github.com/hiddenuser",
			},
		],
		hidden: true,
	},
	{
		id: 5,
		name: "Platform",
		description: "Shared platform engineering",
		htmlUrl: "https://github.com/orgs/example/teams/platform",
		organization: "example",
		privacy: "SECRET",
		membershipCount: 1,
		repoPermissionCount: 0,
		parentId: 3,
		repositories: [],
		labels: [],
		members: [
			{
				id: 5,
				login: "plat-dev",
				name: "Platform Dev",
				avatarUrl: "https://avatars.githubusercontent.com/u/5?v=4",
				htmlUrl: "https://github.com/plat-dev",
			},
		],
		hidden: false,
	},
];

const meta = {
	component: TeamsPage,
	tags: ["autodocs"],
	parameters: {
		layout: "fullscreen",
	},
	decorators: [withStandardPage],
} satisfies Meta<typeof TeamsPage>;

export default meta;
type Story = StoryObj<typeof meta>;

export const WithTeams: Story = {
	args: {
		teams: mockTeams,
		isLoading: false,
	},
};

export const Loading: Story = {
	args: {
		teams: [],
		isLoading: true,
	},
};

export const Empty: Story = {
	args: {
		teams: [],
		isLoading: false,
	},
};

export const MobileHierarchy: Story = {
	args: {
		teams: mockTeams.map((team) =>
			team.id === 5
				? {
						...team,
						name: "Developer experience and platform enablement",
					}
				: team,
		),
		isLoading: false,
	},
	parameters: {
		viewport: { defaultViewport: "reflow" },
		chromatic: { viewports: [320] },
	},
	play: expectNoPageOverflow,
};
