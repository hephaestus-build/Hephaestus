import type { Meta, StoryObj } from "@storybook/react";
import { expect, fn, userEvent, waitFor } from "storybook/test";

import { STORY_NOW, daysAfter, hoursBefore } from "@/stories/story-clock";

import { ConnectionStateNotice } from "./ConnectionStateNotice";
import { WorkspaceScmTokenSettings } from "./WorkspaceScmTokenSettings";

const meta = {
	component: WorkspaceScmTokenSettings,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	args: {
		providerLabel: "GitHub",
		isSaving: false,
		error: null,
		onSave: fn(async () => true),
	},
} satisfies Meta<typeof WorkspaceScmTokenSettings>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Empty: Story = {
	play: async ({ canvas }) => {
		await expect(canvas.getByLabelText("New personal access token")).toHaveAttribute(
			"type",
			"password",
		);
		await expect(canvas.getByRole("button", { name: "Replace token" })).toBeDisabled();
	},
};

export const ReconnectGitLab: Story = {
	args: { providerLabel: "GitLab", reconnect: true },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("button", { name: "Reconnect GitLab" })).toBeDisabled();
		await expect(canvas.queryByText("The token expiry is not available yet.")).toBeNull();
	},
};

export const ReconnectingGitLab: Story = {
	args: { providerLabel: "GitLab", reconnect: true, isSaving: true },
};

export const ReconnectGitLabError: Story = {
	args: {
		providerLabel: "GitLab",
		reconnect: true,
		error: new Error("The token cannot read this group."),
	},
};

export const Saving: Story = {
	args: { isSaving: true },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("button", { name: "Replacing token…" })).toBeDisabled();
		await expect(canvas.getByLabelText("New personal access token")).toBeDisabled();
	},
};

export const SaveError: Story = {
	args: { error: new Error("The replacement could not be stored. Try again.") },
};

export const UnreadableGitLabToken: Story = {
	args: { providerLabel: "GitLab" },
	parameters: { viewport: { defaultViewport: "reflow" } },
	render: (args) => (
		<div className="space-y-4">
			<ConnectionStateNotice
				connectionState="ACTIVE"
				displayName={args.providerLabel}
				credentialsUnreadableSince={new Date(STORY_NOW)}
				credentialRecovery="Replace it using the personal access token form below"
			/>
			<WorkspaceScmTokenSettings {...args} />
		</div>
	),
	play: async ({ canvas }) => {
		canvas.getByText("The stored token cannot be read");
		await expect(canvas.getByLabelText("New personal access token")).toBeEnabled();
	},
};

const GITHUB_EXPIRY_UNKNOWN =
	"The token expiry is not available for this connection. Check the token in GitHub and replace it below if it has expired or been revoked.";

/** A check time without an expiry date proves "no expiry" only for GitLab. */
export const GitHubExpiryUnknown: Story = {
	args: { tokenExpiryCheckedAt: hoursBefore(2) },
	play: async ({ canvas }) => {
		await expect(canvas.getByText(GITHUB_EXPIRY_UNKNOWN)).toBeVisible();
		await expect(canvas.queryByText("The token has no expiry.")).toBeNull();
		await expect(canvas.getByLabelText("New personal access token")).toBeEnabled();
	},
};

export const GitHubExpiryDate: Story = {
	args: { tokenExpiresAt: daysAfter(30), tokenExpiryCheckedAt: hoursBefore(2) },
	play: async ({ canvas }) => {
		await expect(canvas.getByText(/^The token expires on \d{1,2} \w+ \d{4}\.$/u)).toBeVisible();
		await expect(canvas.queryByText(GITHUB_EXPIRY_UNKNOWN)).toBeNull();
	},
};

export const GitHubExpiryError: Story = {
	args: { tokenMetadataError: new Error("Unavailable") },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("We could not load the token expiry.")).toBeVisible();
		await expect(canvas.queryByText(GITHUB_EXPIRY_UNKNOWN)).toBeNull();
		await expect(canvas.getByLabelText("New personal access token")).toBeEnabled();
	},
};

export const UnreadableGitHubToken: Story = {
	parameters: { viewport: { defaultViewport: "reflow" } },
	render: (args) => (
		<div className="space-y-4">
			<ConnectionStateNotice
				connectionState="ACTIVE"
				displayName={args.providerLabel}
				credentialsUnreadableSince={hoursBefore(1)}
				credentialRecovery="Replace it using the personal access token form below"
			/>
			<WorkspaceScmTokenSettings {...args} />
		</div>
	),
	play: async ({ canvas }) => {
		await expect(canvas.getByText("The stored token cannot be read")).toBeVisible();
		await expect(canvas.getByText(GITHUB_EXPIRY_UNKNOWN)).toBeVisible();
		await expect(canvas.getByLabelText("New personal access token")).toBeEnabled();
	},
};

export const FailedReplacementKeepsDraft: Story = {
	args: {
		error: {
			status: 409,
			title: "Conflict",
			detail: "This connection changed. Reload before replacing its token.",
		},
		onSave: fn(async () => false),
	},
	play: async ({ args, canvas }) => {
		const input = canvas.getByLabelText("New personal access token");
		await userEvent.type(input, "replacement-token");
		await userEvent.click(canvas.getByRole("button", { name: "Replace token" }));
		await expect(args.onSave).toHaveBeenCalledWith("replacement-token");
		await expect(input).toHaveValue("replacement-token");
		await expect(
			canvas.getByText("This connection changed. Reload before replacing its token."),
		).toBeVisible();
	},
};

export const SuccessfulReplacement: Story = {
	play: async ({ canvas }) => {
		const input = canvas.getByLabelText("New personal access token");
		await userEvent.type(input, "replacement-token");
		await userEvent.click(canvas.getByRole("button", { name: "Replace token" }));
		await waitFor(async () => expect(input).toHaveValue(""));
	},
};

export const ExpiringGitLabToken: Story = {
	args: {
		providerLabel: "GitLab",
		tokenExpiresAt: new Date("2026-07-05T00:00:00Z"),
		tokenExpiryCheckedAt: new Date(STORY_NOW),
		attentionProblem: "CREDENTIAL_EXPIRING",
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText("The token expires on 5 July 2026.")).toBeVisible();
		await expect(
			canvas.getByText("Hephaestus could not rotate it. Replace it below to keep sync working."),
		).toBeVisible();
	},
};

export const RefusedGitLabToken: Story = {
	args: { providerLabel: "GitLab", attentionProblem: "CREDENTIAL_REVOKED" },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("GitLab no longer accepts this token")).toBeVisible();
		await expect(canvas.getByRole("button", { name: "Replace token" })).toBeDisabled();
		await userEvent.type(canvas.getByLabelText("New personal access token"), "replacement");
		await expect(canvas.getByRole("button", { name: "Replace token" })).toBeEnabled();
	},
};

export const RefusedGitHubToken: Story = {
	args: { providerLabel: "GitHub", attentionProblem: "CREDENTIAL_REVOKED" },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("GitHub no longer accepts this token")).toBeVisible();
	},
};

export const GitLabNoExpiry: Story = {
	args: { providerLabel: "GitLab", tokenExpiryCheckedAt: new Date(STORY_NOW) },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("The token has no expiry.")).toBeVisible();
	},
};

export const LoadingGitLabExpiry: Story = {
	args: { providerLabel: "GitLab", isLoadingTokenMetadata: true },
};
export const GitLabExpiryError: Story = {
	args: { providerLabel: "GitLab", tokenMetadataError: new Error("Unavailable") },
};
