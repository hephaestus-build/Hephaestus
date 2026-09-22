import type { Meta, StoryObj } from "@storybook/react";
import { fn } from "storybook/test";

import type { TeamInfo } from "@/api/types.gen";

import { TeamCard } from "./TeamCard";

/**
 * TeamCard shows a team's basic info and a slot for repositories/children.
 * Use it to present a team header with visibility controls.
 */
const meta = {
	component: TeamCard,
	parameters: { layout: "centered" },
	tags: ["autodocs"],
	args: {
		team: {
			id: 1,
			name: "Platform Team",
			hidden: false,
			membershipCount: 1,
			repoPermissionCount: 0,
			members: [
				{
					id: 10,
					login: "alice",
					name: "Alice",
					avatarUrl: "",
					htmlUrl: "https://github.com/alice",
				},
			],
			repositories: [
				{
					id: 101,
					name: "repo",
					nameWithOwner: "org/repo",
					htmlUrl: "https://github.com/org/repo",
					hiddenFromContributions: false,
				},
			],
			labels: [],
		} satisfies TeamInfo,
		memberCount: 1,
	},
} satisfies Meta<typeof TeamCard>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	args: {
		onToggleVisibility: fn(),
		getCatalogLabels: () => [],
		children: <div className="text-sm text-muted-foreground">Repositories/children slot</div>,
	},
};

export const Hidden: Story = {
	args: {
		team: {
			id: 1,
			name: "Platform Team",
			hidden: true,
			membershipCount: 5,
			repoPermissionCount: 0,
			members: [],
			repositories: [],
			labels: [],
		} satisfies TeamInfo,
		memberCount: 5,
		onToggleVisibility: fn(),
		getCatalogLabels: () => [],
	},
};
