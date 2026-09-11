import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, waitFor } from "storybook/test";

import type { WorkspaceOnboarding, WorkspaceOnboardingLink } from "@/api/types.gen";
import { Stateful } from "@/stories/stateful";
import { expectGenuinelyDisabled, expectUnavailable } from "@/test/controls";
import { expectNoPageOverflow } from "@/test/reflow";

import {
	type OnboardingSubmission,
	WorkspaceOnboardingPage,
	type WorkspaceOnboardingPageProps,
} from "./WorkspaceOnboardingPage";

const slack = {
	connectionId: 1,
	displayName: "Slack",
	providerType: "SLACK",
	registrationId: "slack",
	teamName: "Engineering team",
	required: true,
	available: true,
	linked: false,
} satisfies WorkspaceOnboardingLink;
const outline = {
	connectionId: 2,
	displayName: "Outline",
	providerType: "OUTLINE",
	registrationId: "outline",
	required: false,
	available: true,
	linked: false,
} satisfies WorkspaceOnboardingLink;

const welcome = {
	workspaceName: "Engineering",
	enabled: true,
	aiChoiceRequired: true,
	needsWelcome: true,
	completed: false,
	revision: 2,
	welcomeMarkdown:
		"We learn from the work we already do. Start with your team's practices, then explore the feedback on your own practice page.\n\nQuestions? Ask a workspace owner — you can change your AI choice at any time.",
	aiOptions: [
		{ choice: "ON_PREMISES", practiceReviewsReady: true, mentorReady: true },
		{ choice: "PRIVATE_CLOUD", practiceReviewsReady: true, mentorReady: true },
	],
	links: [slack, outline],
} satisfies WorkspaceOnboarding;

const ready = {
	status: "ready",
	data: welcome,
	submission: { status: "idle" },
	onSubmit: fn(),
	onLink: fn(),
	onLeave: fn(),
} satisfies WorkspaceOnboardingPageProps["state"];

/**
 * The three answers are deliberately identical in shape — a title and one sentence, no icon on any
 * of them, and nothing that argues for one over another. Everything a reader needs in order to
 * decide sits in the fact list above the group, addressed to every answer at once, because an answer
 * carrying more reasons than its neighbours is the asymmetry EDPB 03/2022 calls deceptive.
 *
 * There is no step count. This screen follows the consent page and may be revisited from the
 * sidebar, so "step 2 of n" would be a claim about a flow it cannot see; the marker beside each
 * section says only whether that section is answered, which it can know.
 */
const meta = {
	title: "Onboarding/Workspace setup",
	component: WorkspaceOnboardingPage,
	tags: ["autodocs"],
	parameters: { layout: "fullscreen" },
	args: { state: ready },
	render: (args) => <Harness {...args} />,
} satisfies Meta<typeof WorkspaceOnboardingPage>;
export default meta;
type Story = StoryObj<typeof meta>;

/**
 * Renders the page over its own `submission` and saved choice. A submit records the choice as
 * saved, or moves to `afterSubmit` when a story wants the write to hang or fail, so "Saving…" and
 * the error alert are reached through a click rather than handed in as a frozen prop that would
 * leave nothing to disable. Only those two fields are patched; every callback still reaches the
 * spy in `args`.
 */
function Harness({
	afterSubmit,
	afterLeave,
	...args
}: WorkspaceOnboardingPageProps & {
	afterSubmit?: OnboardingSubmission;
	afterLeave?: OnboardingSubmission;
}) {
	const { state } = args;
	if (state.status !== "ready") return <WorkspaceOnboardingPage {...args} />;
	return (
		<Stateful initial={{ submission: state.submission, aiChoice: state.data.aiChoice }}>
			{({ submission, aiChoice }, setValue) => (
				<WorkspaceOnboardingPage
					{...args}
					state={{
						...state,
						data: { ...state.data, aiChoice },
						submission,
						onSubmit: (choice) => {
							state.onSubmit(choice);
							setValue(
								afterSubmit
									? { submission: afterSubmit, aiChoice }
									: { submission: { status: "idle" }, aiChoice: choice },
							);
						},
						onLeave: () => {
							state.onLeave();
							if (afterLeave) setValue({ submission: afterLeave, aiChoice });
						},
					}}
				/>
			)}
		</Stateful>
	);
}

