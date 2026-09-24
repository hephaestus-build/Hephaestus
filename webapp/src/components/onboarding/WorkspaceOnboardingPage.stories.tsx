import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, waitFor } from "storybook/test";

import type { WorkspaceOnboarding, WorkspaceOnboardingLink } from "@/api/types.gen";
import { expectNoPageOverflow } from "@/stories/reflow";
import { Stateful } from "@/stories/stateful";
import { expectGenuinelyDisabled } from "@/test/controls";

import {
	type OnboardingSubmission,
	WorkspaceOnboardingPage,
	type WorkspaceOnboardingPageProps,
} from "./WorkspaceOnboardingPage";

const IN_HOUSE = /^In-house /u;
const CLOUD = /^Cloud /u;
const NO_AI = /^No AI /u;

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

const allCovered = [
	{
		choice: "IN_HOUSE_ONLY",
		practiceReviewsReady: true,
		mentorReady: true,
		models: [{ name: "Llama 3.3", brand: "META", dataHandlingTier: "IN_HOUSE" }],
	},
	{
		choice: "CLOUD",
		practiceReviewsReady: true,
		mentorReady: true,
		models: [{ name: "GPT-5", brand: "OPENAI", dataHandlingTier: "CLOUD" }],
	},
] satisfies WorkspaceOnboarding["aiOptions"];

const welcome = {
	workspaceName: "Engineering",
	enabled: true,
	aiChoiceRequired: true,
	needsSetup: true,
	aiOptions: allCovered,
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

/** Equal, unselected choices; saving AI preferences never depends on connecting accounts. */
const meta = {
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
	if (state.status !== "ready") {
		return <WorkspaceOnboardingPage {...args} />;
	}
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
							if (afterLeave) {
								setValue({ submission: afterLeave, aiChoice });
							}
						},
					}}
				/>
			)}
		</Stateful>
	);
}

function readyArgs(args: WorkspaceOnboardingPageProps) {
	if (args.state.status !== "ready") {
		throw new Error("Expected the ready state");
	}
	return args.state;
}

/** The card a radio sits in, which is what the grid lays out. */
function cardOf(radio: HTMLElement) {
	const card = radio.closest("label");
	if (!card) {
		throw new Error("Expected the radio inside its card");
	}
	return card.getBoundingClientRect();
}

export const Default: Story = {
	play: async ({ canvas }) => {
		const radios = canvas.getAllByRole("radio");
		await expect(radios).toHaveLength(3);
		await expect(canvas.getByRole("radio", { name: IN_HOUSE })).toBe(radios[0]);
		await expect(canvas.getByRole("radio", { name: CLOUD })).toBe(radios[1]);
		await expect(canvas.getByRole("radio", { name: NO_AI })).toBe(radios[2]);
		const submit = canvas.getByRole("button", { name: "Continue" });
		await expectGenuinelyDisabled(submit);
		await expect(submit).toHaveAccessibleDescription("Choose an answer to continue.");
		await expect(canvas.queryByRole("button", { name: "From your team" })).toBeNull();
		const [first, second, third] = radios.map(cardOf);
		if (!first || !second || !third) {
			throw new Error("Expected three cards");
		}
		await expect(first.top).toBe(second.top);
		await expect(second.top).toBe(third.top);
		const capacityRows = canvas.getAllByText("Capacity");
		await expect(capacityRows).toHaveLength(3);
		await expect(capacityRows[0]?.getBoundingClientRect().top).toBe(
			capacityRows[1]?.getBoundingClientRect().top,
		);
		await expect(capacityRows[1]?.getBoundingClientRect().top).toBe(
			capacityRows[2]?.getBoundingClientRect().top,
		);
		const term = canvas.getByText("What AI does");
		const detail = canvas.getByText(/Hephaestus reviews your work against/u);
		await expect(detail.getBoundingClientRect().top).toBeGreaterThan(
			term.getBoundingClientRect().bottom - 1,
		);
		await expect(detail.getBoundingClientRect().left).toBeGreaterThan(
			term.getBoundingClientRect().left,
		);
	},
};

