import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn } from "storybook/test";
import { WorkspacePasskeyPolicySection } from "./WorkspacePasskeyPolicySection";

const meta = {
	component: WorkspacePasskeyPolicySection,
	tags: ["autodocs"],
	args: { owner: true, required: false, onRetry: fn(), onChange: fn() },
} satisfies Meta<typeof WorkspacePasskeyPolicySection>;
export default meta;
type Story = StoryObj<typeof meta>;
export const Optional: Story = {};
export const Required: Story = { args: { required: true } };
export const Empty: Story = { args: { required: undefined } };
export const Loading: Story = { args: { loading: true } };
export const Error: Story = {
	args: { required: undefined, error: "We could not load the passkey policy." },
};
export const Pending: Story = { args: { pending: true } };
export const InstanceRequired: Story = {
	args: { instanceRequired: true },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("switch", { name: "Require admin passkeys" })).toBeChecked();
		await expect(canvas.getByRole("switch", { name: "Require admin passkeys" })).toBeDisabled();
	},
};
export const Admin: Story = { args: { owner: false } };
