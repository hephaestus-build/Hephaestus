import type { Meta, StoryObj } from "@storybook/react";
import { expect, fn, userEvent, waitFor, within } from "storybook/test";

import { SidebarProvider } from "@/components/ui/sidebar";

import { AppSidebar } from "./AppSidebar";

const mockWorkspace = {
	displayName: "AET",
	accountLogin: "aet-org",
	workspaceSlug: "aet",
	workspaceAddress: "https://hephaestus.build/w/aet",
	id: 1,
	status: "ACTIVE",
	providerType: "GITHUB",
	createdAt: new Date("2025-01-15T00:00:00Z"),
	practicesEnabled: true,
	publishesPublicActivity: false,
} as const;

const meta = {
	component: AppSidebar,
	parameters: {
		layout: "fullscreen",
	},
	tags: ["autodocs"],
	args: {
		isAdmin: false,
		isAppAdmin: false,
		showConversation: true,
		integrationKinds: ["GITHUB", "SLACK", "OUTLINE"],
		context: "main",
		workspaces: [mockWorkspace],
		activeWorkspace: mockWorkspace,
	},
	decorators: [
		(Story) => (
			<SidebarProvider className="w-full max-w-[16rem]">
				<Story />
			</SidebarProvider>
		),
	],
} satisfies Meta<typeof AppSidebar>;

export default meta;
type Story = StoryObj<typeof meta>;

export const RegularUser: Story = {
	args: {
		isAdmin: false,
		context: "main",
		activeWorkspace: mockWorkspace,
	},
	play: async ({ canvas }) => {
		await expect(canvas.queryByText("Administration")).not.toBeInTheDocument();
		await expect(canvas.getByRole("link", { name: /Ask Hephaestus/u })).toBeVisible();
		await expect(
			within(canvas.getByRole("list", { name: "You" }))
				.getAllByRole("link")
				.map((link) => link.textContent),
		).toEqual(["Practice profile", "Activity", "Ask Hephaestus"]);
		await expect(
			within(canvas.getByRole("list", { name: "Workspace" }))
				.getAllByRole("link")
				.map((link) => link.textContent),
		).toEqual(["Practices across the workspace", "Workspace activity", "Teams"]);
		// The only landmark a screen reader can jump to for the app's navigation.
		const navigation = canvas.getByRole("navigation", { name: "Primary" });
		await expect(within(navigation).getByRole("link", { name: "Activity" })).toBeVisible();
	},
};

/**
 * Collapsed to icons on a desktop, the workspace button shows only the avatar but still says which
 * workspace it is: the name and login stay readable to a screen reader, whether the avatar loads or not.
 */
export const Collapsed: Story = {
	decorators: [
		(Story) => (
			<SidebarProvider defaultOpen={false} className="w-full max-w-[16rem]">
				<Story />
			</SidebarProvider>
		),
	],
	play: async ({ canvas, canvasElement }) => {
		const workspace = canvas.getByRole("button", { name: "AET aet-org" });
		// Icon width proves the real sidebar collapsed, not a story that only names it so.
		await expect(workspace.getBoundingClientRect().width).toBeLessThanOrEqual(48);
		canvas.getByRole("list", { name: "You" });
		canvas.getByRole("list", { name: "Workspace" });
		await expect(canvas.getByRole("separator")).toBeVisible();
		canvas.getByRole("link", { name: "Activity" }).focus();
		await userEvent.tab();
		await waitFor(async () =>
			expect(
				within(canvasElement.ownerDocument.body).getByText("Ask Hephaestus", {
					selector: '[data-slot="tooltip-content"]',
				}),
			).toBeVisible(),
		);
	},
};

/** Collapsed with no workspace, a member's button still says there is none, and stays unavailable. */
export const CollapsedNoWorkspace: Story = {
	decorators: Collapsed.decorators,
	args: {
		isAppAdmin: false,
		workspaces: [],
		activeWorkspace: undefined,
	},
	play: async ({ canvas }) => {
		const empty = canvas.getByRole("button", { name: "No workspace" });
		await expect(empty).toBeDisabled();
		await expect(empty.getBoundingClientRect().width).toBeLessThanOrEqual(48);
	},
};

