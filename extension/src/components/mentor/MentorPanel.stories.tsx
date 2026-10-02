import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, userEvent } from "storybook/test";

import type { ChatMessage } from "@/lib/types";
import {
	type MentorConversation,
	MentorPanel,
	type MentorPanelState,
} from "~/components/mentor/MentorPanel";
import { firstMessage } from "~/shared/mentor";
import { expectNoHorizontalOverflow } from "~/stories/reflow";
import { required } from "~/testing/required";

const WEB_APP = "https://hephaestus.build";
const REFERENCE =
	"About pull request #42 in HephaestusTest/lifecycle-validation: https://github.com/HephaestusTest/lifecycle-validation/pull/42";
const THREAD = "5b0f7c2e-3a1d-4e8b-9c6f-2d4e6a8b0c1e";

const READY: Extract<MentorPanelState, { status: "ready" }> = {
	status: "ready",
	workspace: { slug: "aet", displayName: "AET" },
	work: {
		noun: "pull request",
		label: "#42",
		repository: "HephaestusTest/lifecycle-validation",
		title: "Retry the login request once after a timeout",
		canonicalUrl: "https://github.com/HephaestusTest/lifecycle-validation/pull/42",
	},
	reference: REFERENCE,
	onboardingUrl: `${WEB_APP}/w/aet/onboarding`,
};

const STARTED = {
	...READY,
	threadId: THREAD,
	threadUrl: `${WEB_APP}/w/aet/mentor/${THREAD}`,
} satisfies MentorPanelState;

const MESSAGES: ChatMessage[] = [
	{
		id: "0d9a3f1e-1b2c-4d5e-8f60-718293a4b5c6",
		role: "user",
		parts: [
			{
				type: "text",
				text: firstMessage(REFERENCE, "Why did the description practice fail here?"),
			},
		],
	},
	{
		id: "1e0b4a2f-2c3d-4e6f-9071-8293a4b5c6d7",
		role: "assistant",
		parts: [
			{
				type: "text",
				text: "The description says **what** changed but not **why**. Add one sentence on the timeout you saw, and the retry limit you chose.",
			},
		],
	},
];

const EMPTY: MentorConversation = { messages: [], status: "ready", restoring: false };
const TALKING: MentorConversation = { messages: MESSAGES, status: "ready", restoring: false };

/**
 * The Heph panel: Chrome's side panel beside one tab, holding one conversation with Heph about the
 * work that tab shows. It never shows the practice review; that stays in the page.
 */
const meta = {
	component: MentorPanel,
	tags: ["autodocs"],
	parameters: { layout: "fullscreen" },
	args: {
		state: READY,
		conversation: EMPTY,
		onSend: fn(),
		onStop: fn(),
		onRetry: fn(),
		onNewConversation: fn(),
		onReload: fn(),
		onOpenSettings: fn(),
	},
} satisfies Meta<typeof MentorPanel>;

export default meta;
type Story = StoryObj<typeof meta>;

/** Before the first message: what Heph knows and does not, and the line the message will start with. */
export const NewConversation: Story = {
	play: async ({ canvas, args }) => {
		await expect(canvas.getByText("Ask Heph about pull request #42.")).toBeVisible();
		await expect(canvas.getByText(/It does not read this page/u)).toBeVisible();
		await expect(canvas.getByText(REFERENCE)).toBeVisible();
		await expect(canvas.queryByRole("button", { name: /New conversation/u })).toBeNull();
		const send = canvas.getByRole("button", { name: "Send" });
		await expect(send).toBeDisabled();
		await userEvent.type(canvas.getByRole("textbox", { name: "Message Heph" }), "Why?{Enter}");
		await expect(args.onSend).toHaveBeenCalledWith("Why?");
	},
};

