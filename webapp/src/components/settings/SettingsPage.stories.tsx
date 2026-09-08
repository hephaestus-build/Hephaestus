import type { Meta, StoryObj } from "@storybook/react";
import { expect, fn } from "storybook/test";

import { AuthProvider } from "@/integrations/auth/AuthContext";
import { withStandardPage } from "@/stories/decorators";
import { expectNoPageOverflow } from "@/test/reflow";

import { SettingsPage } from "./SettingsPage";

const meta = {
	component: SettingsPage,
	parameters: {
		layout: "fullscreen",
	},
	decorators: [
		withStandardPage,
		(Story) => (
			<AuthProvider>
				<Story />
			</AuthProvider>
		),
	],
	tags: ["autodocs"],
} satisfies Meta<typeof SettingsPage>;

export default meta;
type Story = StoryObj<typeof meta>;

const defaultLinkedAccountsProps = {
	identities: [
		{
			id: 1,
			providerType: "GITHUB",
			serverUrl: "https://github.com",
			username: "octocat",
			displayName: "The Octocat",
			lastLoginAt: new Date("2026-05-20T10:00:00Z"),
		},
	],
	providers: [
		{
			registrationId: "github",
			displayName: "GitHub",
			providerType: "GITHUB",
			baseUrl: "https://github.com",
		},
		{
			registrationId: "gitlab",
			displayName: "GitLab",
			providerType: "GITLAB",
			baseUrl: "https://gitlab.com",
		},
		{
			registrationId: "slack",
			displayName: "Slack",
			providerType: "SLACK",
			baseUrl: "https://slack.com",
		},
	],
	onLink: fn(),
	onUnlink: fn(),
};

const defaultSlackPreferencesProps = {
	workspaces: [
		{
			workspaceSlug: "hephaestustest",
			workspaceName: "Hephaestus Test",
			slackTeamId: "T1",
			slackTeamName: "hephaestus-test",
			slackUserId: "U1",
			slackDisplayName: "Felix",
			channelMessagesAllowed: true,
			activeMonitoredChannelCount: 2,
		},
	],
	isSlackLinked: true,
	canConnectSlack: true,
	onConnectSlack: fn(),
	onToggleChannelMessages: fn(),
};

export const Default: Story = {
	args: {
		practiceFeedbackProps: {
			practiceFeedbackDeliveryEnabled: true,
			onTogglePracticeFeedback: fn(),
		},
		showResearchSection: true,
		researchProps: {
			participateInResearch: true,
			onToggleResearch: fn(),
		},
		linkedAccountsProps: defaultLinkedAccountsProps,
		slackPreferencesProps: defaultSlackPreferencesProps,
		onAccountDeleted: fn(),
		isLoading: false,
	},
};

export const AllTogglesDisabled: Story = {
	args: {
		practiceFeedbackProps: {
			practiceFeedbackDeliveryEnabled: false,
			onTogglePracticeFeedback: fn(),
		},
		showResearchSection: true,
		researchProps: {
			participateInResearch: false,
			onToggleResearch: fn(),
		},
		linkedAccountsProps: defaultLinkedAccountsProps,
		slackPreferencesProps: defaultSlackPreferencesProps,
		onAccountDeleted: fn(),
		isLoading: false,
	},
};

export const Loading: Story = {
	args: {
		practiceFeedbackProps: {
			practiceFeedbackDeliveryEnabled: true,
			onTogglePracticeFeedback: fn(),
		},
		showResearchSection: true,
		researchProps: {
			participateInResearch: true,
			onToggleResearch: fn(),
		},
		linkedAccountsProps: defaultLinkedAccountsProps,
		slackPreferencesProps: defaultSlackPreferencesProps,
		onAccountDeleted: fn(),
		isLoading: true,
	},
};

export const ResearchHidden: Story = {
	args: {
		practiceFeedbackProps: {
			practiceFeedbackDeliveryEnabled: true,
			onTogglePracticeFeedback: fn(),
		},
		showResearchSection: false,
		researchProps: {
			participateInResearch: true,
			onToggleResearch: fn(),
		},
		linkedAccountsProps: defaultLinkedAccountsProps,
		slackPreferencesProps: defaultSlackPreferencesProps,
		onAccountDeleted: fn(),
		isLoading: false,
	},
};

export const MobileReflow: Story = {
	...Default,
	parameters: {
		viewport: { defaultViewport: "reflow" },
		chromatic: { viewports: [320] },
	},
	play: expectNoPageOverflow,
};

export const OrganizationOnly: Story = {
	...Default,
	args: {
		...Default.args,
		needsScmIdentity: true,
		linkedAccountsProps: {
			...defaultLinkedAccountsProps,
			identities: [
				{
					id: 4,
					providerType: "OIDC",
					serverUrl: "https://identity.example.com/realms/team",
					displayName: "Team member",
				},
			],
			providers: [
				...defaultLinkedAccountsProps.providers,
				{
					registrationId: "team",
					providerType: "OIDC",
					baseUrl: "https://identity.example.com/realms/team",
					displayName: "Team account",
				},
			],
		},
	},
	play: async ({ canvas }) => {
		canvas.getByText(/Connect a GitHub or GitLab account below/);
		await expect(
			canvas.getByRole("switch", { name: "Participate in academic research" }),
		).toBeEnabled();
		canvas.getByRole("button", { name: "Connect GitHub" });
	},
};

export const PreferencesError: Story = {
	...Default,
	args: { ...Default.args, settingsError: true, onRetrySettings: fn() },
	play: async ({ canvas }) => {
		canvas.getByText("We couldn't load your practice feedback preferences.");
		canvas.getByRole("button", { name: "Retry" });
		await expect(
			canvas.getByRole("switch", { name: "Participate in academic research" }),
		).toBeEnabled();
		canvas.getByText("The Octocat");
	},
};
