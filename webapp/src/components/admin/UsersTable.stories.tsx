import type { Meta, StoryObj } from "@storybook/react";
import { expect, fn, userEvent } from "storybook/test";

import type { TeamInfo } from "@/api/types.gen";

import type { ExtendedUserTeams } from "./user-teams";
import { UsersTable } from "./UsersTable";

const frontendTeam: TeamInfo = {
	id: 1,
	name: "Frontend Team",
	hidden: false,
	membershipCount: 0,
	repoPermissionCount: 0,
	repositories: [],
	labels: [],
	members: [],
};

const backendTeam: TeamInfo = {
	id: 2,
	name: "Backend Team",
	hidden: false,
	membershipCount: 0,
	repoPermissionCount: 0,
	repositories: [],
	labels: [],
	members: [],
};

const devopsTeam: TeamInfo = {
	id: 3,
	name: "DevOps Team",
	hidden: false,
	membershipCount: 0,
	repoPermissionCount: 0,
	repositories: [],
	labels: [],
	members: [],
};

const qaTeam: TeamInfo = {
	id: 4,
	name: "QA Team",
	hidden: false,
	membershipCount: 0,
	repoPermissionCount: 0,
	repositories: [],
	labels: [],
	members: [],
};

const mockTeams: TeamInfo[] = [frontendTeam, backendTeam, devopsTeam, qaTeam];

const aliceOnTwoTeams: ExtendedUserTeams = {
	id: 1,
	login: "alice",
	name: "Alice Johnson",
	hidden: false,
	url: "https://github.com/alice",
	teams: [frontendTeam, devopsTeam],
	user: {
		id: "user-1",
		name: "Alice Johnson",
		login: "alice",
		email: "alice@example.com",
	},
};

const mockUsers: ExtendedUserTeams[] = [
	aliceOnTwoTeams,
	{
		id: 2,
		login: "bob",
		name: "Bob Smith",
		hidden: false,
		url: "https://github.com/bob",
		teams: [backendTeam],
		user: {
			id: "user-2",
			name: "Bob Smith",
			login: "bob",
			email: "bob@example.com",
		},
	},
	{
		id: 3,
		login: "charlie",
		name: "Charlie Brown",
		hidden: false,
		url: "https://github.com/charlie",
		teams: [frontendTeam, backendTeam, qaTeam],
		user: {
			id: "user-3",
			name: "Charlie Brown",
			login: "charlie",
			email: "charlie@example.com",
		},
	},
	{
		id: 4,
		login: "diana",
		name: "Diana Prince",
		hidden: false,
		url: "https://github.com/diana",
		teams: [],
		user: {
			id: "user-4",
			name: "Diana Prince",
			login: "diana",
			email: "diana@example.com",
		},
	},
	{
		id: 5,
		login: "ethan",
		name: "Ethan Hunt",
		hidden: false,
		url: "https://github.com/ethan",
		teams: [devopsTeam, qaTeam],
		user: {
			id: "user-5",
			name: "Ethan Hunt",
			login: "ethan",
			email: "ethan@example.com",
		},
	},
];

const meta: Meta<typeof UsersTable> = {
	component: UsersTable,
	parameters: {
		layout: "fullscreen",
	},
	tags: ["autodocs"],
	args: {
		users: mockUsers,
		teams: mockTeams,
		view: { q: "", team: "all", sort: "name", desc: false, page: 0, size: 10 },
		onViewChange: fn(),
	},
};

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ args, canvas }) => {
		// The sort lives in the URL, so a column is always sorted — and which one has to be legible
		// to a screen reader, not only to whoever can see the arrow.
		await expect(canvas.getByRole("columnheader", { name: "Name" })).toHaveAttribute(
			"aria-sort",
			"ascending",
		);
		await expect(canvas.getByRole("columnheader", { name: "Username" })).toHaveAttribute(
			"aria-sort",
			"none",
		);

		await userEvent.click(canvas.getByRole("button", { name: "Username" }));
		await expect(args.onViewChange).toHaveBeenCalledWith({
			sort: "username",
			desc: false,
			page: 0,
		});
	},
};

export const Loading: Story = {
	args: {
		isLoading: true,
		users: [],
	},
};

export const EmptyState: Story = {
	args: {
		users: [],
		isLoading: false,
	},
};

export const SingleUser: Story = {
	args: {
		users: [aliceOnTwoTeams],
	},
};

export const UsersWithoutTeams: Story = {
	args: {
		users: [
			{
				id: 6,
				login: "john",
				name: "John Doe",
				hidden: false,
				url: "https://github.com/john",
				teams: [],
				user: {
					id: "user-6",
					login: "john",
					name: "John Doe",
					email: "john@example.com",
				},
			},
			{
				id: 7,
				login: "jane",
				name: "Jane Smith",
				hidden: false,
				url: "https://github.com/jane",
				teams: [],
				user: {
					id: "user-7",
					login: "jane",
					name: "Jane Smith",
					email: "jane@example.com",
				},
			},
		],
	},
};

export const ManyUsers: Story = {
	args: {
		users: Array.from({ length: 50 }, (_, i) => ({
			id: i + 10,
			login: `user${i + 1}`,
			name: `User ${i + 1}`,
			hidden: false,
			url: `https://github.com/user${i + 1}`,
			teams: mockTeams.filter((_team, teamIndex) => (i + teamIndex) % 3 === 0),
			user: {
				id: `user-${i + 10}`,
				name: `User ${i + 1}`,
				login: `user${i + 1}`,
				email: `user${i + 1}@example.com`,
			},
		})),
	},
};