export const FirstVisit: Story = {
	play: async ({ canvas }) => {
		for (const name of [IN_HOUSE, CLOUD, CLOUD, NO_AI]) {
			await expect(canvas.getByRole("radio", { name })).not.toBeChecked();
		}
		await expect(canvas.getByRole("heading", { level: 1, name: "Your AI choice" })).toBeVisible();
		await expect(
			canvas.getByText("Which AI may handle your work? Any answer is fine, including none."),
		).toBeVisible();
		await expect(canvas.getByRole("heading", { name: /Connect your accounts/u })).toBeVisible();
		await expect(canvas.getByRole("button", { name: "Skip for now" })).toBeEnabled();
	},
};

export const AnswerAndContinue: Story = {
	args: { state: { ...ready, data: { ...welcome, links: [] } } },
	play: async ({ canvas, userEvent, args }) => {
		await userEvent.click(canvas.getByRole("radio", { name: CLOUD }));
		await expect(canvas.getByRole("radio", { name: CLOUD })).toBeChecked();
		const models = canvas.getByRole("region", { name: "Models for this answer in Engineering" });
		await expect(models).toHaveTextContent("GPT-5");
		await expect(models).toHaveTextContent("Model: OpenAI");
		await expect(models).toHaveTextContent("Declared cloud");
		await expect(canvas.getByText("Press Continue and it holds in every workspace.")).toBeVisible();
		await expect(canvas.getByRole("button", { name: "Continue" })).toBeEnabled();
		await userEvent.click(canvas.getByRole("button", { name: "Continue" }));
		await expect(readyArgs(args).onSubmit).toHaveBeenCalledWith("CLOUD");
	},
};

export const NoAi: Story = {
	args: { state: { ...ready, data: { ...welcome, links: [] } } },
	play: async ({ canvas, userEvent, args }) => {
		await expect(canvas.getByRole("radio", { name: NO_AI })).toHaveAccessibleName(
			/No AI.*Sync and stored work stay.*No new feedback or Heph replies.*None sent for your work/u,
		);
		await expect(canvas.queryByRole("region", { name: /Models for this answer/u })).toBeNull();
		await userEvent.click(canvas.getByRole("radio", { name: NO_AI }));
		await userEvent.click(canvas.getByRole("button", { name: "Continue" }));
		await expect(readyArgs(args).onSubmit).toHaveBeenCalledWith("NO_AI");
	},
};

export const WorkspaceModels: Story = {
	args: {
		state: {
			...ready,
			data: {
				...welcome,
				links: [],
				aiChoice: "CLOUD",
				aiOptions: [
					{
						choice: "IN_HOUSE_ONLY",
						practiceReviewsReady: true,
						mentorReady: true,
						models: [
							{
								name: "Qwen3",
								brand: "QWEN",
								connectionPlatform: "LOGOS",
								dataHandlingTier: "IN_HOUSE",
							},
						],
					},
					{
						choice: "CLOUD",
						practiceReviewsReady: true,
						mentorReady: true,
						models: [
							{
								name: "Qwen3",
								brand: "QWEN",
								connectionPlatform: "LOGOS",
								dataHandlingTier: "IN_HOUSE",
							},
							{
								name: "gpt-6-luna",
								brand: "OPENAI",
								connectionPlatform: "AZURE",
								dataHandlingTier: "CLOUD",
							},
							{ name: "Team model", dataHandlingTier: "IN_HOUSE" },
						],
					},
				],
			},
		},
	},
	play: async ({ canvas }) => {
		const models = canvas.getByRole("region", { name: "Models for this answer in Engineering" });
		await expect(models).toHaveTextContent("Qwen3");
		await expect(models).toHaveTextContent("gpt-6-luna");
		await expect(models).toHaveTextContent("Model: OpenAI");
		await expect(models).toHaveTextContent("via Microsoft Azure");
		await expect(models).toHaveTextContent("Declared cloud");
		await expect(models).toHaveTextContent("Team model");
		await expect(models.querySelectorAll("img")).toHaveLength(4);
		const cloud = canvas.getByRole("radio", { name: CLOUD });
		await expect(cloud).toHaveAccessibleName(/gpt-6-luna.*Microsoft Azure/u);
		const header = cloud.closest("label")?.querySelector('[data-slot="ai-choice-header"]');
		await expect(header).toHaveTextContent("gpt-6-luna");
		await expect(header).toHaveTextContent("via Microsoft Azure");
		await expect(header?.querySelectorAll("img")).toHaveLength(2);
	},
};

