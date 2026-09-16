import type { Meta, StoryObj } from "@storybook/react";
import { fn } from "storybook/test";

import type { TeamInfo } from "@/api/types.gen";
import type { ExtendedUserTeams } from "@/components/admin/user-teams";
import { withStandardPage } from "@/stories/decorators";
import { expectNoPageOverflow, expectTablesScrollInPlace } from "@/stories/reflow";

import { WorkspaceMembersPage } from "./WorkspaceMembersPage";

const meta = {
	component: WorkspaceMembersPage,
	parameters: {
		layout: "fullscreen",
	},
	decorators: [withStandardPage],
	tags: ["autodocs"],
	args: {
		view: { q: "", team: "all", sort: "name", desc: false, page: 0, size: 10 },
		onViewChange: fn(),
		renderPageLink: (page, props) => <a {...props} href={`?page=${page}`} />,
	},
} satisfies Meta<typeof WorkspaceMembersPage>;

export default meta;
type Story = StoryObj<typeof meta>;

const frontendTeam: TeamInfo = {
	id: 1,
	name: "Frontend",
	membershipCount: 0,
	repoPermissionCount: 0,
	repositories: [],
	labels: [],
	members: [],
	hidden: false,
};

const backendTeam: TeamInfo = {
	id: 2,
	name: "Backend",
	membershipCount: 0,
	repoPermissionCount: 0,
	repositories: [],
	labels: [],
	members: [],
	hidden: false,
};

const devopsTeam: TeamInfo = {
	id: 3,
	name: "DevOps",
	membershipCount: 0,
	repoPermissionCount: 0,
	repositories: [],
	labels: [],
	members: [],
	hidden: false,
};

const mockTeams: TeamInfo[] = [frontendTeam, backendTeam, devopsTeam];

const mockUsers: ExtendedUserTeams[] = [
	{
		id: 1,
		login: "alice.smith",
		name: "Alice Smith",
		hidden: false,
		url: "https://github.com/alice.smith",
		teams: [frontendTeam],
		user: {
			id: 1,
			name: "Alice Smith",
			login: "alice.smith",
			email: "alice.smith@example.com",
			role: "user",
		},
	},
	{
		id: 2,
		login: "bob.johnson",
		name: "Bob Johnson",
		hidden: false,
		url: "https://github.com/bob.johnson",
		teams: [backendTeam, devopsTeam],
		user: {
			id: 2,
			name: "Bob Johnson",
			login: "bob.johnson",
			email: "bob.johnson@example.com",
			role: "admin",
		},
	},
	{
		id: 3,
		login: "carol.williams",
		name: "Carol Williams",
		hidden: false,
		url: "https://github.com/carol.williams",
		teams: [frontendTeam, backendTeam],
		user: {
			id: 3,
			name: "Carol Williams",
			login: "carol.williams",
			email: "carol.williams@example.com",
			role: "user",
		},
	},
	{
		id: 4,
		login: "david.brown",
		name: "David Brown",
		hidden: false,
		url: "https://github.com/david.brown",
		teams: [],
		user: {
			id: 4,
			name: "David Brown",
			login: "david.brown",
			email: "david.brown@example.com",
			role: "user",
		},
	},
];

export const Default: Story = {
	args: {
		users: mockUsers,
		teams: mockTeams,
		isLoading: false,
	},
};

export const Loading: Story = {
	args: {
		users: [],
		teams: [],
		isLoading: true,
	},
};

export const EmptyUsers: Story = {
	args: {
		users: [],
		teams: mockTeams,
		isLoading: false,
	},
};

export const NoTeams: Story = {
	args: {
		users: mockUsers.map((user) => ({
			...user,
			teams: [],
		})),
		teams: [],
		isLoading: false,
	},
};

export const MobileReflow: Story = {
	args: {
		users: mockUsers,
		teams: mockTeams,
		isLoading: false,
	},
	parameters: {
		viewport: { defaultViewport: "reflow" },
		chromatic: { viewports: [320] },
	},
	play: async () => {
		await expectNoPageOverflow();
		await expectTablesScrollInPlace();
	},
};