/** Collapsed with no workspace, an instance admin's button still says that it creates one. */
export const CollapsedNoWorkspaceAppAdmin: Story = {
	decorators: Collapsed.decorators,
	args: {
		isAppAdmin: true,
		workspaces: [],
		activeWorkspace: undefined,
	},
	play: async ({ canvas }) => {
		const create = canvas.getByRole("button", { name: "Create workspace" });
		await expect(create).toBeEnabled();
		await expect(create.getBoundingClientRect().width).toBeLessThanOrEqual(48);
	},
};

export const WorkspaceAdminUser: Story = {
	args: {
		isAdmin: true,
		isAppAdmin: false,
		context: "main",
		activeWorkspace: mockWorkspace,
	},
	play: async ({ canvas }) => {
		canvas.getByRole("list", { name: "Administration" });
		await expect(canvas.getByRole("link", { name: "Teams" })).toHaveAttribute(
			"href",
			"/w/aet/teams",
		);
		await expect(canvas.getByRole("link", { name: "Team setup" })).toHaveAttribute(
			"href",
			"/w/aet/admin/teams",
		);
		await expect(canvas.queryByText("Instance administration")).not.toBeInTheDocument();
	},
};

export const AdminUser: Story = {
	args: {
		isAdmin: true,
		isAppAdmin: true,
		context: "main",
		activeWorkspace: mockWorkspace,
	},
	play: async ({ canvas }) => {
		canvas.getByText("Administration");
	},
};

export const ReadOnlyUserView: Story = {
	args: {
		isAdmin: false,
		isAppAdmin: false,
		readOnly: true,
		context: "main",
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("link", { name: /^Conversation$/u })).toBeVisible();
		await expect(canvas.queryByText("User settings")).not.toBeInTheDocument();
	},
};

export const AdminContext: Story = {
	args: {
		isAppAdmin: true,
		context: "admin",
		activeWorkspace: mockWorkspace,
	},
	play: async ({ canvas, canvasElement }) => {
		canvas.getByText("Practice catalog");
		await expect(canvas.getByRole("link", { name: "Back to app" })).toHaveAttribute(
			"href",
			"/w/aet",
		);
		const content = canvasElement.querySelector<HTMLElement>('[data-slot="sidebar-content"]');
		if (!content) {
			throw new Error("sidebar content not found");
		}
		await expect(content.scrollWidth).toBeLessThanOrEqual(content.clientWidth);
	},
};

export const AdminContextNoWorkspace: Story = {
	args: {
		isAppAdmin: true,
		context: "admin",
		workspaces: [],
		activeWorkspace: undefined,
	},
	play: async ({ canvas }) => {
		// The footer link takes the name of the console's own group, so the place has one name.
		await expect(canvas.getAllByText("Instance administration")).toHaveLength(2);
		await expect(canvas.getByRole("link", { name: "Back to app" })).toHaveAttribute("href", "/");
		await expect(canvas.queryByText(/no workspace/iu)).not.toBeInTheDocument();
	},
};

export const PracticeReviewsOff: Story = {
	args: {
		activeWorkspace: { ...mockWorkspace, practicesEnabled: false },
		workspaces: [{ ...mockWorkspace, practicesEnabled: false }],
	},
};

export const NoWorkspace: Story = {
	args: {
		workspaces: [],
		activeWorkspace: undefined,
	},
};

export const LoadingWorkspaces: Story = {
	args: {
		workspaces: [],
		activeWorkspace: undefined,
		workspacesLoading: true,
	},
};

/** A non-member, or a workspace with no conversation model ready yet: the other entries stay. */
export const ConversationHidden: Story = {
	args: {
		showConversation: false,
	},
	play: async ({ canvas }) => {
		await expect(canvas.queryByRole("link", { name: /Ask Hephaestus/u })).not.toBeInTheDocument();
		await expect(canvas.getByRole("link", { name: "Activity" })).toBeVisible();
	},
};

