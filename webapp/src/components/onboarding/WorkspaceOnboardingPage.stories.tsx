import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, mocked, waitFor } from "storybook/test";

import type { WorkspaceOnboarding } from "@/api/types.gen";
import { Stateful } from "@/stories/stateful";
import { expectGenuinelyDisabled, expectUnavailable } from "@/test/controls";
import { expectNoPageOverflow } from "@/test/reflow";
import {
	WorkspaceOnboardingPage,
	type WorkspaceOnboardingPageProps,
} from "./WorkspaceOnboardingPage";

const welcome = {
	workspaceName: "Engineering",
	enabled: true,
	aiChoiceRequired: true,
	needsWelcome: true,
	completed: false,
	revision: 2,
	welcomeMarkdown:
		"We learn from the work we already do. Start with your team's practices, then explore the feedback on your own practice page.\n\nQuestions? Ask a workspace owner — you can change your preferences at any time.",
	aiOptions: [
		{ choice: "ON_PREMISES", practiceReviewsReady: true, mentorReady: true },
		{ choice: "PRIVATE_CLOUD", practiceReviewsReady: true, mentorReady: true },
	],
	links: [
		{
			connectionId: 1,
			displayName: "Slack",
			providerType: "SLACK",
			registrationId: "slack",
			teamName: "Engineering team",
			required: true,
			available: true,
			linked: false,
		},
		{
			connectionId: 2,
			displayName: "Outline",
			providerType: "OUTLINE",
			registrationId: "outline",
			required: false,
			available: true,
			linked: false,
		},
	],
} satisfies WorkspaceOnboarding;

/**
 * Account notice acknowledgement, research participation, and workspace AI preferences are separate
 * decisions. Saving a preference never connects an account or enrols someone in research.
 */
const meta = {
	title: "Onboarding/Workspace setup",
	component: WorkspaceOnboardingPage,
	tags: ["autodocs"],
	parameters: { layout: "fullscreen" },
	args: {
		state: { status: "ready", data: welcome },
		onChoose: fn(async () => true),
		onLink: fn(),
		onRefresh: fn(),
		onComplete: fn(),
		onDismiss: fn(),
	},
	render: (args) => <ConfirmedSetup {...args} />,
} satisfies Meta<typeof WorkspaceOnboardingPage>;
export default meta;
type Story = StoryObj<typeof meta>;

function ConfirmedSetup(args: WorkspaceOnboardingPageProps) {
	const state = args.state;
	if (state.status !== "ready") return <WorkspaceOnboardingPage {...args} />;
	return (
		<Stateful initial={{ data: state.data, saveError: args.saveError, pending: args.pending }}>
			{({ data, saveError, pending }, setState) => (
				<WorkspaceOnboardingPage
					{...args}
					state={{ ...state, data }}
					saveError={saveError}
					pending={pending}
					onChoose={async (choice) => {
						setState({ data, saveError: undefined, pending: "choice" });
						const saved = await args.onChoose(choice);
						setState({
							data: saved ? { ...data, aiChoice: choice } : data,
							pending: undefined,
							saveError: saved
								? undefined
								: "Your preference could not be saved. Please try again; your saved preference has not changed.",
						});
						return saved;
					}}
					onRefresh={() => {
						args.onRefresh();
						setState({
							data: { ...data, links: data.links.map((link) => ({ ...link, linked: true })) },
							saveError,
							pending,
						});
					}}
				/>
			)}
		</Stateful>
	);
}

export const Default: Story = {};

