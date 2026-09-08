import type { Meta, StoryObj } from "@storybook/react";
import { fn } from "storybook/test";

import { SidebarProvider } from "@/components/ui/sidebar";

import { WorkspaceSwitcher } from "./WorkspaceSwitcher";

const featureFlags = {
	practicesEnabled: true,
	mentorEnabled: true,
	leaderboardEnabled: true,
	progressionEnabled: false,
	leaguesEnabled: false,
} as const;

const meta = {
	component: WorkspaceSwitcher,
	parameters: {
		layout: "centered",
		docs: {
			description: {
				component: "Switch workspaces with the menu or ⌘1–9 on macOS and Ctrl+1–9 elsewhere.",
			},
		},
	},
	tags: ["autodocs"],
	args: {
		workspaces: [
			{
				displayName: "AET",
				accountLogin: "aet-org",
				workspaceSlug: "aet",
				id: 1,
				status: "ACTIVE",
				providerType: "GITHUB",
				createdAt: new Date("2025-01-15T00:00:00Z"),
				...featureFlags,
			},
		],
		activeWorkspace: {
			displayName: "AET",
			accountLogin: "aet-org",
			workspaceSlug: "aet",
			id: 1,
			status: "ACTIVE",
			providerType: "GITHUB",
			createdAt: new Date("2025-01-15T00:00:00Z"),
			...featureFlags,
		},
		onWorkspaceChange: fn(),
		onAddWorkspace: fn(),
	},
	decorators: [
		(Story) => (
			<SidebarProvider className="min-h-0 w-[16rem] border border-border rounded-lg p-2 bg-sidebar">
				<Story />
			</SidebarProvider>
		),
	],
} satisfies Meta<typeof WorkspaceSwitcher>;

export default meta;
type Story = StoryObj<typeof meta>;

export const SingleWorkspace: Story = {};

export const MultipleWorkspaces: Story = {
	args: {
		workspaces: [
			{
				displayName: "AET",
				accountLogin: "aet-org",
				workspaceSlug: "aet",
				id: 1,
				status: "ACTIVE",
				providerType: "GITHUB",
				createdAt: new Date("2025-01-15T00:00:00Z"),
				...featureFlags,
			},
			{
				displayName: "Personal",
				accountLogin: "personal",
				workspaceSlug: "personal",
				id: 2,
				status: "ACTIVE",
				providerType: "GITHUB",
				createdAt: new Date("2025-01-15T00:00:00Z"),
				...featureFlags,
			},
			{
				displayName: "Team B",
				accountLogin: "team-b",
				workspaceSlug: "team-b",
				id: 3,
				status: "ACTIVE",
				providerType: "GITHUB",
				createdAt: new Date("2025-01-15T00:00:00Z"),
				...featureFlags,
			},
		],
		activeWorkspace: {
			displayName: "AET",
			accountLogin: "aet-org",
			workspaceSlug: "aet",
			id: 1,
			status: "ACTIVE",
			providerType: "GITHUB",
			createdAt: new Date("2025-01-15T00:00:00Z"),
			...featureFlags,
		},
	},
};

export const NoWorkspacesRegular: Story = {
	args: {
		workspaces: [],
		activeWorkspace: undefined,
		isAppAdmin: false,
	},
};

export const NoWorkspacesAppAdmin: Story = {
	args: {
		workspaces: [],
		activeWorkspace: undefined,
		isAppAdmin: true,
	},
};

export const Loading: Story = {
	args: {
		workspaces: [],
		activeWorkspace: undefined,
		isLoading: true,
	},
};