export const ProviderOperatedLogos: Story = {
	args: {
		state: {
			...ready,
			data: {
				...welcome,
				links: [],
				aiChoice: "CLOUD",
				aiOptions: [
					{ choice: "IN_HOUSE_ONLY", practiceReviewsReady: false, mentorReady: false, models: [] },
					{
						choice: "CLOUD",
						practiceReviewsReady: true,
						mentorReady: true,
						models: [
							{
								name: "Qwen3",
								brand: "QWEN",
								connectionPlatform: "LOGOS",
								dataHandlingTier: "CLOUD",
							},
						],
					},
				],
			},
		},
	},
	play: async ({ canvas }) => {
		const models = canvas.getByRole("region", { name: "Models for this answer in Engineering" });
		await expect(models).toHaveTextContent("Qwen3");
		await expect(models).toHaveTextContent("via Logos");
		await expect(models).toHaveTextContent("Declared cloud");
	},
};

export const KeyboardOnly: Story = {
	args: { state: { ...ready, data: { ...welcome, links: [] } } },
	play: async ({ canvas, userEvent, args }) => {
		canvas.getByRole("radio", { name: IN_HOUSE }).focus();
		await userEvent.keyboard("{ArrowRight}");
		await expect(canvas.getByRole("radio", { name: CLOUD })).toBeChecked();
		await userEvent.keyboard("{ArrowRight}");
		await expect(canvas.getByRole("radio", { name: NO_AI })).toBeChecked();
		await userEvent.keyboard("{Enter}");
		await expect(readyArgs(args).onSubmit).toHaveBeenCalledWith("NO_AI");
	},
};

