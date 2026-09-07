import type { Meta, StoryObj } from "@storybook/react";
import { expect, fn, userEvent, waitFor } from "storybook/test";

import { STORY_NOW } from "@/components/common/story-clock";

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
		onSave: fn().mockResolvedValue(true),
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
		await expect(canvas.getByRole("button", { name: "Saving token…" })).toBeDisabled();
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
		canvas.getByText("The stored token can't be read");
		await expect(canvas.getByLabelText("New personal access token")).toBeEnabled();
	},
};

export const SuccessfulReplacement: Story = {
	play: async ({ canvas }) => {
		const input = canvas.getByLabelText("New personal access token");
		await userEvent.type(input, "replacement-token");
		await userEvent.click(canvas.getByRole("button", { name: "Replace token" }));
		await waitFor(() => expect(input).toHaveValue(""));
	},
};
