import type { Meta, StoryObj } from "@storybook/react";
import { expect, fn, screen, userEvent, within } from "storybook/test";

import { expectSettledVisible } from "@/stories/overlay";
import { expectNoPageOverflow, expectWithinViewport } from "@/stories/reflow";

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
				component:
					"Switch workspaces with the menu. The switcher takes no Cmd or Ctrl number keys, because the browser switches tabs with them.",
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

/**
 * A workspace named the same as its account says the name once, on the button and in the menu. The
 * avatar repeats what the words already say, so it adds nothing to either name.
 */
export const SameNameAndLogin: Story = {
	args: {
		activeWorkspace: {
			...meta.args.activeWorkspace,
			displayName: "HephaestusTest",
			accountLogin: "HephaestusTest",
		},
		workspaces: [
			{
				...meta.args.activeWorkspace,
				displayName: "HephaestusTest",
				accountLogin: "HephaestusTest",
			},
		],
	},
	play: async ({ canvas }) => {
		const trigger = canvas.getByRole("button", { name: "HephaestusTest" });
		await expect(within(trigger).getAllByText("HephaestusTest")).toHaveLength(1);

		await userEvent.click(trigger);
		const menu = within(await screen.findByRole("menu"));
		const item = within(menu.getByRole("group", { name: "Workspaces" })).getByRole("menuitem", {
			name: "HephaestusTest",
		});
		await expectSettledVisible(item);
		await expect(within(item).getAllByText("HephaestusTest")).toHaveLength(1);
	},
};

/** An account whose login differs only in case is still a different word, so both lines stay. */
export const LoginDiffersOnlyInCase: Story = {
	args: {
		activeWorkspace: {
			...meta.args.activeWorkspace,
			displayName: "HephaestusTest",
			accountLogin: "hephaestustest",
		},
		workspaces: [
			{
				...meta.args.activeWorkspace,
				displayName: "HephaestusTest",
				accountLogin: "hephaestustest",
			},
		],
	},
	play: async ({ canvas }) => {
		const trigger = canvas.getByRole("button", { name: "HephaestusTest hephaestustest" });
		await expect(within(trigger).getByText("HephaestusTest")).toBeVisible();
		await expect(within(trigger).getByText("hephaestustest")).toBeVisible();
	},
};

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
	play: async ({ args, canvas }) => {
		// A login that differs from the name tells two workspaces apart, so it stays on every line.
		await userEvent.click(canvas.getByRole("button", { name: "AET aet-org" }));
		const menu = within(await screen.findByRole("menu"));
		await expectSettledVisible(menu.getByRole("menuitem", { name: "AET aet-org" }));
		await expect(menu.getByRole("menuitem", { name: "Personal personal" })).toBeVisible();
		await expect(menu.getByRole("menuitem", { name: "Team B team-b" })).toBeVisible();

		await userEvent.click(menu.getByRole("menuitem", { name: "Personal personal" }));
		await expect(args.onWorkspaceChange).toHaveBeenCalledWith(
			expect.objectContaining({ workspaceSlug: "personal" }),
		);
	},
};

/** On a phone, a long name and login wrap on the button, and the menu opens inside the screen. */
export const PhoneLongNames: Story = {
	args: {
		activeWorkspace: {
			...meta.args.activeWorkspace,
			displayName: longName,
			accountLogin: "an-organisation-with-a-very-long-account-login-and-no-spaces",
		},
		workspaces: [
			{
				...meta.args.activeWorkspace,
				displayName: longName,
				accountLogin: "an-organisation-with-a-very-long-account-login-and-no-spaces",
			},
		],
	},
	parameters: { viewport: { defaultViewport: "reflow" } },
	play: async ({ canvas }) => {
		await expect(window.innerWidth).toBe(320);
		const trigger = canvas.getByRole("button", {
			name: `${longName} an-organisation-with-a-very-long-account-login-and-no-spaces`,
		});
		await expectNoPageOverflow();
		await userEvent.click(trigger);
		const item = within(await screen.findByRole("menu")).getByRole("menuitem", {
			name: new RegExp(`^${longName}`, "u"),
		});
		await expectSettledVisible(item);
		await expectWithinViewport(item);
	},
};

export const NoWorkspacesRegular: Story = {
	args: {
		workspaces: [],
		activeWorkspace: undefined,
		isAppAdmin: false,
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("button", { name: "No workspace" })).toBeDisabled();
	},
};

export const NoWorkspacesAppAdmin: Story = {
	args: {
		workspaces: [],
		activeWorkspace: undefined,
		isAppAdmin: true,
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("button", { name: "Create workspace" })).toBeEnabled();
	},
};

export const Loading: Story = {
	args: {
		workspaces: [],
		activeWorkspace: undefined,
		isLoading: true,
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("button", { name: "Loading workspaces" })).toBeDisabled();
	},
};
