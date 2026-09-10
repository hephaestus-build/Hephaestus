import type { Meta, StoryObj } from "@storybook/react-vite";
import { fn } from "storybook/test";

import { WorkspaceAccessPolicyForm } from "./WorkspaceAccessPolicyForm";

const meta = {
	component: WorkspaceAccessPolicyForm,
	tags: ["autodocs"],
	args: {
		policy: { enabled: false, emailConfigured: true },
		providers: [
			{
				registrationId: "github",
				displayName: "GitHub",
				providerType: "GITHUB",
				baseUrl: "https://github.com",
			},
			{
				registrationId: "tum",
				displayName: "TUM",
				providerType: "OIDC",
				baseUrl: "https://login.example.edu",
			},
		],
		teams: [],
		pending: false,
		onSave: fn(),
	},
} satisfies Meta<typeof WorkspaceAccessPolicyForm>;
export default meta;
type Story = StoryObj<typeof meta>;

export const Empty: Story = {};
export const Configured: Story = {
	args: {
		teams: [
			{
				id: 1,
				name: "Maintainers",
				hidden: false,
				labels: [],
				members: [],
				membershipCount: 0,
				repoPermissionCount: 0,
				repositories: [],
			},
		],
		policy: {
			enabled: true,
			emailConfigured: true,
			version: 1,
			settings: {
				primaryRegistrationId: "github",
				introductionMarkdown: "Welcome! Please follow our code of conduct.",
				acknowledgementLabel: "I have read and agree to the code of conduct",
				maintainerTeamId: 1,
				requestableTeamIds: [],
				requiredLinks: [{ registrationId: "tum" }],
				notices: [],
				maximumDurationDays: 90,
				reminderDays: 14,
				adminMailbox: "admins@example.test",
				personalDataRetentionDays: 30,
			},
		},
	},
};
export const Saving: Story = { args: { ...Configured.args, pending: true } };
export const Error: Story = {
	args: { ...Configured.args, error: "The policy changed; reload before saving." },
};
export const EmailUnavailable: Story = {
	args: { policy: { ...Configured.args?.policy, emailConfigured: false } },
};
