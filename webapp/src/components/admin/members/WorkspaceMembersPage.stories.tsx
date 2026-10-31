import type { Meta, StoryObj } from "@storybook/react";
import { expect, fn } from "storybook/test";

import { withStandardPage } from "@/stories/decorators";
import { expectNoPageOverflow, expectTablesScrollInPlace } from "@/stories/reflow";

import { mockMemberships, mockTeams } from "./fixtures";
import { workspaceMemberRows } from "./workspace-members";
import { WorkspaceMembersPage } from "./WorkspaceMembersPage";

const members = workspaceMemberRows(mockMemberships);

const meta = {
	component: WorkspaceMembersPage,
	parameters: {
		layout: "fullscreen",
	},
	decorators: [withStandardPage],
	tags: ["autodocs"],
	args: {
		workspaceSlug: "acme",
		members,
		rowCount: members.length,
		teams: mockTeams,
		isLoading: false,
		onToggleHidden: fn(),
		onRoleChange: fn(),
		view: { q: "", team: "all", sort: "name", desc: false, page: 0, size: 10 },
		onViewChange: fn(),
		renderPageLink: (page, props) => <a {...props} href={`?page=${page}`} />,
	},
} satisfies Meta<typeof WorkspaceMembersPage>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {};

/** The Review scope column only mirrors the setting, so the page points to where it changes. */
export const ReviewScopeChangesInReviewSettings: Story = {
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("columnheader", { name: "Review scope" })).toBeVisible();
		await expect(canvas.getByRole("link", { name: "Change in review settings" })).toHaveAttribute(
			"href",
			"/w/acme/admin/practices/review?section=automated-review#review-scope-heading",
		);
	},
};

export const Loading: Story = {
	args: {
		members: [],
		rowCount: 0,
		teams: [],
		isLoading: true,
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("table", { name: "Workspace members" })).toHaveAttribute(
			"aria-busy",
			"true",
		);
		await expect(canvas.queryByText("No members found")).not.toBeInTheDocument();
	},
};

export const Empty: Story = {
	args: {
		members: [],
		rowCount: 0,
		isLoading: false,
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText("This workspace has no members yet.")).toBeVisible();
	},
};

export const TeamsUnavailable: Story = {
	args: {
		members,
		teams: [],
		isLoading: false,
		teamsError: { status: 503 },
		onRetryTeams: fn(),
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText("We could not load teams")).toBeVisible();
		await expect(canvas.getByRole("button", { name: "Retry" })).toBeVisible();
		await expect(canvas.getByText("Alice Johnson")).toBeVisible();
		await expect(canvas.getByText("Diana Prince")).toBeVisible();
	},
};

export const TeamsRefused: Story = {
	args: {
		members,
		teams: [],
		isLoading: false,
		teamsError: { status: 403 },
		onRetryTeams: fn(),
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText("You do not have access to teams")).toBeVisible();
		await expect(canvas.queryByRole("button", { name: "Retry" })).not.toBeInTheDocument();
		await expect(canvas.getByText("Alice Johnson")).toBeVisible();
	},
};

export const Error: Story = {
	args: {
		members: [],
		rowCount: 0,
		error: { status: 503, detail: "Service unavailable" },
		onRetry: fn(),
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText("We could not load members")).toBeVisible();
		await expect(canvas.queryByText("No members found")).not.toBeInTheDocument();
		await expect(canvas.getByRole("button", { name: "Retry" })).toBeVisible();
	},
};

export const NoTeams: Story = {
	args: {
		members: workspaceMemberRows(mockMemberships),
		teams: [],
	},
};

export const MobileReflow: Story = {
	parameters: {
		viewport: { defaultViewport: "reflow" },
		chromatic: { viewports: [320] },
	},
	play: async () => {
		await expectNoPageOverflow();
		await expectTablesScrollInPlace();
	},
};
