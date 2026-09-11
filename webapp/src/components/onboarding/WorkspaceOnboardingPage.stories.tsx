import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, waitFor } from "storybook/test";

import type { WorkspaceOnboarding, WorkspaceOnboardingLink } from "@/api/types.gen";
import { Stateful } from "@/stories/stateful";
import { expectGenuinelyDisabled } from "@/test/controls";
import { expectNoPageOverflow } from "@/test/reflow";

import {
	type OnboardingSubmission,
	WorkspaceOnboardingPage,
	type WorkspaceOnboardingPageProps,
} from "./WorkspaceOnboardingPage";

const IN_HOUSE = "Only in-house";
const NOT_KEPT = "In-house, or a provider that keeps nothing";
const ANY = "Any AI this workspace set up";
const NO_AI = "No AI";

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
	{ choice: "IN_HOUSE_ONLY", practiceReviewsReady: true, mentorReady: true },
	{ choice: "NOT_KEPT_ONLY", practiceReviewsReady: true, mentorReady: true },
	{ choice: "ANY_DECLARED", practiceReviewsReady: true, mentorReady: true },
] satisfies WorkspaceOnboarding["aiOptions"];

const welcome = {
	workspaceName: "Engineering",
	enabled: true,
	aiChoiceRequired: true,
	needsWelcome: true,
	completed: false,
	revision: 2,
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

/**
 * The four answers are identical in shape — one same-size icon, a title and one sentence each,
 * No AI included. The icon keeps the answers symmetric rather than breaking it: what EDPB 03/2022
 * condemns is one answer carrying more weight than its neighbours, not an icon as such. Everything a
 * reader needs in order to decide sits in the fact list above the group, addressed to every answer
 * at once. Cards are a 2×2 grid because they are choices; facts are rows because they are text.
 *
 * Rejected along the way: an owner-authored welcome text (Heph introduces the page, and words
 * nobody maintains go stale); matching the answer to the model exactly (the answer is a ceiling,
 * so anything stricter also counts); disabling an answer the workspace has not set up (consent is
 * to a boundary, not to today's inventory — the card says what runs, and nothing substitutes); and
 * asking developers to pick data-handling facts themselves (that is the admin's declaration).
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

/** The card a radio sits in, which is what the grid lays out. */
function cardOf(radio: HTMLElement) {
	const card = radio.closest("label");
	if (!card) throw new Error("Expected the radio inside its card");
	return card.getBoundingClientRect();
}

export const Default: Story = {
	play: async ({ canvas }) => {
		const radios = canvas.getAllByRole("radio");
		await expect(radios).toHaveLength(4);
		await expect(canvas.getByRole("radio", { name: IN_HOUSE })).toBe(radios[0]);
		await expect(canvas.getByRole("radio", { name: NOT_KEPT })).toBe(radios[1]);
		await expect(canvas.getByRole("radio", { name: ANY })).toBe(radios[2]);
		await expect(canvas.getByRole("radio", { name: NO_AI })).toBe(radios[3]);
		const submit = canvas.getByRole("button", { name: "Continue" });
		await expectGenuinelyDisabled(submit);
		await expect(submit).toHaveAccessibleDescription("Choose an answer to continue.");
		await expect(canvas.queryByRole("button", { name: "From your team" })).toBeNull();
		// Two columns: the first two cards share a row and a height, the third starts below them.
		const [first, second, third] = radios.map(cardOf);
		if (!first || !second || !third) throw new Error("Expected four cards");
		await expect(first.top).toBe(second.top);
		await expect(first.height).toBe(second.height);
		await expect(third.top).toBeGreaterThan(first.bottom);
		// Facts are rows: each term sits beside its sentence, not above it.
		const term = canvas.getByText("What AI does");
		const detail = canvas.getByText(/Practice reviews about your work/);
		await expect(term.getBoundingClientRect().top).toBe(detail.getBoundingClientRect().top);
		await expect(detail.getBoundingClientRect().left).toBeGreaterThan(
			term.getBoundingClientRect().right,
		);
	},
};

export const FirstVisit: Story = {
	play: async ({ canvas }) => {
		for (const name of [IN_HOUSE, NOT_KEPT, ANY, NO_AI])
			await expect(canvas.getByRole("radio", { name })).not.toBeChecked();
		await expect(
			canvas.getByRole("heading", { level: 1, name: "Welcome to Engineering" }),
		).toBeVisible();
		await expect(
			canvas.getByText(
				"One question: which AI may handle your work. Any answer is fine by me, including none.",
			),
		).toBeVisible();
		await expect(canvas.getByRole("heading", { name: /Connect your accounts/ })).toBeVisible();
		await expect(canvas.getByRole("button", { name: "Skip for now" })).toBeEnabled();
	},
};

export const AnswerAndContinue: Story = {
	args: { state: { ...ready, data: { ...welcome, links: [] } } },
	play: async ({ canvas, userEvent, args }) => {
		await userEvent.click(canvas.getByRole("radio", { name: ANY }));
		await expect(canvas.getByRole("radio", { name: ANY })).toBeChecked();
		await expect(canvas.getByText("Noted. Press Continue and I'll remember that.")).toBeVisible();
		await expect(canvas.getByRole("button", { name: "Continue" })).toBeEnabled();
		await userEvent.click(canvas.getByRole("button", { name: "Continue" }));
		await expect(readyArgs(args).onSubmit).toHaveBeenCalledWith("ANY_DECLARED");
	},
};

export const NoAi: Story = {
	args: { state: { ...ready, data: { ...welcome, links: [] } } },
	play: async ({ canvas, userEvent, args }) => {
		await expect(canvas.getByRole("radio", { name: NO_AI })).toHaveAccessibleDescription(
			"No AI runs for you in this workspace.",
		);
		await expect(canvas.getByRole("radio", { name: IN_HOUSE })).toHaveAccessibleDescription(
			"Runs only on systems your organisation operates.",
		);
		await userEvent.click(canvas.getByRole("radio", { name: NO_AI }));
		await userEvent.click(canvas.getByRole("button", { name: "Continue" }));
		await expect(readyArgs(args).onSubmit).toHaveBeenCalledWith("NO_AI");
	},
};

export const KeyboardOnly: Story = {
	args: { state: { ...ready, data: { ...welcome, links: [] } } },
	play: async ({ canvas, userEvent, args }) => {
		canvas.getByRole("radio", { name: IN_HOUSE }).focus();
		await userEvent.keyboard("{ArrowRight}");
		await expect(canvas.getByRole("radio", { name: NOT_KEPT })).toBeChecked();
		await userEvent.keyboard("{ArrowRight}{ArrowRight}");
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
		await expect(canvas.getByText("Noted. Connect Slack and you're in.")).toBeVisible();
		// Each account row carries its provider's own mark, not the generic link glyph.
		await expect(canvas.getByTitle("SlackIcon")).toBeVisible();
		await expect(canvas.getByTitle("OutlineIcon")).toBeVisible();
		await expect(canvas.queryByTitle("LinkIcon")).toBeNull();
		await userEvent.click(canvas.getByRole("radio", { name: NOT_KEPT }));
		await userEvent.click(canvas.getByRole("button", { name: "Connect Slack" }));
		await expect(readyArgs(args).onLink).toHaveBeenCalledWith("slack", "NOT_KEPT_ONLY");
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
					{ choice: "IN_HOUSE_ONLY", practiceReviewsReady: false, mentorReady: false },
					{ choice: "NOT_KEPT_ONLY", practiceReviewsReady: true, mentorReady: true },
					{ choice: "ANY_DECLARED", practiceReviewsReady: true, mentorReady: true },
				],
				links: [],
			},
		},
	},
	play: async ({ canvas, userEvent, args }) => {
		const inHouse = canvas.getByRole("radio", { name: IN_HOUSE });
		await expect(inHouse).toHaveAccessibleDescription(
			"Runs only on systems your organisation operates. Not set up here yet — nothing runs for you until a workspace owner adds a model.",
		);
		// A ceiling nobody has built up to is still a valid answer, so the card is not disabled.
		await expect(inHouse).not.toHaveAttribute("aria-disabled");
		await userEvent.click(inHouse);
		await expect(inHouse).toBeChecked();
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
					{ choice: "IN_HOUSE_ONLY", practiceReviewsReady: true, mentorReady: false },
					{ choice: "NOT_KEPT_ONLY", practiceReviewsReady: false, mentorReady: true },
					{ choice: "ANY_DECLARED", practiceReviewsReady: true, mentorReady: true },
				],
			},
		},
	},
	play: async ({ canvas }) => {
		// Reviews run for the saved answer, so Heph claims only the part that does not.
		await expect(
			canvas.getByText(
				"Part of your choice isn't set up here yet. I won't switch you anywhere else.",
			),
		).toBeVisible();
		await expect(canvas.getByRole("radio", { name: IN_HOUSE })).toHaveAccessibleDescription(
			/Heph isn't set up for this answer yet\.$/,
		);
		await expect(canvas.getByRole("radio", { name: NOT_KEPT })).toHaveAccessibleDescription(
			/Practice reviews aren't set up for this answer yet\.$/,
		);
		await expect(canvas.getByRole("radio", { name: ANY })).toHaveAccessibleDescription(
			/safety checks, which its staff may read if flagged\.$/,
		);
	},
};

