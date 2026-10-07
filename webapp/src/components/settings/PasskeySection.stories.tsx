import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn } from "storybook/test";
import { PasskeySection } from "./PasskeySection";

const status = {
	protectionEnabled: false,
	recoveryRequired: false,
	instanceAdminRequired: true,
	workspaceAdminRequired: false,
	verified: false,
	credentials: [],
};
const meta = {
	component: PasskeySection,
	tags: ["autodocs"],
	args: {
		status,
		supported: true,
		onRetry: fn(),
		onRegister: fn(),
		onVerify: fn(),
		onProtection: fn(),
		onRemove: fn(),
		onCreateRecoveryCodes: fn(),
		onRecover: fn(),
	},
} satisfies Meta<typeof PasskeySection>;
export default meta;
type Story = StoryObj<typeof meta>;
export const Empty: Story = {
	play: async ({ canvas }) => {
		await expect(
			canvas.getByText("No passkeys yet. Register a passkey to protect admin access."),
		).toBeVisible();
	},
};
export const Loading: Story = { args: { loading: true } };
export const Error: Story = {
	args: { status: undefined, error: "We could not load your passkeys. Try again." },
};
export const Recovery: Story = { args: { status: { ...status, recoveryRequired: true } } };
export const Enrolled: Story = {
	args: {
		status: { ...status, credentials: [{ id: "test-credential", label: "Personal device" }] },
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("button", { name: "Remove Personal device" })).toBeDisabled();
	},
};
export const Verified: Story = {
	args: {
		status: {
			...status,
			verified: true,
			protectionEnabled: true,
			credentials: [{ id: "test-credential", label: "Personal device" }],
		},
	},
};
export const Pending: Story = { args: { pending: true, pendingAction: "register" } };
export const Unsupported: Story = { args: { supported: false } };

export const LongCredentialName: Story = {
	args: {
		status: {
			protectionEnabled: true,
			recoveryRequired: false,
			instanceAdminRequired: true,
			workspaceAdminRequired: false,
			verified: true,
			credentials: [
				{
					id: "long",
					label:
						"Personal workstation with a hardware security key and a long device name for admin access",
				},
			],
		},
	},
};