/** A conversation under way, with the way to continue it in Hephaestus and to start another. */
export const Conversation: Story = {
	args: { state: STARTED, conversation: TALKING },
	play: async ({ canvas, args }) => {
		await expect(canvas.getByRole("list", { name: "Conversation" })).toBeVisible();
		await expect(canvas.getByRole("link", { name: /Continue in Hephaestus/u })).toHaveAttribute(
			"href",
			STARTED.threadUrl,
		);
		await expect(canvas.queryByText(/Your first message starts with/u)).toBeNull();
		await userEvent.click(canvas.getByRole("button", { name: /New conversation/u }));
		await expect(args.onNewConversation).toHaveBeenCalledOnce();
	},
};

/** Heph has the message and has not started answering. */
export const Thinking: Story = {
	args: {
		state: STARTED,
		conversation: { messages: MESSAGES.slice(0, 1), status: "submitted", restoring: false },
	},
	play: async ({ canvas, args }) => {
		await expect(canvas.getAllByText("Heph is thinking…")).not.toHaveLength(0);
		await userEvent.click(canvas.getByRole("button", { name: "Stop the reply" }));
		await expect(args.onStop).toHaveBeenCalledOnce();
		await expect(canvas.getByRole("button", { name: /New conversation/u })).toBeDisabled();
	},
};

/** The reply is arriving: Send is Stop until it ends. */
export const Streaming: Story = {
	args: { state: STARTED, conversation: { ...TALKING, status: "streaming" } },
	play: async ({ canvas }) => {
		await expect(canvas.queryByRole("button", { name: "Send" })).toBeNull();
		await expect(canvas.getByRole("button", { name: "Stop the reply" })).toBeVisible();
	},
};

/** The turn failed: the reason as the server or the extension said it, and a way to ask again. */
export const ReplyFailed: Story = {
	args: {
		state: STARTED,
		conversation: {
			messages: MESSAGES.slice(0, 1),
			status: "error",
			error: "The connection to Hephaestus was lost, so the reply stopped.",
			restoring: false,
		},
	},
	play: async ({ canvas, args }) => {
		await expect(canvas.getByRole("alert")).toHaveTextContent(
			"The connection to Hephaestus was lost",
		);
		await userEvent.click(canvas.getByRole("button", { name: "Try again" }));
		await expect(args.onRetry).toHaveBeenCalledOnce();
	},
};

/** A reply the server stored as interrupted says so under it. */
export const InterruptedReply: Story = {
	args: {
		state: STARTED,
		conversation: {
			...TALKING,
			messages: [
				required(MESSAGES[0], "the question"),
				{ ...required(MESSAGES[1], "the reply"), metadata: { status: "interrupted" } },
			],
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText(/This reply was interrupted before it finished/u)).toBeVisible();
	},
};

/** The panel was reopened: the stored conversation is read back, and nothing can be sent meanwhile. */
export const Restoring: Story = {
	args: { state: STARTED, conversation: { ...EMPTY, restoring: true } },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("textbox", { name: "Message Heph" })).toBeDisabled();
		await expect(canvas.getByText("Loading this conversation…")).toBeVisible();
	},
};

/** The stored conversation could not be read back. */
export const RestoreFailed: Story = {
	args: {
		state: STARTED,
		conversation: { ...EMPTY, restoreError: "Hephaestus could not be reached." },
	},
	play: async ({ canvas, args }) => {
		await userEvent.click(canvas.getByRole("button", { name: "Try again" }));
		await expect(args.onReload).toHaveBeenCalledOnce();
	},
};

/** The tab moved to other work: the conversation it holds is not continued here. */
export const TabMovedOn: Story = {
	args: { state: { ...READY, heldAbout: "HephaestusTest/lifecycle-validation #41" } },
	play: async ({ canvas, args }) => {
		await expect(canvas.getByRole("textbox", { name: "Message Heph" })).toBeDisabled();
		await userEvent.click(canvas.getByRole("button", { name: "Start one about #42" }));
		await expect(args.onNewConversation).toHaveBeenCalledOnce();
	},
};