export const SavedChoiceUncovered: Story = {
	args: {
		state: {
			...ready,
			data: {
				...welcome,
				needsWelcome: false,
				aiChoice: "NOT_KEPT_ONLY",
				aiOptions: [
					{ choice: "IN_HOUSE_ONLY", practiceReviewsReady: true, mentorReady: true },
					{ choice: "NOT_KEPT_ONLY", practiceReviewsReady: false, mentorReady: false },
					{ choice: "ANY_DECLARED", practiceReviewsReady: false, mentorReady: false },
				],
				links: [],
			},
		},
	},
	play: async ({ canvas, userEvent }) => {
		await expect(
			canvas.getByText("Your choice isn't set up here yet. I won't switch you anywhere else."),
		).toBeVisible();
		await expect(canvas.getByRole("radio", { name: NOT_KEPT })).toBeChecked();
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
				needsWelcome: false,
				completed: true,
				aiChoice: "IN_HOUSE_ONLY",
				links: [{ ...slack, linked: true }, outline],
			},
		},
	},
	play: async ({ canvas, userEvent, args }) => {
		await expect(
			canvas.getByRole("heading", { level: 1, name: "Your AI choice in Engineering" }),
		).toBeVisible();
		await expect(
			canvas.getByText("You chose Only in-house. Change it whenever you like."),
		).toBeVisible();
		const save = canvas.getByRole("button", { name: "Save" });
		await expectGenuinelyDisabled(save);
		await expect(save).toHaveAccessibleDescription(
			"You can change this any time from the sidebar.",
		);
		await userEvent.click(canvas.getByRole("radio", { name: ANY }));
		await expect(
			canvas.getByText("Save and I'll follow your new answer from the next review on."),
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
			data: { ...welcome, needsWelcome: false, aiChoice: "IN_HOUSE_ONLY" },
		},
	},
	play: async ({ canvas, userEvent, args }) => {
		const save = canvas.getByRole("button", { name: "Save" });
		await expectGenuinelyDisabled(save);
		await expect(save).toHaveAccessibleDescription(
			"You can change this any time from the sidebar.",
		);
		await expect(canvas.getByRole("button", { name: "Connect Slack" })).toBeEnabled();
		await userEvent.click(canvas.getByRole("radio", { name: NO_AI }));
		await expect(
			canvas.getByText("Save and I'll follow your new answer from the next review on."),
		).toBeVisible();
		await expect(save).toBeEnabled();
		await expect(save).not.toHaveAccessibleDescription(/finish setup/);
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
		await waitFor(() =>
			expect(canvas.getByRole("region", { name: /Connect your accounts/ })).toHaveFocus(),
		);
	},
};

