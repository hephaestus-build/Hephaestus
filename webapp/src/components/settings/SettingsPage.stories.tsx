import type { Meta, StoryObj } from "@storybook/react";
import { expect, fn } from "storybook/test";

import { AuthProvider } from "@/runtime/auth/AuthContext";
import { withStandardPage } from "@/stories/decorators";
import { expectNoPageOverflow } from "@/stories/reflow";
import { storySessions } from "@/stories/sessions-story-mock-data";

import type { AiChoiceSectionProps } from "./AiChoiceSection";
import { SettingsPage } from "./SettingsPage";

const meta = {
	component: SettingsPage,
	args: {
		emailPreferencesProps: {
			researchAvailable: true,
			isAppAdmin: false,
			state: {
				status: "ready",
				preferences: {
					productFeedback: false,
					workspaceAlerts: false,
					surveySummaries: false,
					productSurveys: false,
					researchSurveys: false,
					emailAvailable: true,
					deliveryConfigured: true,
				},
				isPending: false,
				onChange: fn(),
			},
		},
		sessionsProps: {
			state: {
				status: "ready",
				sessions: storySessions,
				revokingJti: null,
				revokingOthers: false,
				onRevoke: fn(),
				onRevokeOthers: fn(),
			},
		},
	},
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
			subject: "583231",
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

const defaultPublicActivityProps = {
	visible: true,
	onVisibleChange: fn(),
};

const defaultAiChoiceProps = {
	choice: "CLOUD",
	onSave: fn(),
} satisfies AiChoiceSectionProps;

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
		aiChoiceProps: defaultAiChoiceProps,
		publicActivityProps: defaultPublicActivityProps,
		linkedAccountsProps: defaultLinkedAccountsProps,
		slackPreferencesProps: defaultSlackPreferencesProps,
		onAccountDeleted: fn(),
		isLoading: false,
	},
};

export const EmailNotConfigured: Story = {
	args: {
		...Default.args,
		emailPreferencesProps: {
			...meta.args.emailPreferencesProps,
			state: {
				...meta.args.emailPreferencesProps.state,
				preferences: {
					...meta.args.emailPreferencesProps.state.preferences,
					deliveryConfigured: false,
				},
			},
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.queryByRole("region", { name: "Email notifications" })).toBeNull();
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
		aiChoiceProps: defaultAiChoiceProps,
		publicActivityProps: defaultPublicActivityProps,
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
		aiChoiceProps: defaultAiChoiceProps,
		publicActivityProps: defaultPublicActivityProps,
		linkedAccountsProps: defaultLinkedAccountsProps,
		slackPreferencesProps: defaultSlackPreferencesProps,
		onAccountDeleted: fn(),
		isLoading: true,
	},
};

export const ResearchHidden: Story = {
	args: {
		emailPreferencesProps: { ...meta.args.emailPreferencesProps, researchAvailable: false },
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
		aiChoiceProps: defaultAiChoiceProps,
		publicActivityProps: defaultPublicActivityProps,
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
