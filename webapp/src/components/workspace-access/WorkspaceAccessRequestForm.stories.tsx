import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn } from "storybook/test";

import { WorkspaceAccessRequestForm } from "./WorkspaceAccessRequestForm";

const meta = {
	component: WorkspaceAccessRequestForm,
	tags: ["autodocs"],
	args: {
		form: {
			policyVersion: 1,
			introductionMarkdown: "## Welcome\nPlease read our code of conduct before requesting access.",
			acknowledgementLabel: "I have read and agree to the code of conduct",
			maintainers: [{ accountId: 1, displayName: "Alex Maintainer" }],
			requestableTeams: [{ id: 2, name: "Engineering" }],
			requiredLinks: [],
			notices: [
				{
					key: "ai",
					title: "AI policy",
					markdown: "Practice reviews use the workspace's configured AI provider.",
				},
			],
			maximumDurationDays: 90,
			verifiedContactAvailable: true,
		},
		pending: false,
		onSubmit: fn(),
	},
} satisfies Meta<typeof WorkspaceAccessRequestForm>;
export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("region", { name: "Before you apply" })).toBeVisible();
		await expect(canvas.getByRole("button", { name: "Request access" })).toBeDisabled();
		await expect(
			canvas.getByRole("checkbox", { name: "I acknowledge AI policy" }),
		).not.toBeChecked();
	},
};
export const Submitting: Story = { args: { pending: true } };
export const Error: Story = {
	args: { error: "The workspace policy changed. Reload the form and review the updated notices." },
};
export const NoAdditionalTeams: Story = {
	args: { form: { ...meta.args.form, requestableTeams: [] } },
};