export const Saving: Story = {
	args: { state: { ...ready, data: { ...welcome, links: [] } } },
	render: (args) => <Harness {...args} afterSubmit={{ status: "saving", action: "save" }} />,
	play: async ({ canvas, userEvent }) => {
		await userEvent.click(canvas.getByRole("radio", { name: NO_AI }));
		await userEvent.click(canvas.getByRole("button", { name: "Continue" }));
		await expectGenuinelyDisabled(canvas.getByRole("button", { name: "Saving…" }));
		// The checked radio keeps `tabindex="0"` inside a disabled group, so the press is the proof.
		for (const radio of canvas.getAllByRole("radio"))
			await expect(radio).toHaveAttribute("aria-disabled", "true");
		await userEvent.click(canvas.getByRole("radio", { name: NOT_KEPT }));
		await expect(canvas.getByRole("radio", { name: NO_AI })).toBeChecked();
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
		await userEvent.click(canvas.getByRole("radio", { name: NOT_KEPT }));
		await userEvent.click(canvas.getByRole("button", { name: "Continue" }));
		const alert = canvas.getByRole("alert");
		await expect(alert).toHaveTextContent("Couldn't save your AI choice");
		await expect(alert).toHaveTextContent("Your choice could not be saved.");
		await waitFor(() => expect(alert).toHaveFocus());
		await expect(canvas.getByRole("radio", { name: NOT_KEPT })).toBeChecked();
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
		if (refresh?.status !== "error") throw new Error("Expected a retryable refresh error");
		await expect(refresh.onRetry).toHaveBeenCalledOnce();
	},
};

export const Loading: Story = {
	args: { state: { status: "loading" } },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("heading", { level: 1, name: "Workspace setup" })).toBeVisible();
		await expect(canvas.getByText("Give me a moment — I'm fetching your setup.")).toBeVisible();
	},
};

export const LoadFailed: Story = {
	args: {
		state: { status: "error", error: new Error("Unavailable"), onRetry: fn(), onLeave: fn() },
	},
	play: async ({ canvas, userEvent, args }) => {
		await expect(canvas.getByText("I couldn't fetch your setup just now.")).toBeVisible();
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
		// One column: every card starts below the one before it.
		const cards = canvas.getAllByRole("radio").map(cardOf);
		for (let index = 1; index < cards.length; index++) {
			const above = cards[index - 1];
			const card = cards[index];
			if (!above || !card) throw new Error("Expected four cards");
			await expect(card.top).toBeGreaterThanOrEqual(above.bottom);
			await expect(card.left).toBe(above.left);
		}
		await userEvent.click(canvas.getByRole("radio", { name: NO_AI }));
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