export const FirstVisit: Story = {
	play: async ({ canvas, userEvent, args }) => {
		await expect(canvas.getByRole("radio", { name: "On-premises" })).not.toBeChecked();
		await expect(canvas.getByRole("radio", { name: "Private cloud" })).not.toBeChecked();
		await expect(canvas.getByRole("radio", { name: "No AI" })).not.toBeChecked();
		await expectGenuinelyDisabled(canvas.getByRole("button", { name: "Save AI preference" }));
		canvas.getByRole("radio", { name: "On-premises" }).focus();
		await userEvent.keyboard("{ArrowRight}{ArrowRight}");
		await expect(canvas.getByRole("radio", { name: "No AI" })).toBeChecked();
		await expect(args.onChoose).not.toHaveBeenCalled();
		await userEvent.click(canvas.getByRole("button", { name: "Save AI preference" }));
		await expect(args.onChoose).toHaveBeenCalledWith("NO_AI");
		await waitFor(() =>
			expect(
				canvas.getByRole("heading", { name: "Connect your workspace accounts" }),
			).toHaveFocus(),
		);
		await expectGenuinelyDisabled(canvas.getByRole("button", { name: "Continue to workspace" }));
		await userEvent.click(canvas.getByRole("button", { name: "Connect Slack" }));
		await expect(args.onLink).toHaveBeenCalledWith("slack");
		await userEvent.click(canvas.getByRole("button", { name: "Refresh connections" }));
		await expect(args.onRefresh).toHaveBeenCalledOnce();
		await userEvent.click(canvas.getByRole("button", { name: "Continue to workspace" }));
		await expect(args.onComplete).toHaveBeenCalledOnce();
	},
};

export const NoAdditionalAccounts: Story = {
	args: {
		state: { status: "ready", data: { ...welcome, links: [], welcomeMarkdown: "", aiOptions: [] } },
	},
	play: async ({ canvas, userEvent, args }) => {
		await expectUnavailable(canvas.getByRole("radio", { name: "On-premises" }));
		await expect(canvas.getByRole("radio", { name: "On-premises" })).toHaveAccessibleDescription(
			/not available/i,
		);
		await userEvent.click(canvas.getByRole("radio", { name: "No AI" }));
		await userEvent.click(canvas.getByRole("button", { name: "Save AI preference" }));
		await waitFor(() =>
			expect(canvas.getByRole("heading", { name: "You're ready" })).toHaveFocus(),
		);
		await userEvent.click(canvas.getByRole("button", { name: "Continue to workspace" }));
		await expect(args.onComplete).toHaveBeenCalledOnce();
	},
};

export const OptionalAccounts: Story = {
	args: {
		state: {
			status: "ready",
			data: { ...welcome, links: welcome.links.map((link) => ({ ...link, required: false })) },
		},
	},
	play: async ({ canvas, userEvent }) => {
		await userEvent.click(canvas.getByRole("radio", { name: "Private cloud" }));
		await userEvent.click(canvas.getByRole("button", { name: "Save AI preference" }));
		await expect(
			await canvas.findByRole("button", { name: "Continue to workspace" }),
		).toBeEnabled();
	},
};

export const ChangeSavedPreference: Story = {
	args: {
		state: {
			status: "ready",
			data: { ...welcome, aiChoice: "NO_AI", completed: true, needsWelcome: false },
		},
	},
	play: async ({ canvas, userEvent, args }) => {
		await expect(canvas.getByRole("radio", { name: "No AI" })).toBeChecked();
		await userEvent.click(canvas.getByRole("radio", { name: "Private cloud" }));
		await userEvent.click(canvas.getByRole("button", { name: "Save AI preference" }));
		await expect(args.onChoose).toHaveBeenCalledWith("PRIVATE_CLOUD");
		await userEvent.click(await canvas.findByRole("button", { name: "Back" }));
		await expect(canvas.getByRole("radio", { name: "Private cloud" })).toBeChecked();
		await userEvent.click(canvas.getByRole("radio", { name: "No AI" }));
		await userEvent.click(canvas.getByRole("button", { name: "Save AI preference" }));
		await expect(args.onChoose).toHaveBeenLastCalledWith("NO_AI");
	},
};

export const SavedLocationUnavailable: Story = {
	args: {
		state: {
			status: "ready",
			data: { ...welcome, aiChoice: "ON_PREMISES", aiOptions: [], links: [] },
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("radio", { name: "On-premises" })).toBeChecked();
		await expectUnavailable(canvas.getByRole("radio", { name: "Private cloud" }));
		await expect(canvas.getByRole("radio", { name: "No AI" })).toBeEnabled();
	},
};

