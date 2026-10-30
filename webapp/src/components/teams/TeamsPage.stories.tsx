import type { Meta, StoryObj } from "@storybook/react";
import { expect, fn } from "storybook/test";

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
		slug: "team-1",
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
		slug: "team-2",
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
		slug: "team-3",
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
		slug: "team-4",
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
		slug: "team-5",
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
		state: { status: "ready" },
	},
	play: async ({ canvas }) => {
		await expect(
			canvas.getByText("See members grouped by team and explore their activity."),
		).toBeVisible();
	},
};

export const Loading: Story = {
	args: {
		teams: [],
		state: { status: "loading" },
	},
};

export const Empty: Story = {
	args: {
		teams: [],
		state: { status: "ready" },
	},
};

/** The team list could not be read: the page says so and offers a retry, never "No teams yet". */
export const Failed: Story = {
	args: {
		teams: [],
		state: { status: "error", error: new Error("Network down"), onRetry: fn() },
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText("We could not load teams")).toBeVisible();
		await expect(canvas.getByRole("button", { name: "Retry" })).toBeVisible();
		await expect(canvas.queryByText(/No teams yet/u)).not.toBeInTheDocument();
	},
};

/** A refresh failed: the teams already read stay, under the failure and its retry. */
export const RefreshFailed: Story = {
	args: {
		teams: mockTeams,
		state: { status: "ready", refresh: { error: new Error("Network down"), onRetry: fn() } },
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText("We could not refresh teams")).toBeVisible();
		await expect(canvas.getByRole("button", { name: "Retry" })).toBeVisible();
		await expect(canvas.getByRole("heading", { level: 2, name: "Frontend" })).toBeVisible();
	},
};

/** The same teams remain visible while the requested retry is still reading. */
export const RetryingRefresh: Story = {
	args: {
		teams: mockTeams,
		state: {
			status: "ready",
			refresh: { error: new Error("Network down"), onRetry: fn(), pending: true },
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("button", { name: "Retrying…" })).toBeDisabled();
		await expect(canvas.getByRole("heading", { level: 2, name: "Frontend" })).toBeVisible();
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
		state: { status: "ready" },
	},
	parameters: {
		viewport: { defaultViewport: "reflow" },
		chromatic: { viewports: [320] },
	},
	play: expectNoPageOverflow,
};