export const RequiredLinkOpen: Story = {
	args: { state: { ...ready, data: { ...welcome, aiChoice: "IN_HOUSE_ONLY" } } },
	play: async ({ canvas, userEvent, args }) => {
		const submit = canvas.getByRole("button", { name: "Continue" });
		await expectGenuinelyDisabled(submit);
		await expect(submit).toHaveAccessibleDescription("Connect Slack to finish setup.");
		await expect(
			canvas.getByText(
				"Your AI choice is set and holds in all your workspaces. Connect Slack and you're in.",
			),
		).toBeVisible();
		await expect(canvas.getByTitle("SlackIcon")).toBeVisible();
		await expect(canvas.getByTitle("OutlineIcon")).toBeVisible();
		await expect(canvas.queryByTitle("LinkIcon")).toBeNull();
		await userEvent.click(canvas.getByRole("radio", { name: CLOUD }));
		await expect(
			canvas.getByRole("button", { name: "Save AI choice and connect Slack" }),
		).toHaveTextContent("Save and connect");
		await userEvent.click(canvas.getByRole("button", { name: "Save AI choice and connect Slack" }));
		await expect(readyArgs(args).onLink).toHaveBeenCalledWith("slack", "CLOUD");
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
		await expect(canvas.getByText(/doesn't hold you up/u)).toBeVisible();
		const connect = canvas.getByRole("button", { name: "Connect Slack" });
		await expectGenuinelyDisabled(connect);
		await expect(connect).toHaveAccessibleDescription(
			"Unavailable right now for Engineering team. It doesn't hold you up.",
		);
	},
};

export const OptionalLinksOnly: Story = {
	args: {
		state: {
			...ready,
			data: {
				...welcome,
				aiChoice: "IN_HOUSE_ONLY",
				links: [{ ...slack, required: false }, outline],
			},
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("button", { name: "Continue" })).toBeEnabled();
		await expect(
			canvas.getByText("Optional. You can do this later from User settings."),
		).toBeVisible();
	},
};

export const NoLinks: Story = {
	args: { state: { ...ready, data: { ...welcome, links: [] } } },
};

export const OptionUncovered: Story = {
	args: {
		state: {
			...ready,
			data: {
				...welcome,
				aiOptions: [
					{ choice: "IN_HOUSE_ONLY", practiceReviewsReady: false, mentorReady: false, models: [] },
					{ choice: "CLOUD", practiceReviewsReady: true, mentorReady: true, models: [] },
				],
				links: [],
			},
		},
	},
	play: async ({ canvas, userEvent, args }) => {
		const inHouse = canvas.getByRole("radio", { name: IN_HOUSE });
		await expect(inHouse).toHaveAccessibleName(/No model ready here/u);
		await expect(canvas.queryByText(/is set up within this answer yet/u)).toBeNull();
		await expect(inHouse).not.toHaveAttribute("aria-disabled");
		await userEvent.click(inHouse);
		await expect(inHouse).toBeChecked();
		await expect(
			canvas.getByText(
				"Nothing in Engineering is set up within this answer yet. You get no AI here until a workspace owner adds a model that fits. Your choice still counts.",
			),
		).toBeVisible();
		await expect(
			canvas.getByText(
				"That isn't set up here yet. Nothing switches you elsewhere. Press Continue and it holds in every workspace.",
			),
		).toBeVisible();
		await expect(canvas.getByRole("button", { name: "Continue" })).toBeEnabled();
		await userEvent.click(canvas.getByRole("button", { name: "Continue" }));
		await expect(readyArgs(args).onSubmit).toHaveBeenCalledWith("IN_HOUSE_ONLY");
	},
};

export const HephNotCovered: Story = {
	args: {
		state: {
			...ready,
			data: {
				...welcome,
				aiChoice: "IN_HOUSE_ONLY",
				aiOptions: [
					{ choice: "IN_HOUSE_ONLY", practiceReviewsReady: true, mentorReady: false, models: [] },
					{ choice: "CLOUD", practiceReviewsReady: false, mentorReady: true, models: [] },
				],
			},
		},
	},
	play: async ({ canvas, userEvent }) => {
		await expect(
			canvas.getByText(
				"Part of your choice isn't set up here yet. Nothing switches you anywhere else.",
			),
		).toBeVisible();
		await expect(
			canvas.getByText(
				"Practice reviews run in Engineering within this answer. Heph isn't set up here yet.",
			),
		).toBeVisible();
		await userEvent.click(canvas.getByRole("radio", { name: CLOUD }));
		await expect(
			canvas.getByText(
				"Heph runs in Engineering within this answer. Practice reviews aren't set up here yet.",
			),
		).toBeVisible();
	},
};

export const SavedChoiceUncovered: Story = {
	args: {
		state: {
			...ready,
			data: {
				...welcome,
				needsSetup: false,
				aiChoice: "CLOUD",
				aiOptions: [
					{ choice: "IN_HOUSE_ONLY", practiceReviewsReady: true, mentorReady: true, models: [] },
					{ choice: "CLOUD", practiceReviewsReady: false, mentorReady: false, models: [] },
				],
				links: [],
			},
		},
	},
	play: async ({ canvas, userEvent }) => {
		await expect(
			canvas.getByText("Your choice isn't set up here yet. Nothing switches you anywhere else."),
		).toBeVisible();
		await expect(canvas.getByRole("radio", { name: CLOUD })).toBeChecked();
		await expectGenuinelyDisabled(canvas.getByRole("button", { name: "Save" }));
		await userEvent.click(canvas.getByRole("radio", { name: NO_AI }));
		await expect(canvas.getByRole("button", { name: "Save" })).toBeEnabled();
	},
};

export const ReturnVisit: Story = {
	args: {
		state: {
			...ready,
			data: {
				...welcome,
				needsSetup: false,
				aiChoice: "IN_HOUSE_ONLY",
				links: [{ ...slack, linked: true }, outline],
			},
		},
	},
	play: async ({ canvas, userEvent, args }) => {
		await expect(canvas.getByRole("heading", { level: 1, name: "Your AI choice" })).toBeVisible();
		await expect(
			canvas.getByText("You chose In-house. Change it whenever you like."),
		).toBeVisible();
		const save = canvas.getByRole("button", { name: "Save" });
		await expectGenuinelyDisabled(save);
		await expect(save).toHaveAccessibleDescription(
			"Applies in all your workspaces. Change it any time.",
		);
		await userEvent.click(canvas.getByRole("radio", { name: CLOUD }));
		await expect(
			canvas.getByText(
				"Save to apply your new choice in every workspace. Requests already sent cannot be recalled.",
			),
		).toBeVisible();
		await userEvent.click(canvas.getByRole("radio", { name: IN_HOUSE }));
		await userEvent.click(canvas.getByRole("button", { name: "Back to workspace" }));
		await expect(readyArgs(args).onLeave).toHaveBeenCalledOnce();
		await expect(readyArgs(args).onSubmit).not.toHaveBeenCalled();
	},
};

/**
 * A member who skipped setup can still withdraw from AI: a return visit writes the answer alone and
 * never finishes setup, so an account the workspace requires does not gate Save.
 */
export const ReturnVisitRequiredLinkOpen: Story = {
	args: {
		state: {
			...ready,
			data: { ...welcome, needsSetup: false, aiChoice: "IN_HOUSE_ONLY" },
		},
	},
	play: async ({ canvas, userEvent, args }) => {
		const save = canvas.getByRole("button", { name: "Save" });
		await expectGenuinelyDisabled(save);
		await expect(save).toHaveAccessibleDescription(
			"Applies in all your workspaces. Change it any time.",
		);
		await expect(canvas.getByRole("button", { name: "Connect Slack" })).toBeEnabled();
		await userEvent.click(canvas.getByRole("radio", { name: NO_AI }));
		await expect(
			canvas.getByText(
				"Save to apply your new choice in every workspace. Requests already sent cannot be recalled.",
			),
		).toBeVisible();
		await expect(save).toBeEnabled();
		await expect(save).not.toHaveAccessibleDescription(/finish setup/u);
		await userEvent.click(save);
		await expect(readyArgs(args).onSubmit).toHaveBeenCalledWith("NO_AI");
	},
};

export const OAuthReturn: Story = {
	args: {
		focus: "accounts",
		state: { ...ready, data: { ...welcome, aiChoice: "IN_HOUSE_ONLY" } },
	},
	play: async ({ canvas }) => {
		await waitFor(async () =>
			expect(canvas.getByRole("region", { name: /Connect your accounts/u })).toHaveFocus(),
		);
	},
};

/**
 * The round-trip that connected the last required account: the server now reports nothing owed, so
 * the page is a return visit and Heph names the exit instead of leaving a disabled Save as the
 * only visible control.
 */
export const OAuthReturnDone: Story = {
	args: {
		focus: "accounts",
		state: {
			...ready,
			data: {
				...welcome,
				needsSetup: false,
				aiChoice: "IN_HOUSE_ONLY",
				links: [{ ...slack, linked: true }, outline],
			},
		},
	},
	play: async ({ canvas }) => {
		await expect(
			canvas.getByText(
				"Your accounts are connected. Head back to your workspace whenever you're ready.",
			),
		).toBeVisible();
		await expect(canvas.getByRole("button", { name: "Back to workspace" })).toBeEnabled();
		await expectGenuinelyDisabled(canvas.getByRole("button", { name: "Save" }));
	},
};

/**
 * A member who answered in another workspace is never asked again: the saved answer is checked,
 * Heph says it holds here, and only this workspace's required account link stands between them
 * and the workspace.
 */
export const AnsweredElsewhere: Story = {
	args: { state: { ...ready, data: { ...welcome, aiChoice: "CLOUD", links: [slack] } } },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("radio", { name: CLOUD })).toBeChecked();
		await expect(
			canvas.getByText(
				"Your AI choice is set and holds in all your workspaces. Connect Slack and you're in.",
			),
		).toBeVisible();
		await expect(canvas.getByRole("button", { name: "Connect Slack" })).toBeEnabled();
		await expect(canvas.getByRole("button", { name: "Skip for now" })).toHaveAccessibleDescription(
			"Skipping does not save an answer selected above. Your saved choice (Cloud) stays in effect.",
		);
	},
};