export const RequiredAccountUnavailable: Story = {
	args: {
		state: {
			status: "ready",
			data: { ...welcome, links: welcome.links.map((link) => ({ ...link, available: false })) },
		},
	},
	play: async ({ canvas, userEvent, args }) => {
		await userEvent.click(canvas.getByRole("radio", { name: "No AI" }));
		await userEvent.click(canvas.getByRole("button", { name: "Save AI preference" }));
		await expectGenuinelyDisabled(await canvas.findByRole("button", { name: "Connect Slack" }));
		await expectGenuinelyDisabled(canvas.getByRole("button", { name: "Continue to workspace" }));
		await userEvent.click(canvas.getByRole("button", { name: "Not now" }));
		await expect(args.onDismiss).toHaveBeenCalledOnce();
	},
};

export const Loading: Story = { args: { state: { status: "loading" } } };
export const LoadFailed: Story = {
	args: { state: { status: "error", error: new Error("Unavailable"), onRetry: fn() } },
	play: async ({ canvas, userEvent, args }) => {
		await userEvent.click(canvas.getByRole("button", { name: /try again|retry/i }));
		if (args.state.status !== "error") throw new Error("Expected the retryable error state");
		await expect(args.state.onRetry).toHaveBeenCalledOnce();
	},
};

export const SavingPreference: Story = {
	args: { onChoose: fn(() => new Promise<boolean>(() => {})) },
	play: async ({ canvas, userEvent, args }) => {
		await userEvent.click(canvas.getByRole("radio", { name: "No AI" }));
		await userEvent.click(canvas.getByRole("button", { name: "Save AI preference" }));
		await expect(args.onChoose).toHaveBeenCalledWith("NO_AI");
		await expectGenuinelyDisabled(canvas.getByRole("button", { name: "Saving preference…" }));
		await expectUnavailable(canvas.getByRole("radio", { name: "Private cloud" }));
	},
};

export const SaveFailedAndRetried: Story = {
	args: {
		state: { status: "ready", data: { ...welcome, aiChoice: "ON_PREMISES" } },
		onChoose: fn<WorkspaceOnboardingPageProps["onChoose"]>(),
	},
	beforeEach: ({ args }) => {
		mocked(args.onChoose).mockReset().mockResolvedValueOnce(false).mockResolvedValue(true);
	},
	play: async ({ canvas, userEvent, args }) => {
		await userEvent.click(canvas.getByRole("radio", { name: "Private cloud" }));
		await userEvent.click(canvas.getByRole("button", { name: "Save AI preference" }));
		await expect(args.onChoose).toHaveBeenCalledWith("PRIVATE_CLOUD");
		await expect(canvas.getByRole("radio", { name: "Private cloud" })).toBeChecked();
		await expect(await canvas.findByRole("alert")).toHaveTextContent(
			"your saved preference has not changed.",
		);
		await expect(canvas.queryByRole("button", { name: "Continue to workspace" })).toBeNull();
		await userEvent.click(canvas.getByRole("button", { name: "Save AI preference" }));
		await expect(args.onChoose).toHaveBeenCalledTimes(2);
		await waitFor(() =>
			expect(
				canvas.getByRole("heading", { name: "Connect your workspace accounts" }),
			).toHaveFocus(),
		);
		await expect(canvas.queryByRole("alert")).toBeNull();
		await userEvent.click(canvas.getByRole("button", { name: "Back" }));
		await expect(canvas.getByRole("radio", { name: "Private cloud" })).toBeChecked();
	},
};

export const ExistingWorkspaceDefaults: Story = {
	args: {
		state: {
			status: "ready",
			data: { ...welcome, enabled: false, aiChoiceRequired: false, needsWelcome: false, links: [] },
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText(/Until you save a preference/)).toHaveTextContent(
			"existing AI defaults",
		);
	},
};

