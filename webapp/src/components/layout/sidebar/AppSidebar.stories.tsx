import type { Meta, StoryObj } from "@storybook/react";
import { expect } from "storybook/test";

import type { ChatThreadSummary } from "@/api/types.gen";
import { SidebarProvider } from "@/components/ui/sidebar";
import { minutesBefore, STORY_NOW } from "@/stories/story-clock";

import { AppSidebar } from "./AppSidebar";

const mockWorkspace = {
	displayName: "AET",
	accountLogin: "aet-org",
	workspaceSlug: "aet",
	id: 1,
	status: "ACTIVE",
	providerType: "GITHUB",
	createdAt: new Date("2025-01-15T00:00:00Z"),
	practicesEnabled: true,
	mentorEnabled: true,
	leaderboardEnabled: true,
	progressionEnabled: false,
	leaguesEnabled: false,
} as const;

const meta = {
	component: AppSidebar,
	parameters: {
		layout: "fullscreen",
	},
	tags: ["autodocs"],
	args: {
		username: "johnDoe",
		isAdmin: false,
		isAppAdmin: false,
		isMember: true,
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
		username: "johndoe",
		isAdmin: false,
		context: "main",
		activeWorkspace: mockWorkspace,
	},
	play: async ({ canvas }) => {
		await expect(canvas.queryByText("Administration")).not.toBeInTheDocument();
		await expect(canvas.getByRole("link", { name: /AI mentor/u })).toBeVisible();
	},
};

export const WorkspaceAdminUser: Story = {
	args: {
		username: "admin",
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
		username: "admin",
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
		username: "alex",
		isAdmin: false,
		isAppAdmin: false,
		readOnly: true,
		context: "mentor",
		mentorThreads: [
			{ id: "1", title: "React Hooks Best Practices", createdAt: new Date(STORY_NOW) },
		] satisfies ChatThreadSummary[],
		mentorThreadsLoading: false,
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText("React Hooks Best Practices")).toBeVisible();
		await expect(canvas.queryByText("New chat")).not.toBeInTheDocument();
		await expect(canvas.queryByText("User settings")).not.toBeInTheDocument();
	},
};

export const AdminContext: Story = {
	args: {
		username: "admin",
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
		username: "admin",
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

export const MentorContext: Story = {
	args: {
		username: "mentor",
		isAdmin: false,
		context: "mentor",
		mentorThreads: [
			{
				id: "1",
				title: "React Hooks Best Practices",
				createdAt: new Date(STORY_NOW),
			},
			{
				id: "2",
				title: "TypeScript Generic Types",
				createdAt: new Date(STORY_NOW),
			},
			{
				id: "3",
				title: "API Architecture Review",
				createdAt: minutesBefore(24 * 60 + 1),
			},
		] satisfies ChatThreadSummary[],
		mentorThreadsLoading: false,
	},
};

export const MentorLoading: Story = {
	args: {
		username: "mentor",
		isAdmin: false,
		context: "mentor",
		mentorThreadsLoading: true,
	},
};

export const AllFeaturesDisabled: Story = {
	args: {
		activeWorkspace: {
			...mockWorkspace,
			leaderboardEnabled: false,
		},
		workspaces: [
			{
				...mockWorkspace,
				leaderboardEnabled: false,
			},
		],
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

export const HephHiddenFromNonMember: Story = {
	args: {
		isMember: false,
	},
	play: async ({ canvas }) => {
		await expect(canvas.queryByRole("link", { name: /AI mentor/u })).not.toBeInTheDocument();
	},
};

export const HephOffInWorkspace: Story = {
	args: {
		activeWorkspace: { ...mockWorkspace, mentorEnabled: false },
		workspaces: [{ ...mockWorkspace, mentorEnabled: false }],
	},
	play: async ({ canvas }) => {
		await expect(canvas.queryByRole("link", { name: /AI mentor/u })).not.toBeInTheDocument();
	},
};
