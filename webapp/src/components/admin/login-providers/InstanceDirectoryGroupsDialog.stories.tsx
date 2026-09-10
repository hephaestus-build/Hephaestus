import type { Meta, StoryObj } from "@storybook/react";
import { expect, fn, screen, userEvent } from "storybook/test";

import { minutesBefore } from "@/components/common/story-clock";
import { expectSettledVisible } from "@/test/overlay";
import { InstanceDirectoryGroupsDialog } from "./InstanceDirectoryGroupsDialog";

const provider = {
	registrationId: "organization",
	displayName: "Organization",
	type: "OIDC",
	baseUrl: "https://identity.example.com/realms/engineering",
	redirectUri: "https://app.example.com/api/login/oauth2/code/organization",
	scopes: "openid profile",
	enabled: true,
	directoryGroupIds: ["engineering-team"],
	createdAt: minutesBefore(60),
	updatedAt: minutesBefore(5),
};
const meta = {
	component: InstanceDirectoryGroupsDialog,
	parameters: { layout: "centered" },
	tags: ["autodocs"],
	args: { provider, isSaving: false, onClose: fn(), onSave: fn() },
} satisfies Meta<typeof InstanceDirectoryGroupsDialog>;
export default meta;
type Story = StoryObj<typeof meta>;

export const ApprovedGroups: Story = {
	play: async () => {
		await expectSettledVisible(
			await screen.findByRole("dialog", { name: "Approve directory groups" }),
		);
		await expect(screen.getByRole("textbox", { name: "Approved group IDs" })).toHaveValue(
			"engineering-team",
		);
	},
};
export const Empty: Story = { args: { provider: { ...provider, directoryGroupIds: [] } } };
export const Saving: Story = { args: { isSaving: true } };
export const Closed: Story = { args: { provider: null } };
export const InvalidGroup: Story = {
	play: async () => {
		const input = await screen.findByRole("textbox", { name: "Approved group IDs" });
		await expectSettledVisible(input);
		await userEvent.clear(input);
		await userEvent.type(input, "../wrong-realm");
		await expect(input).toHaveAttribute("aria-invalid", "true");
		await expect(screen.getByRole("button", { name: "Approve groups" })).toBeDisabled();
	},
};
export const RemoveApproval: Story = {
	play: async () => {
		const input = await screen.findByRole("textbox", { name: "Approved group IDs" });
		await expectSettledVisible(input);
		await userEvent.clear(input);
		await expect(screen.getByRole("button", { name: "Remove directory approval" })).toBeEnabled();
	},
};