export const NarrowViewport: Story = {
	args: {
		state: {
			status: "ready",
			data: {
				...welcome,
				workspaceName: "International engineering and research collaboration",
				welcomeMarkdown:
					"## Start here\n\nRead your team’s practices and ask questions in Slack.\n\n```text\nhttps://engineering.example.com/teams/international-collaboration/onboarding/first-week/checklist-with-a-long-unbroken-identifier\n```",
			},
		},
	},
	globals: { viewport: { value: "reflow", isRotated: false } },
	parameters: { chromatic: { viewports: [320] } },
	play: async ({ canvas, userEvent }) => {
		await expect(window.innerWidth).toBe(320);
		await userEvent.click(canvas.getByText("A welcome from your team"));
		await expectNoPageOverflow();
		await userEvent.click(canvas.getByRole("radio", { name: "No AI" }));
		await userEvent.click(canvas.getByRole("button", { name: "Save AI preference" }));
		await canvas.findByRole("heading", { name: "Connect your workspace accounts" });
		await expectNoPageOverflow();
	},
};
export const Dark: Story = {
	globals: { theme: "dark" },
	args: { state: { status: "ready", data: { ...welcome, aiChoice: "NO_AI" } } },
};

export const Finishing: Story = {
	args: {
		initialStep: "accounts",
		pending: "completion",
		state: { status: "ready", data: { ...welcome, aiChoice: "NO_AI", links: [] } },
	},
	play: async ({ canvas }) => {
		await expectGenuinelyDisabled(canvas.getByRole("button", { name: "Finishing…" }));
		await expectGenuinelyDisabled(canvas.getByRole("button", { name: "Not now" }));
	},
};
export const ContinuingLater: Story = {
	args: {
		initialStep: "accounts",
		pending: "dismissal",
		state: { status: "ready", data: { ...welcome, aiChoice: "NO_AI" } },
	},
	play: async ({ canvas }) => {
		await expectGenuinelyDisabled(canvas.getByRole("button", { name: "Continuing…" }));
		await expectGenuinelyDisabled(canvas.getByRole("button", { name: "Back" }));
	},
};
export const ReturnWithRequiredAccount: Story = {
	args: {
		state: {
			status: "ready",
			data: { ...welcome, aiChoice: "NO_AI", completed: false, needsWelcome: false, revision: 3 },
		},
	},
	play: async ({ canvas, userEvent, args }) => {
		await expect(canvas.getByRole("radio", { name: "No AI" })).toBeChecked();
		await userEvent.click(canvas.getByRole("button", { name: "Continue" }));
		await expect(args.onChoose).not.toHaveBeenCalled();
		await expect(await canvas.findByRole("button", { name: "Connect Slack" })).toBeEnabled();
	},
};
export const HephOnly: Story = {
	args: {
		state: {
			status: "ready",
			data: {
				...welcome,
				aiOptions: [{ choice: "ON_PREMISES", mentorReady: true, practiceReviewsReady: false }],
			},
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("radio", { name: "On-premises" })).toHaveAccessibleDescription(
			/Heph/,
		);
		await expectUnavailable(canvas.getByRole("radio", { name: "Private cloud" }));
	},
};

export const RefreshingConnections: Story = {
	args: {
		initialStep: "accounts",
		state: {
			status: "ready",
			data: { ...welcome, aiChoice: "NO_AI" },
			refresh: { status: "pending" },
		},
	},
	play: async ({ canvas }) => {
		await expectGenuinelyDisabled(canvas.getByRole("button", { name: "Refreshing connections…" }));
		await expect(canvas.getByRole("button", { name: "Not now" })).toBeEnabled();
	},
};

export const RefreshFailed: Story = {
	args: {
		initialStep: "accounts",
		state: {
			status: "ready",
			data: { ...welcome, aiChoice: "NO_AI" },
			refresh: { status: "error", error: new Error("Unavailable"), onRetry: fn() },
		},
	},
	play: async ({ canvas, userEvent, args }) => {
		await userEvent.click(canvas.getByRole("button", { name: /try again|retry/i }));
		if (args.state.status !== "ready" || args.state.refresh?.status !== "error")
			throw new Error("Expected a retryable refresh error");
		await expect(args.state.refresh.onRetry).toHaveBeenCalledOnce();
		await expect(canvas.getByRole("button", { name: "Connect Slack" })).toBeEnabled();
	},
};

export const LoadAndDismissalFailed: Story = {
	args: {
		state: { status: "error", error: new Error("Unavailable"), onRetry: fn() },
		saveError: "We couldn't save your decision to continue. Please try again.",
	},
};