export const Saving: Story = {
	args: { state: { ...ready, data: { ...welcome, links: [] } } },
	render: (args) => <Harness {...args} afterSubmit={{ status: "saving", action: "save" }} />,
	play: async ({ canvas, userEvent }) => {
		const noAi = canvas.getByRole("radio", { name: NO_AI });
		await userEvent.click(noAi);
		await userEvent.click(canvas.getByRole("button", { name: "Continue" }));
		await expectGenuinelyDisabled(canvas.getByRole("button", { name: "Saving…" }));
		for (const radio of canvas.getAllByRole("radio")) {
			await expect(radio.closest("label")).toBeVisible();
			await expect(radio).toBeDisabled();
		}
		await expect(noAi).toBeChecked();
		await expectGenuinelyDisabled(canvas.getByRole("button", { name: "Skip for now" }));
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
		await userEvent.click(canvas.getByRole("radio", { name: CLOUD }));
		await userEvent.click(canvas.getByRole("button", { name: "Continue" }));
		const alert = canvas.getByRole("alert");
		await expect(alert).toHaveTextContent("Couldn't save your AI choice");
		await expect(alert).toHaveTextContent("Your choice could not be saved.");
		await waitFor(async () => expect(alert).toHaveFocus());
		await expect(canvas.getByRole("radio", { name: CLOUD })).toBeChecked();
	},
};

