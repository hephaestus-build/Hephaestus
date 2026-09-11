import type { Meta, StoryObj } from "@storybook/react";
import { fn } from "storybook/test";

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
			username: "octocat",
			displayName: "The Octocat",
			lastLoginAt: new Date("2026-05-20T10:00:00Z"),
		},
	],
	providers: [
		{ registrationId: "github", displayName: "GitHub", providerType: "GITHUB" },
		{ registrationId: "gitlab", displayName: "GitLab", providerType: "GITLAB" },
		{ registrationId: "slack", displayName: "Slack", providerType: "SLACK" },
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
			organization: "AET",
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
			organization: "AET",
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
			organization: "AET",
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
			organization: "AET",
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
