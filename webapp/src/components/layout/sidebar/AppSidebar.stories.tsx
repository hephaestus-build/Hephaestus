import type { Meta, StoryObj } from "@storybook/react";
import { expect, within } from "storybook/test";

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
		showMentor: true,
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
		await expect(canvas.getByRole("link", { name: /AI mentor/u })).toBeVisible();
		// The only landmark a screen reader can jump to for the app's navigation.
		const navigation = canvas.getByRole("navigation", { name: "Primary" });
		await expect(within(navigation).getByRole("link", { name: "Activity" })).toBeVisible();
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
		canvas.getByText("Administration");
		await expect(canvas.queryByText("Instance admin")).not.toBeInTheDocument();
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
		await expect(canvas.getByRole("link", { name: /AI mentor/u })).toBeVisible();
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
		canvas.getByText("Instance administration");
		canvas.getByText("Back to app");
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

/** A non-member, or a workspace with no Heph model ready yet: the other entries stay. */
export const HephHidden: Story = {
	args: {
		showMentor: false,
	},
	play: async ({ canvas }) => {
		await expect(canvas.queryByRole("link", { name: /AI mentor/u })).not.toBeInTheDocument();
		await expect(canvas.getByRole("link", { name: "Activity" })).toBeVisible();
	},
};