export const SkipFailed: Story = {
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
		await userEvent.click(canvas.getByRole("button", { name: "Skip for now" }));
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
		await expect(canvas.getByText("Couldn't refresh your setup")).toBeVisible();
		await userEvent.click(canvas.getByRole("button", { name: "Retry" }));
		const { refresh } = readyArgs(args);
		if (refresh?.status !== "error") {
			throw new Error("Expected a retryable refresh error");
		}
		await expect(refresh.onRetry).toHaveBeenCalledOnce();
	},
};

export const Loading: Story = {
	args: { state: { status: "loading" } },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("heading", { level: 1, name: "Your AI choice" })).toBeVisible();
		await expect(
			canvas.getByText("Give me a moment. Hephaestus is fetching your setup."),
		).toBeVisible();
	},
};

export const LoadFailed: Story = {
	args: {
		state: { status: "error", error: new Error("Unavailable"), onRetry: fn(), onLeave: fn() },
	},
	play: async ({ canvas, userEvent, args }) => {
		await expect(canvas.getByText("Hephaestus couldn't fetch your setup just now.")).toBeVisible();
		await userEvent.click(canvas.getByRole("button", { name: "Retry" }));
		if (args.state.status !== "error") {
			throw new Error("Expected the retryable error state");
		}
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
				links: [
					{
						...slack,
						displayName: "Engineering and product collaboration",
						teamName: "Platform, developer experience and internal tooling group",
					},
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
		const cards = canvas.getAllByRole("radio").map(cardOf);
		for (let index = 1; index < cards.length; index += 1) {
			const above = cards[index - 1];
			const card = cards[index];
			if (!above || !card) {
				throw new Error("Expected three cards");
			}
			await expect(card.top).toBeGreaterThanOrEqual(above.bottom);
			await expect(card.left).toBe(above.left);
		}
		await userEvent.click(canvas.getByRole("radio", { name: CLOUD }));
		await expect(canvas.getByRole("region", { name: /Models for this answer/u })).toBeVisible();
		await expectNoPageOverflow();
	},
};

export const Dark: Story = {
	globals: { theme: "dark" },
	args: {
		state: {
			...ready,
			data: {
				...welcome,
				aiChoice: "IN_HOUSE_ONLY",
				links: [{ ...slack, linked: true }, outline],
			},
		},
	},
};

export const SaveNoAiBeforeConnecting: Story = {
	play: async ({ canvas, userEvent, args }) => {
		await userEvent.click(canvas.getByRole("radio", { name: NO_AI }));
		await expect(readyArgs(args).onSubmit).not.toHaveBeenCalled();
		await userEvent.click(canvas.getByRole("button", { name: "Save AI choice" }));
		await expect(readyArgs(args).onSubmit).toHaveBeenCalledWith("NO_AI");
		await expect(canvas.getByRole("radio", { name: NO_AI })).toBeChecked();
		await expect(canvas.getByRole("button", { name: "Continue" })).toBeDisabled();
		await expect(canvas.getByRole("button", { name: "Connect Slack" })).toBeEnabled();
		await expect(canvas.getByRole("button", { name: "Skip for now" })).toHaveAccessibleDescription(
			"Skipping does not save an answer selected above. Your saved choice (No AI) stays in effect.",
		);
	},
};

export const SkipWithoutSaving: Story = {
	play: async ({ canvas, userEvent, args }) => {
		await userEvent.click(canvas.getByRole("radio", { name: CLOUD }));
		const skip = canvas.getByRole("button", { name: "Skip for now" });
		await expect(skip).toHaveAccessibleDescription(
			"Skipping does not save an answer selected above. This workspace cannot use AI for you until you save an answer.",
		);
		await userEvent.click(skip);
		await expect(readyArgs(args).onSubmit).not.toHaveBeenCalled();
		await expect(readyArgs(args).onLeave).toHaveBeenCalledOnce();
	},
};

export const OptionalChoiceSkipped: Story = {
	args: {
		state: { ...ready, data: { ...welcome, aiChoiceRequired: false, links: [] } },
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("button", { name: "Skip for now" })).toHaveAccessibleDescription(
			"Skipping does not save an answer selected above. Workspaces that do not require an answer may use a model whose handling is not declared.",
		);
	},
};