export const InstanceWorkspaceAdministration: Story = {
	args: {
		isAppAdmin: true,
		isAdmin: true,
		showConversation: false,
		activeWorkspace: undefined,
		administrationWorkspaceSlug: "tutors",
		administrationWorkspace: {
			displayName: "Tutors",
			providerType: "GITLAB",
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Tutors administration")).toBeVisible();
		await expect(canvas.getByRole("link", { name: "Workspace settings" })).toHaveAttribute(
			"href",
			"/w/tutors/admin/settings",
		);
		await expect(canvas.queryByRole("link", { name: "Activity" })).not.toBeInTheDocument();
		await expect(canvas.queryByRole("list", { name: "You" })).not.toBeInTheDocument();
		await expect(canvas.queryByRole("list", { name: "Workspace" })).not.toBeInTheDocument();
		await expect(canvas.getByRole("link", { name: "Team setup" })).toHaveAttribute(
			"href",
			"/w/tutors/admin/teams",
		);
		await expect(canvas.queryByText("AET")).not.toBeInTheDocument();
	},
};

export const InstanceAdministrationLoading: Story = {
	args: {
		isAppAdmin: true,
		showConversation: false,
		activeWorkspace: undefined,
		administrationWorkspaceSlug: "tutors",
		workspacesLoading: true,
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Workspace administration")).toBeVisible();
		await expect(canvas.getByRole("link", { name: "Back to instance workspaces" })).toBeVisible();
		await expect(canvas.queryByRole("alert")).not.toBeInTheDocument();
		await expect(canvas.queryByText("AET")).not.toBeInTheDocument();
	},
};

/** The details did not load for a reason a second read can fix, so the sidebar offers one. */
export const InstanceAdministrationUnavailable: Story = {
	args: {
		isAppAdmin: true,
		showConversation: false,
		activeWorkspace: undefined,
		administrationWorkspaceSlug: "tutors",
		administrationWorkspaceError: { error: { status: 503 }, onRetry: fn() },
	},
	play: async ({ canvas }) => {
		// The page announces the failure; the sidebar says why its links are missing, without an alert.
		await expect(canvas.queryByRole("alert")).not.toBeInTheDocument();
		await expect(canvas.getByText("We could not load the workspace details")).toBeVisible();
		await expect(canvas.getByRole("button", { name: "Retry" })).toBeEnabled();
		await expect(canvas.getByRole("link", { name: "Back to instance workspaces" })).toBeVisible();
		await expect(canvas.queryByText("AET")).not.toBeInTheDocument();
		await expect(canvas.queryByText("No workspace")).not.toBeInTheDocument();
	},
};

/** A refused read says so and offers no retry, which would be refused again. */
export const InstanceAdministrationRefused: Story = {
	args: {
		...InstanceAdministrationUnavailable.args,
		administrationWorkspaceError: { error: { status: 403 }, onRetry: fn() },
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText("You do not have access to the workspace details")).toBeVisible();
		await expect(canvas.queryByRole("button", { name: "Retry" })).not.toBeInTheDocument();
	},
};

/** Shared pages stay available when the workspace does not run practice reviews. */
export const PracticesOff: Story = {
	args: { activeWorkspace: { ...mockWorkspace, practicesEnabled: false } },
	play: async ({ canvas }) => {
		await expect(canvas.queryByRole("link", { name: "Practice profile" })).not.toBeInTheDocument();
		await expect(
			canvas.queryByRole("link", { name: "Practices across the workspace" }),
		).not.toBeInTheDocument();
		await expect(canvas.getByRole("link", { name: "Workspace activity" })).toBeVisible();
	},
};

/**
 * An admin keeps the Practices section when practices are off: Review settings is where they are
 * turned on, Practice setup is where the workspace adopts practices first, and past reviews stay
 * readable.
 */
export const PracticesOffAdmin: Story = {
	args: { isAdmin: true, activeWorkspace: { ...mockWorkspace, practicesEnabled: false } },
	play: async ({ canvas }) => {
		await expect(canvas.queryByRole("link", { name: "Practice profile" })).not.toBeInTheDocument();
		await userEvent.click(canvas.getByRole("button", { name: "Practices" }));
		await expect(canvas.getByRole("link", { name: "Review settings" })).toHaveAttribute(
			"href",
			"/w/aet/admin/practices/review",
		);
	},
};

export const CollapsedWorkspaceAdmin: Story = {
	args: WorkspaceAdminUser.args,
	decorators: Collapsed.decorators,
	play: async ({ canvas }) => {
		await expect(canvas.getAllByRole("separator")).toHaveLength(2);
		for (const separator of canvas.getAllByRole("separator")) {
			await expect(separator).toBeVisible();
		}
		await expect(canvas.getByRole("link", { name: "Team setup" })).toHaveAttribute(
			"href",
			"/w/aet/admin/teams",
		);
	},
};