function readyArgs(args: WorkspaceOnboardingPageProps) {
	if (args.state.status !== "ready") throw new Error("Expected the ready state");
	return args.state;
}

export const Default: Story = {};

export const FirstVisit: Story = {
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("radio", { name: "On-premises" })).not.toBeChecked();
		await expect(canvas.getByRole("radio", { name: "Private cloud" })).not.toBeChecked();
		await expect(canvas.getByRole("radio", { name: "No AI" })).not.toBeChecked();
		const submit = canvas.getByRole("button", { name: "Continue" });
		await expectGenuinelyDisabled(submit);
		await expect(submit).toHaveAccessibleDescription(
			/Choose how you'd like to use AI to continue\. Not now leaves/,
		);
		await expect(canvas.getByText(/We learn from/)).toBeVisible();
		await expect(canvas.getByRole("heading", { name: /Connect your accounts/ })).toBeVisible();
	},
};

export const AnswerAndContinue: Story = {
	args: { state: { ...ready, data: { ...welcome, links: [] } } },
	play: async ({ canvas, userEvent, args }) => {
		await userEvent.click(canvas.getByRole("radio", { name: "On-premises" }));
		await expect(canvas.getByRole("radio", { name: "On-premises" })).toBeChecked();
		await expect(canvas.getByText("Noted. Press Continue and I'll remember that.")).toBeVisible();
		await expect(canvas.getByRole("button", { name: "Continue" })).toBeEnabled();
		await userEvent.keyboard("{ArrowRight}");
		await expect(canvas.getByRole("radio", { name: "Private cloud" })).toBeChecked();
		await userEvent.click(canvas.getByRole("button", { name: "Continue" }));
		await expect(readyArgs(args).onSubmit).toHaveBeenCalledWith("PRIVATE_CLOUD");
	},
};

export const NoAi: Story = {
	args: { state: { ...ready, data: { ...welcome, links: [] } } },
	play: async ({ canvas, userEvent, args }) => {
		await expect(canvas.getByRole("radio", { name: "No AI" })).toHaveAccessibleDescription(
			"No AI runs for you in this workspace.",
		);
		await expect(canvas.getByRole("radio", { name: "On-premises" })).toHaveAccessibleDescription(
			"AI runs on infrastructure your organisation manages.",
		);
		await userEvent.click(canvas.getByRole("radio", { name: "No AI" }));
		await userEvent.click(canvas.getByRole("button", { name: "Continue" }));
		await expect(readyArgs(args).onSubmit).toHaveBeenCalledWith("NO_AI");
	},
};

export const KeyboardOnly: Story = {
	args: { state: { ...ready, data: { ...welcome, links: [] } } },
	play: async ({ canvas, userEvent, args }) => {
		canvas.getByRole("button", { name: "From your team" }).focus();
		await userEvent.tab();
		await expect(canvas.getByRole("radio", { name: "On-premises" })).toHaveFocus();
		await userEvent.keyboard("{ArrowRight}{ArrowRight}");
		await expect(canvas.getByRole("radio", { name: "No AI" })).toBeChecked();
		await userEvent.keyboard("{Enter}");
		await expect(readyArgs(args).onSubmit).toHaveBeenCalledWith("NO_AI");
	},
};

export const RequiredLinkOpen: Story = {
	args: { state: { ...ready, data: { ...welcome, aiChoice: "ON_PREMISES" } } },
	play: async ({ canvas, userEvent, args }) => {
		const submit = canvas.getByRole("button", { name: "Continue" });
		await expectGenuinelyDisabled(submit);
		await expect(submit).toHaveAccessibleDescription("Connect Slack to finish setup.");
		await userEvent.click(canvas.getByRole("radio", { name: "Private cloud" }));
		await userEvent.click(canvas.getByRole("button", { name: "Connect Slack" }));
		await expect(readyArgs(args).onLink).toHaveBeenCalledWith("slack", "PRIVATE_CLOUD");
	},
};

