import type { Meta, StoryObj } from "@storybook/react";
import { expect, fn, userEvent } from "storybook/test";

import type { AdminWorkspaceView } from "@/api/types.gen";
import { expectNoPageOverflow } from "@/stories/reflow";
import { expectGenuinelyDisabled } from "@/test/controls";

import { AdminWorkspacesTable } from "./AdminWorkspacesTable";

const activeWorkspace: AdminWorkspaceView = {
	id: 1,
	workspaceSlug: "aet",
	displayName: "AET",
	status: "ACTIVE",
	accountLogin: "aet-org",
	providerType: "GITHUB",
	ownerLogin: "octocat",
	memberCount: 42,
	createdAt: new Date("2026-01-15T00:00:00Z"),
};

const suspendedWorkspace: AdminWorkspaceView = {
	id: 2,
	workspaceSlug: "intro-course",
	displayName: "Intro Course",
	status: "SUSPENDED",
	accountLogin: "ase/ios",
	providerType: "GITLAB",
	memberCount: 0,
	createdAt: new Date("2026-03-01T00:00:00Z"),
};

const workspaces = [activeWorkspace, suspendedWorkspace];

const meta = {
	component: AdminWorkspacesTable,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	args: {
		workspaces,
		isLoading: false,
		hasSearch: false,
		onViewAsUser: fn(),
	},
} satisfies Meta<typeof AdminWorkspacesTable>;

export default meta;
type Story = StoryObj<typeof meta>;

/**
 * An active workspace opens its administration on the workspace settings, or starts a user view. A
 * workspace that is not active offers neither.
 */
export const Default: Story = {
	play: async ({ args, canvas }) => {
		await expect(canvas.getByRole("link", { name: "Open administration of AET" })).toHaveAttribute(
			"href",
			"/w/aet/admin/settings",
		);
		await userEvent.click(canvas.getByRole("button", { name: "View as user in AET" }));
		await expect(args.onViewAsUser).toHaveBeenCalledWith(activeWorkspace);
		await expect(
			canvas.queryByRole("link", { name: "Open administration of Intro Course" }),
		).not.toBeInTheDocument();
		await expectGenuinelyDisabled(
			canvas.getByRole("button", { name: "Open administration of Intro Course" }),
		);
		await expectGenuinelyDisabled(
			canvas.getByRole("button", { name: "View as user in Intro Course" }),
		);
		canvas.getByText(
			"This workspace is not active, so you cannot open its administration or view it as a user.",
		);
		// Statuses and providers in words, never the constants the wire carries.
		canvas.getByText("Active");
		canvas.getByText("Suspended");
		canvas.getByText("GitHub");
		canvas.getByText("GitLab");
		await expect(
			canvas.queryByText(/^(?:ACTIVE|SUSPENDED|GITHUB|GITLAB)$/u),
		).not.toBeInTheDocument();
	},
};

export const Loading: Story = {
	args: { isLoading: true },
};

export const Empty: Story = {
	args: { workspaces: [] },
	play: async ({ canvas }) => {
		canvas.getByText("No workspaces yet");
	},
};

export const EmptyWithSearch: Story = {
	args: { workspaces: [], hasSearch: true },
	play: async ({ canvas }) => {
		canvas.getByText("No workspaces match your search");
	},
};

/** A first read failed. Nothing is listed, and Retry asks again. */
export const LoadError: Story = {
	args: {
		workspaces: [],
		loadFailure: {
			error: { status: 503, detail: "The workspace directory is temporarily unavailable." },
			onRetry: fn(),
			pending: false,
		},
	},
	play: async ({ canvas }) => {
		canvas.getByText("We could not load workspaces");
		await expect(canvas.getByRole("button", { name: "Retry" })).toBeEnabled();
		await expect(canvas.queryByRole("table")).not.toBeInTheDocument();
	},
};

/** The list was refused: it says so, offers no Retry, and lists no workspace to reach. */
export const Refused: Story = {
	args: {
		workspaces: [],
		loadFailure: { error: { status: 403 }, onRetry: fn(), pending: false },
	},
	play: async ({ canvas }) => {
		canvas.getByText("You do not have access to workspaces");
		await expect(canvas.queryByRole("button", { name: "Retry" })).not.toBeInTheDocument();
		await expect(canvas.queryByText("No workspaces yet")).not.toBeInTheDocument();
	},
};

/**
 * A failed refresh keeps the workspaces last read, with their support actions, under the failure. A
 * workspace that is not active still cannot be viewed.
 */
export const RefreshFailed: Story = {
	args: { refreshFailure: { error: { status: 503 }, onRetry: fn(), pending: false } },
	play: async ({ canvas }) => {
		canvas.getByText("These workspaces may be out of date");
		await expect(canvas.getByRole("button", { name: "Retry" })).toBeEnabled();
		await expect(canvas.getByRole("button", { name: "View as user in AET" })).toBeEnabled();
		await expectGenuinelyDisabled(
			canvas.getByRole("button", { name: "View as user in Intro Course" }),
		);
	},
};

export const RefreshRetrying: Story = {
	args: { refreshFailure: { error: { status: 503 }, onRetry: fn(), pending: true } },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("button", { name: "Retrying…" })).toBeDisabled();
		await expect(canvas.getByRole("button", { name: "View as user in AET" })).toBeEnabled();
	},
};

export const Reflow: Story = {
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	play: expectNoPageOverflow,
};
