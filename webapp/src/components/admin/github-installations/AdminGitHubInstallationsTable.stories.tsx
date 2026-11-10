import type { Meta, StoryObj } from "@storybook/react";
import { expect, fn, userEvent, within } from "storybook/test";

import type { GitHubInstallationRequest } from "@/api/types.gen";
import { STORY_NOW } from "@/stories/story-clock";

import { AdminGitHubInstallationsTable } from "./AdminGitHubInstallationsTable";

const at = new Date(STORY_NOW);

const pending: GitHubInstallationRequest = {
	installationId: 101,
	accountLogin: "octo-org",
	accountKind: "ORGANIZATION",
	status: "PENDING",
	receivedAt: at,
};

const declined: GitHubInstallationRequest = {
	installationId: 102,
	accountLogin: "mona",
	accountKind: "USER",
	status: "DECLINED",
	receivedAt: at,
	decidedAt: at,
};

const adopted: GitHubInstallationRequest = {
	installationId: 103,
	accountLogin: "design-lab",
	accountKind: "ORGANIZATION",
	status: "ADOPTED",
	receivedAt: at,
	decidedAt: at,
	workspaceSlug: "design-lab",
};

/** A personal account's adopted installation whose workspace was deleted: its login is removed. */
const removedPersonalAccount: GitHubInstallationRequest = {
	installationId: 105,
	accountKind: "USER",
	status: "ADOPTED",
	receivedAt: at,
	decidedAt: at,
};

const adoptedWorkspaceDeleted: GitHubInstallationRequest = {
	installationId: 104,
	accountLogin: "old-course",
	accountKind: "ORGANIZATION",
	status: "ADOPTED",
	receivedAt: at,
	decidedAt: at,
};

const meta = {
	component: AdminGitHubInstallationsTable,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	args: {
		installations: [pending, declined, adopted, adoptedWorkspaceDeleted, removedPersonalAccount],
		isLoading: false,
		deciding: new Set<number>(),
		onAdopt: fn(),
		onDecline: fn(),
	},
} satisfies Meta<typeof AdminGitHubInstallationsTable>;

export default meta;
type Story = StoryObj<typeof meta>;

/**
 * A waiting installation can be adopted or declined. A declined one can still be adopted. An adopted one opens
 * its workspace, or says that its workspace is deleted.
 */
export const Default: Story = {
	play: async ({ args, canvas }) => {
		await userEvent.click(
			canvas.getByRole("button", { name: "Adopt the installation on octo-org" }),
		);
		await expect(args.onAdopt).toHaveBeenCalledWith(pending);
		await userEvent.click(
			canvas.getByRole("button", { name: "Decline the installation on octo-org" }),
		);
		await expect(args.onDecline).toHaveBeenCalledWith(pending);

		const declinedRow = canvas.getByRole("row", { name: /mona/u });
		await expect(
			within(declinedRow).getByRole("button", { name: "Adopt the installation on mona" }),
		).toBeEnabled();
		await expect(within(declinedRow).queryByRole("button", { name: /Decline/u })).toBeNull();

		await expect(
			canvas.getByRole("link", { name: "Open workspace for design-lab" }),
		).toHaveAttribute("href", "/w/design-lab");
		const deletedRow = canvas.getByRole("row", { name: /old-course/u });
		await expect(within(deletedRow).getByText("Workspace deleted")).toBeVisible();
		await expect(within(deletedRow).queryByRole("button")).toBeNull();

		const removedRow = canvas.getByRole("row", { name: /Removed personal account/u });
		await expect(within(removedRow).getByText("Workspace deleted")).toBeVisible();
		// Statuses and account types in words, never the constants the wire carries.
		canvas.getByText("Waiting for approval");
		within(declinedRow).getByText("Personal account");
		await expect(
			canvas.queryByText(/^(?:PENDING|DECLINED|ADOPTED|USER|ORGANIZATION)$/u),
		).toBeNull();
	},
};

/** A decision on its way holds that installation's actions, and only those. */
export const Deciding: Story = {
	args: { deciding: new Set([101]) },
	play: async ({ canvas }) => {
		await expect(
			canvas.getByRole("button", { name: "Adopt the installation on octo-org" }),
		).toBeDisabled();
		await expect(
			canvas.getByRole("button", { name: "Decline the installation on octo-org" }),
		).toBeDisabled();
		await expect(
			canvas.getByRole("button", { name: "Adopt the installation on mona" }),
		).toBeEnabled();
	},
};

export const Loading: Story = {
	args: { isLoading: true },
};

export const Empty: Story = {
	args: { installations: [] },
	play: async ({ canvas }) => {
		canvas.getByText("No GitHub App installations yet");
	},
};

/** A first read failed. Nothing is listed, and Retry asks again. */
export const LoadError: Story = {
	args: {
		installations: [],
		loadFailure: { error: { status: 503 }, onRetry: fn(), pending: false },
	},
	play: async ({ canvas }) => {
		canvas.getByText("We could not load GitHub installations");
		await expect(canvas.getByRole("button", { name: "Retry" })).toBeEnabled();
		await expect(canvas.queryByRole("table")).toBeNull();
	},
};

/** A failed refresh keeps the installations last read, with their actions, under the failure. */
export const RefreshFailed: Story = {
	args: { refreshFailure: { error: { status: 503 }, onRetry: fn(), pending: false } },
	play: async ({ canvas }) => {
		canvas.getByText("These installations may be out of date");
		await expect(
			canvas.getByRole("button", { name: "Adopt the installation on octo-org" }),
		).toBeEnabled();
	},
};
