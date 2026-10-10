import type { Meta, StoryObj } from "@storybook/react";
import { expect, fn, screen, userEvent, within } from "storybook/test";

import { expectSettledVisible } from "@/stories/overlay";

import { withSidebarFrame } from "./sidebar-story-frame";
import { WorkspaceSwitcher } from "./WorkspaceSwitcher";

const featureFlags = {
	practicesEnabled: true,
	publishesPublicActivity: false,
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
				workspaceAddress: "https://hephaestus.build/w/aet",
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
			workspaceAddress: "https://hephaestus.build/w/aet",
			id: 1,
			status: "ACTIVE",
			providerType: "GITHUB",
			createdAt: new Date("2025-01-15T00:00:00Z"),
			...featureFlags,
		},
		onWorkspaceChange: fn(),
		onAddWorkspace: fn(),
	},
	decorators: [withSidebarFrame],
} satisfies Meta<typeof WorkspaceSwitcher>;

export default meta;
type Story = StoryObj<typeof meta>;

export const SingleWorkspace: Story = {};

const longName = "Software Engineering Education Research Group, Practice Review Pilot";

/** A name wraps rather than ending in an ellipsis, since it is the only place the reader sees it. */
export const LongWorkspaceName: Story = {
	args: {
		activeWorkspace: { ...meta.args.activeWorkspace, displayName: longName },
		workspaces: [{ ...meta.args.activeWorkspace, displayName: longName }],
	},
	play: async ({ canvas }) => {
		const name = canvas.getByText(longName);
		await expect(name.scrollWidth).toBeLessThanOrEqual(name.clientWidth);
	},
};

/** A name with no spaces in it has nowhere to break but between letters. */
export const LongUnbrokenLogin: Story = {
	args: {
		activeWorkspace: {
			...meta.args.activeWorkspace,
			accountLogin: "an-organisation-with-a-very-long-account-login-and-no-spaces",
		},
	},
	play: async ({ canvas }) => {
		const login = canvas.getByText("an-organisation-with-a-very-long-account-login-and-no-spaces");
		// The button clips what overflows it, so the label has to end inside the button.
		await expect(login.getBoundingClientRect().right).toBeLessThanOrEqual(
			canvas.getByRole("button").getBoundingClientRect().right,
		);
	},
};

export const WithoutAddWorkspace: Story = {
	args: { onAddWorkspace: undefined },
	play: async ({ canvas }) => {
		await userEvent.click(canvas.getByRole("button", { name: /AET/u }));
		const menu = within(await screen.findByRole("menu"));
		await expectSettledVisible(menu.getByRole("menuitem", { name: /AET/u }));
		await expect(
			menu.queryByRole("menuitem", { name: "Create workspace" }),
		).not.toBeInTheDocument();
	},
};

export const MultipleWorkspaces: Story = {
	args: {
		workspaces: [
			{
				displayName: "AET",
				accountLogin: "aet-org",
				workspaceSlug: "aet",
				workspaceAddress: "https://hephaestus.build/w/aet",
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
				workspaceAddress: "https://hephaestus.build/w/personal",
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
				workspaceAddress: "https://hephaestus.build/w/team-b",
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
			workspaceAddress: "https://hephaestus.build/w/aet",
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
