import type { Meta, StoryObj } from "@storybook/react";
import { expect, fn, userEvent, waitFor } from "storybook/test";

import { STORY_NOW } from "@/stories/story-clock";

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
		await expect(canvas.getByText("GitLab refuses this token")).toBeVisible();
		await expect(canvas.getByRole("button", { name: "Replace token" })).toBeDisabled();
		await userEvent.type(canvas.getByLabelText("New personal access token"), "replacement");
		await expect(canvas.getByRole("button", { name: "Replace token" })).toBeEnabled();
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
