import type { Meta, StoryObj } from "@storybook/react";
import { fn } from "storybook/test";

import type { LabelInfo, RepositoryInfo, TeamInfo } from "@/api/types.gen";

import { TeamTree } from "./TeamTree";

const meta = {
	component: TeamTree,
	parameters: { layout: "centered" },
	tags: ["autodocs"],
} satisfies Meta<typeof TeamTree>;

export default meta;
type Story = StoryObj<typeof meta>;

const repoA: RepositoryInfo = {
	id: 100,
	name: "api",
	nameWithOwner: "org/api",
	htmlUrl: "https://github.com/org/api",
	hiddenFromContributions: false,
};
const repoB: RepositoryInfo = {
	id: 101,
	name: "web",
	nameWithOwner: "org/web",
	htmlUrl: "https://github.com/org/web",
	hiddenFromContributions: false,
};
const bug: LabelInfo = {
	id: 1,
	name: "bug",
	color: "d73a4a",
	repository: repoA,
};

const child: TeamInfo = {
	id: 2,
	name: "Frontend",
	hidden: false,
	membershipCount: 0,
	repoPermissionCount: 0,
	repositories: [repoB],
	labels: [],
	members: [],
};

const parent: TeamInfo = {
	id: 1,
	name: "Platform",
	hidden: false,
	membershipCount: 1,
	repoPermissionCount: 1,
	members: [
		{
			id: 10,
			login: "alice",
			name: "Alice",
			avatarUrl: "",
			htmlUrl: "https://github.com/alice",
		},
	],
	repositories: [repoA],
	labels: [bug],
};

const childrenMap = new Map<number, TeamInfo[]>([[1, [child]]]);
const displaySet = new Set<number>([1, 2]);

export const Default: Story = {
	args: {
		team: parent,
		childrenMap,
		displaySet,
		getCatalogLabels: (repoId: number) => (repoId === 100 ? [bug] : []),
		onToggleVisibility: fn(),
		onToggleRepositoryVisibility: fn(),
	},
};