/** The reader chose No AI: the web app's own notice, and the way to change the choice. */
export const NoAi: Story = {
	args: { state: { ...READY, notice: { reason: "no-ai" } } },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Heph is off for you")).toBeVisible();
		await expect(canvas.getByRole("link", { name: /Change your AI choice/u })).toHaveAttribute(
			"href",
			READY.onboardingUrl,
		);
		await expect(canvas.getByRole("textbox", { name: "Message Heph" })).toBeDisabled();
	},
};

/** No Heph model is ready in the workspace; only an owner can change that, so there is no link. */
export const NotSetUp: Story = {
	args: { state: { ...READY, notice: { reason: "not-set-up" } } },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Heph isn't set up in this workspace yet")).toBeVisible();
		await expect(canvas.queryByRole("link", { name: /AI choice/u })).toBeNull();
	},
};

/** The reader has not chosen yet and the workspace requires it. */
export const ChoiceRequired: Story = {
	args: { state: { ...READY, notice: { reason: "choice-required" } } },
};

/** Heph has no model within the reader's choice; the choice is named by its own title. */
export const UnavailableForChoice: Story = {
	args: { state: { ...READY, notice: { reason: "unavailable", choice: "IN_HOUSE_ONLY" } } },
};

export const Loading: Story = { args: { state: { status: "loading" } } };

/** The worker could not answer. */
export const Failed: Story = {
	args: { state: { status: "failed", message: "The extension did not answer." } },
	play: async ({ canvas, args }) => {
		await userEvent.click(canvas.getByRole("button", { name: "Try again" }));
		await expect(args.onReload).toHaveBeenCalledOnce();
	},
};

export const NotConfigured: Story = { args: { state: { status: "not-configured" } } };

export const SignedOut: Story = {
	args: { state: { status: "signed-out", instanceHost: "hephaestus.build" } },
	play: async ({ canvas, args }) => {
		await userEvent.click(canvas.getByRole("button", { name: "Sign in" }));
		await expect(args.onOpenSettings).toHaveBeenCalledOnce();
	},
};

export const ConsentRequired: Story = {
	args: { state: { status: "consent-required", webAppUrl: WEB_APP } },
};

/** The tab shows no pull request, merge request or issue. */
export const NotWork: Story = { args: { state: { status: "unsupported-page" } } };

export const NoWorkspace: Story = {
	args: { state: { status: "no-workspace", siteOrigin: "https://gitlab.example.test" } },
};

export const NotFound: Story = {
	args: { state: { status: "not-found", workLabel: "HephaestusTest/lifecycle-validation #42" } },
};

export const ChooseWorkspace: Story = {
	args: {
		state: { status: "choose-workspace", workLabel: "HephaestusTest/lifecycle-validation #42" },
	},
};

/** Chrome lets the reader narrow the side panel; nothing scrolls sideways at 320px. */
export const Narrow: Story = {
	args: { state: STARTED, conversation: TALKING },
	parameters: { reflow: true },
	play: async ({ canvasElement }) => {
		await expectNoHorizontalOverflow(canvasElement);
	},
};

/** Starting a replacement conversation failed; the current transcript stays readable. */
export const NewConversationFailed: Story = {
	args: {
		state: STARTED,
		conversation: TALKING,
		newConversation: { status: "error", message: "The extension did not answer." },
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText("The extension did not answer.")).toBeVisible();
		await expect(canvas.getByText(/The description says/u)).toBeVisible();
	},
};

/** A long reply remains in the transcript while the composer stays available. */
export const LongReply: Story = {
	args: {
		state: STARTED,
		conversation: {
			...TALKING,
			messages: [
				...MESSAGES.slice(0, 1),
				{
					id: "1e0b4a2f-2c3d-4e6f-9071-8293a4b5c6d7",
					role: "assistant",
					parts: [
						{
							type: "text",
							text: Array.from(
								{ length: 30 },
								(_, index) =>
									`Paragraph ${index + 1}: Keep the change focused and record how you verified it.`,
							).join("\n\n"),
						},
					],
				},
			],
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("textbox", { name: "Message Heph" })).toBeVisible();
	},
};
