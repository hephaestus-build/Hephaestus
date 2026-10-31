import type { Meta, StoryObj } from "@storybook/react";
import { expect, fn, within } from "storybook/test";

import { TIME_ZONES } from "@/lib/dates";
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
			isAppAdmin: false,
			state: {
				status: "ready",
				preferences: {
					productFeedback: false,
					productFeedbackUpdates: true,
					workspaceAlerts: false,
					emailAvailable: true,
					deliveryConfigured: true,
					weeklyDigest: false,
					weeklyDigestAvailable: true,
				},
				isPending: false,
				onChange: fn(),
			},
		},
		weeklyDigestProps: {
			state: {
				status: "ready",
				enabled: true,
				available: true,
				timeZone: "Europe/Berlin",
				zones: TIME_ZONES,
				isPending: false,
				onToggle: fn(),
				onChangeTimeZone: fn(),
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
	linkedProviders: [{ type: "GITHUB", serverUrl: "https://api.github.com" }],
	onLink: fn(),
	onUnlink: fn(),
};

const defaultPublicActivityProps = {
	state: { status: "ready", visible: true, pending: false, onVisibleChange: fn() },
} as const;

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
			slackDisplayName: "Jordan",
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
	play: async ({ canvas }) => {
		// Each link leads to a group that is on the page; no passkeys are passed here, so none is listed.
		const nav = within(canvas.getByRole("navigation", { name: "Settings sections" }));
		const links = nav.getAllByRole("link");
		await expect(links.map((link) => link.textContent)).toStrictEqual([
			"Connected accounts",
			"Preferences",
			"Research participation",
			"Danger zone",
		]);
		for (const link of links) {
			const target = link.getAttribute("href")?.slice(1) ?? "";
			await expect(document.getElementById(target)).not.toBeNull();
		}
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
	play: async ({ canvas }) => {
		// Only the setting the preferences read holds waits on it: every other section has its read.
		await expect(canvas.getByRole("switch", { name: "Comments on your work" })).toHaveAttribute(
			"aria-disabled",
			"true",
		);
		await expect(
			canvas.getByRole("switch", { name: "Allow research use of my data" }),
		).not.toHaveAttribute("aria-disabled", "true");
		await expect(canvas.getByText("The Octocat")).toBeVisible();
		await expect(canvas.queryByRole("list", { name: "Loading connected accounts…" })).toBeNull();
		const aiChoice = within(canvas.getByRole("region", { name: "Your AI choice" }));
		await expect(aiChoice.queryByText("Loading…")).toBeNull();
	},
};

/**
 * The preferences read failed. Only the setting it holds gives way to Retry: the research choice is
 * read on its own, so it can still be withdrawn.
 */
export const PreferencesUnavailable: Story = {
	args: {
		...Default.args,
		settingsError: true,
		onRetrySettings: fn(),
	},
	play: async ({ args, canvas, userEvent }) => {
		await expect(
			canvas.getByText(
				"We could not load your preferences, so your practice feedback setting is not shown.",
			),
		).toBeVisible();
		await expect(canvas.queryByRole("switch", { name: "Comments on your work" })).toBeNull();
		await userEvent.click(canvas.getByRole("switch", { name: "Allow research use of my data" }));
		await expect(args.researchProps.onToggleResearch).toHaveBeenCalledWith(
			false,
			expect.anything(),
		);
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
		aiChoiceProps: defaultAiChoiceProps,
		publicActivityProps: defaultPublicActivityProps,
		linkedAccountsProps: defaultLinkedAccountsProps,
		slackPreferencesProps: defaultSlackPreferencesProps,
		onAccountDeleted: fn(),
		isLoading: false,
	},
	play: async ({ canvas }) => {
		// No research on this instance: neither the section nor a link to it.
		const nav = within(canvas.getByRole("navigation", { name: "Settings sections" }));
		await expect(nav.queryByRole("link", { name: "Research participation" })).toBeNull();
		await expect(canvas.queryByRole("region", { name: "Research participation" })).toBeNull();
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