export const RequiredLinkBroken: Story = {
	args: {
		state: {
			...ready,
			data: { ...welcome, aiChoice: "NO_AI", links: [{ ...slack, available: false }, outline] },
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("button", { name: "Continue" })).toBeEnabled();
		await expect(canvas.getByText(/doesn't hold you up/)).toBeVisible();
		const connect = canvas.getByRole("button", { name: "Connect Slack" });
		await expectGenuinelyDisabled(connect);
		await expect(connect).toHaveAccessibleDescription(
			"Engineering team · Unavailable right now — it doesn't hold you up.",
		);
	},
};

export const OptionalLinksOnly: Story = {
	args: {
		state: {
			...ready,
			data: {
				...welcome,
				aiChoice: "ON_PREMISES",
				links: [{ ...slack, required: false }, outline],
			},
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("button", { name: "Continue" })).toBeEnabled();
		await expect(canvas.getByText("Optional. You can do this later from settings.")).toBeVisible();
	},
};

export const NoLinks: Story = {
	args: { state: { ...ready, data: { ...welcome, links: [] } } },
};

export const LocationUnavailable: Story = {
	args: {
		state: {
			...ready,
			data: {
				...welcome,
				aiOptions: [
					{ choice: "ON_PREMISES", practiceReviewsReady: true, mentorReady: true },
					{ choice: "PRIVATE_CLOUD", practiceReviewsReady: false, mentorReady: false },
				],
			},
		},
	},
	play: async ({ canvas, userEvent }) => {
		const privateCloud = canvas.getByRole("radio", { name: "Private cloud" });
		await expectUnavailable(privateCloud);
		await expect(privateCloud).toHaveAccessibleDescription(/Not set up in this workspace yet/);
		await userEvent.click(canvas.getByRole("radio", { name: "No AI" }));
		await expect(canvas.getByRole("radio", { name: "No AI" })).toBeChecked();
	},
};

export const SavedChoiceUnavailable: Story = {
	args: {
		state: {
			...ready,
			data: {
				...welcome,
				needsWelcome: false,
				aiChoice: "PRIVATE_CLOUD",
				aiOptions: [{ choice: "ON_PREMISES", practiceReviewsReady: true, mentorReady: true }],
				links: [],
			},
		},
	},
	play: async ({ canvas, userEvent }) => {
		await expect(
			canvas.getByText(
				"Your saved choice isn't set up here yet. I won't switch you anywhere else.",
			),
		).toBeVisible();
		await expectGenuinelyDisabled(canvas.getByRole("button", { name: "Save" }));
		await userEvent.click(canvas.getByRole("radio", { name: "No AI" }));
		await expect(canvas.getByRole("button", { name: "Save" })).toBeEnabled();
	},
};

export const ReturnVisit: Story = {
	args: {
		state: {
			...ready,
			data: { ...welcome, needsWelcome: false, completed: true, aiChoice: "ON_PREMISES" },
		},
	},
	play: async ({ canvas, userEvent, args }) => {
		await expect(
			canvas.getByRole("heading", { level: 1, name: "Your AI choice in Engineering" }),
		).toBeVisible();
		await expect(canvas.queryByText(/We learn from/)).toBeNull();
		// The trigger is a `Button` slotted into `CollapsibleTrigger`; the state it reports is what
		// the slot would lose if the spread dropped an `aria-*` prop.
		const disclosure = canvas.getByRole("button", { name: "From your team", expanded: false });
		await userEvent.click(disclosure);
		await expect(disclosure).toHaveAttribute("aria-expanded", "true");
		await expect(canvas.getByText(/We learn from/)).toBeVisible();
		await expectGenuinelyDisabled(canvas.getByRole("button", { name: "Save" }));
		await userEvent.click(canvas.getByRole("button", { name: "Back to workspace" }));
		await expect(readyArgs(args).onLeave).toHaveBeenCalledOnce();
		await expect(readyArgs(args).onSubmit).not.toHaveBeenCalled();
	},
};

export const OAuthReturn: Story = {
	args: { focus: "accounts", state: { ...ready, data: { ...welcome, aiChoice: "ON_PREMISES" } } },
	play: async ({ canvas }) => {
		await waitFor(() =>
			expect(canvas.getByRole("region", { name: /Connect your accounts/ })).toHaveFocus(),
		);
	},
};

export const Saving: Story = {
	args: { state: { ...ready, data: { ...welcome, links: [] } } },
	render: (args) => <Harness {...args} afterSubmit={{ status: "saving", action: "save" }} />,
	play: async ({ canvas, userEvent }) => {
		await userEvent.click(canvas.getByRole("radio", { name: "No AI" }));
		await userEvent.click(canvas.getByRole("button", { name: "Continue" }));
		await expectGenuinelyDisabled(canvas.getByRole("button", { name: "Saving…" }));
		// The checked radio keeps `tabindex="0"` inside a disabled group, so the press is the proof.
		for (const radio of canvas.getAllByRole("radio"))
			await expect(radio).toHaveAttribute("aria-disabled", "true");
		await userEvent.click(canvas.getByRole("radio", { name: "Private cloud" }));
		await expect(canvas.getByRole("radio", { name: "No AI" })).toBeChecked();
		await expectGenuinelyDisabled(canvas.getByRole("button", { name: "Not now" }));
	},
};

export const SaveFailed: Story = {
	args: { state: { ...ready, data: { ...welcome, links: [] } } },
	render: (args) => (
		<Harness
			{...args}
			afterSubmit={{
				status: "error",
				action: "save",
				message: "Your choice could not be saved.",
			}}
		/>
	),
	play: async ({ canvas, userEvent }) => {
		await userEvent.click(canvas.getByRole("radio", { name: "Private cloud" }));
		await userEvent.click(canvas.getByRole("button", { name: "Continue" }));
		const alert = canvas.getByRole("alert");
		await expect(alert).toHaveTextContent("Couldn't save your AI choice");
		await expect(alert).toHaveTextContent("Your choice could not be saved.");
		await waitFor(() => expect(alert).toHaveFocus());
		await expect(canvas.getByRole("radio", { name: "Private cloud" })).toBeChecked();
	},
};

export const LeaveFailed: Story = {
	render: (args) => (
		<Harness
			{...args}
			afterLeave={{
				status: "error",
				action: "continue",
				message: "Your setup could not be dismissed. Try again.",
			}}
		/>
	),
	play: async ({ canvas, userEvent }) => {
		await userEvent.click(canvas.getByRole("button", { name: "Not now" }));
		await expect(canvas.getByRole("alert")).toHaveTextContent(
			"Couldn't continue to your workspace",
		);
	},
};

export const RefreshFailed: Story = {
	args: {
		state: {
			...ready,
			data: { ...welcome, aiChoice: "NO_AI" },
			refresh: { status: "error", error: new Error("Unavailable"), onRetry: fn() },
		},
	},
	play: async ({ canvas, userEvent, args }) => {
		await userEvent.click(canvas.getByRole("button", { name: "Retry" }));
		const { refresh } = readyArgs(args);
		if (refresh?.status !== "error") throw new Error("Expected a retryable refresh error");
		await expect(refresh.onRetry).toHaveBeenCalledOnce();
	},
};

export const Loading: Story = { args: { state: { status: "loading" } } };

export const LoadFailed: Story = {
	args: {
		state: { status: "error", error: new Error("Unavailable"), onRetry: fn(), onLeave: fn() },
	},
	play: async ({ canvas, userEvent, args }) => {
		await userEvent.click(canvas.getByRole("button", { name: "Retry" }));
		if (args.state.status !== "error") throw new Error("Expected the retryable error state");
		await expect(args.state.onRetry).toHaveBeenCalledOnce();
		await expect(canvas.getByRole("button", { name: "Back to workspace" })).toBeEnabled();
	},
};

export const Narrow: Story = {
	args: {
		state: {
			...ready,
			data: {
				...welcome,
				workspaceName: "International engineering collaboration",
				welcomeMarkdown:
					"Read your team's practices and ask questions in Slack.\n\n```text\nhttps://engineering.example.com/teams/international-collaboration/onboarding/first-week/checklist-with-a-long-unbroken-identifier\n```",
				links: [
					{ ...slack, teamName: "Platform, developer experience and internal tooling group" },
					outline,
				],
			},
		},
	},
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	play: async ({ canvas, userEvent }) => {
		// The viewport parameter is applied by the viewport addon and ignored where it is not; at
		// 1440px the overflow assertion below would pass without proving anything.
		await expect(window.innerWidth).toBe(320);
		await expectNoPageOverflow();
		await userEvent.click(canvas.getByRole("radio", { name: "No AI" }));
		await expectNoPageOverflow();
	},
};

export const Dark: Story = {
	globals: { theme: "dark" },
	args: {
		state: {
			...ready,
			data: { ...welcome, aiChoice: "ON_PREMISES", links: [{ ...slack, linked: true }, outline] },
		},
	},
};
