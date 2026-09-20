import type { Meta, StoryObj } from "@storybook/react";
import { fn } from "storybook/test";

import type { LabelInfo, RepositoryInfo, TeamInfo } from "@/api/types.gen";
import { withStandardPage } from "@/stories/decorators";
import { expectNoPageOverflow } from "@/stories/reflow";

import { WorkspaceTeamsTable } from "./WorkspaceTeamsTable";

const hephaestusRepo: RepositoryInfo = {
	id: 1,
	name: "hephaestus",
	nameWithOwner: "org/hephaestus",
	htmlUrl: "https://github.com/org/hephaestus",
	hiddenFromContributions: false,
};

const webAppRepo: RepositoryInfo = {
	id: 2,
	name: "web-app",
	nameWithOwner: "org/web-app",
	htmlUrl: "https://github.com/org/web-app",
	hiddenFromContributions: false,
};

const serverRepo: RepositoryInfo = {
	id: 3,
	name: "server",
	nameWithOwner: "org/server",
	htmlUrl: "https://github.com/org/server",
	hiddenFromContributions: false,
};

const bugLabel: LabelInfo = { id: 101, name: "bug", color: "d73a4a", repository: hephaestusRepo };
const helpWantedLabel: LabelInfo = {
	id: 103,
	name: "help wanted",
	color: "008672",
	repository: hephaestusRepo,
};
const goodFirstIssueLabel: LabelInfo = {
	id: 104,
	name: "good first issue",
	color: "7057ff",
	repository: serverRepo,
};

const teams: TeamInfo[] = [
	{
		id: 1,
		name: "Frontend",
		description: "",
		htmlUrl: "https://github.com/orgs/org/teams/frontend",
		organization: "org",
		privacy: "SECRET",
		membershipCount: 2,
		repoPermissionCount: 2,
		hidden: false,
		repositories: [hephaestusRepo, webAppRepo],
		labels: [bugLabel, helpWantedLabel],
		members: [
			{
				id: 1,
				login: "sarah",
				name: "Sarah",
				avatarUrl: "",
				htmlUrl: "https://github.com/sarah",
			},
			{
				id: 2,
				login: "alex",
				name: "Alex",
				avatarUrl: "",
				htmlUrl: "https://github.com/alex",
			},
		],
	},
	{
		id: 2,
		name: "Backend",
		description: "",
		htmlUrl: "https://github.com/orgs/org/teams/backend",
		organization: "org",
		privacy: "SECRET",
		membershipCount: 1,
		repoPermissionCount: 2,
		hidden: false,
		parentId: 1,
		repositories: [serverRepo],
		labels: [goodFirstIssueLabel],
		members: [
			{
				id: 3,
				login: "jamie",
				name: "Jamie",
				avatarUrl: "",
				htmlUrl: "https://github.com/jamie",
			},
		],
	},
	{
		id: 3,
		name: "QA",
		description: "",
		htmlUrl: "https://github.com/orgs/org/teams/qa",
		organization: "org",
		privacy: "SECRET",
		membershipCount: 0,
		repoPermissionCount: 1,
		hidden: true,
		parentId: 1,
		repositories: [webAppRepo],
		labels: [],
		members: [],
	},
];

const meta = {
	component: WorkspaceTeamsTable,
	parameters: { layout: "fullscreen" },
	decorators: [withStandardPage],
	tags: ["autodocs"],
	argTypes: {
		teams: { control: false },
	},
	args: {
		teams,
		onHideTeam: fn(),
		onToggleRepositoryVisibility: fn(),
		onAddLabelToTeam: fn(),
		onRemoveLabelFromTeam: fn(),
		search: "",
		onSearchChange: fn(),
	},
} satisfies Meta<typeof WorkspaceTeamsTable>;

export default meta;
export type Story = StoryObj<typeof meta>;

export const Default: Story = {};
export const Loading: Story = { args: { isLoading: true, teams: [] } };

export const MobileReflow: Story = {
	parameters: {
		viewport: { defaultViewport: "reflow" },
		chromatic: { viewports: [320] },
	},
	play: expectNoPageOverflow,
};
