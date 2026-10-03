import { useChat } from "@ai-sdk/react";
import { createChat } from "@shadcn/helpers/ai-sdk";
import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn } from "storybook/test";

import { mentorTurn } from "@/lib/chat-validation";
import type { ChatMessage } from "@/lib/types";

import { Chat, type ChatProps } from "./Chat";
import {
	CONVERSATION,
	CONVERSATION_VOTES,
	REPLY_WITH_FEEDBACK,
	hephReply,
	userMessage,
} from "./fixtures";

const meta = {
	component: Chat,
	parameters: { layout: "fullscreen" },
	tags: ["autodocs"],
	decorators: [
		(Story) => (
			<div className="h-dvh">
				<Story />
			</div>
		),
	],
	args: {
		messages: CONVERSATION,
		votes: CONVERSATION_VOTES,
		turn: { kind: "ready" },
		onMessageSubmit: fn(),
		onStop: fn(),
		onMessageEdit: fn(),
		onCopy: fn(),
		onVote: fn(),
		onReload: fn(),
	},
} satisfies Meta<typeof Chat>;

export default meta;
type Story = StoryObj<typeof meta>;

/** A saved conversation: Heph's replies offer votes, and every turn can be copied. */
export const Default: Story = {
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("log")).toBeVisible();
		await expect(canvas.getByText("What made you pick the time-based cache?")).toBeVisible();
		await expect(canvas.getAllByRole("button", { name: "Good response" })).toHaveLength(3);
	},
};

export const Empty: Story = {
	args: { messages: [], votes: [] },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("How can I help you today?")).toBeVisible();
		await expect(canvas.getByRole("button", { name: "Send message" })).toBeDisabled();
	},
};

/** Before the reply shows words, Heph's place in the conversation says it is thinking. */
export const Thinking: Story = {
	args: {
		messages: [...CONVERSATION.slice(0, 4), userMessage("turn-5", "And my latest one?")],
		turn: { kind: "submitted", warmingUp: false },
	},
	play: async ({ args, canvas, userEvent }) => {
		await expect(canvas.getByRole("status")).toHaveTextContent("Thinking…");
		await userEvent.click(canvas.getByRole("button", { name: "Stop generating" }));
		await expect(args.onStop).toHaveBeenCalledOnce();
	},
};

/** A cold start says why the reply is slow rather than thinking for half a minute. */
export const WarmingUp: Story = {
	args: { ...Thinking.args, turn: { kind: "submitted", warmingUp: true } },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("status")).toHaveTextContent(
			"Getting ready. The first reply takes a little longer.",
		);
	},
};

/** Once the reply shows words, it replaces the status line. */
export const Streaming: Story = {
	args: {
		messages: [
			...CONVERSATION.slice(0, 5),
			{
				...hephReply("turn-6", "I looked at your latest pull request, **Cache the practice"),
				metadata: undefined,
			},
		],
		turn: { kind: "streaming", warmingUp: false },
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText(/I looked at your latest pull request/u)).toBeVisible();
		await expect(canvas.getByRole("status")).toBeEmptyDOMElement();
	},
};

/** Someone else's saved conversation, or one the reader's AI choice closed: nothing to write in. */
export const ReadOnly: Story = {
	args: { readonly: true },
	play: async ({ canvas }) => {
		await expect(canvas.queryByRole("textbox", { name: "Message" })).toBeNull();
	},
};

export const Failed: Story = {
	args: { messages: CONVERSATION.slice(0, 5), turn: { kind: "error", failure: "failed" } },
	play: async ({ args, canvas, userEvent }) => {
		await expect(canvas.getByText("Something went wrong")).toBeVisible();
		await userEvent.click(canvas.getByRole("button", { name: "Try again" }));
		await expect(args.onReload).toHaveBeenCalledOnce();
	},
};

export const Busy: Story = {
	args: { ...Failed.args, turn: { kind: "error", failure: "busy" } },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Heph is busy", { exact: true })).toBeVisible();
		await expect(canvas.getByRole("button", { name: "Try again" })).toBeEnabled();
	},
};

export const BusyDark: Story = {
	...Busy,
	globals: { theme: "dark" },
};

/** A reply saved as interrupted can be tried again when the conversation is reopened. */
export const InterruptedReplyReopened: Story = {
	args: {
		messages: [
			CONVERSATION[0] ?? userMessage("turn-1", "Why?"),
			{ id: "turn-2", role: "assistant", parts: [], metadata: { status: "interrupted" } },
		],
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("button", { name: "Try again" })).toBeVisible();
		await expect(canvas.getByText(/interrupted before it finished/u)).toBeVisible();
	},
};

// A scripted stream through the real `useChat`: whatever the reader sends, Heph answers with this.
const LIVE_TRANSPORT = createChat<ChatMessage>().transport({
	delayMs: 5,
	fallback: ({ writer }) => {
		writer.text("I looked at your latest pull request, **Cache the practice catalog**.");
		writer.data({
			type: "data-observation",
			id: "live-link",
			data: {
				observationId: "3f0c2b4e-8a1d-4c6e-9b7f-2d5e8a1c4b6f",
				text: "Your description names the decision but not why it beat invalidating on write.",
			},
		});
	},
});

function LiveChat(args: ChatProps) {
	const { messages, sendMessage, status, stop } = useChat<ChatMessage>({
		transport: LIVE_TRANSPORT,
	});
	return (
		<Chat
			{...args}
			messages={messages}
			turn={mentorTurn(status, undefined)}
			onMessageSubmit={(text) => {
				args.onMessageSubmit(text);
				void sendMessage({ text });
			}}
			onStop={() => {
				args.onStop();
				void stop();
			}}
		/>
	);
}

/** A whole turn through `useChat`: the reply streams in, then offers its actions. */
export const LiveReply: Story = {
	args: { messages: [], votes: [] },
	render: (args) => <LiveChat {...args} />,
	play: async ({ args, canvas, userEvent }) => {
		await userEvent.type(
			canvas.getByRole("textbox", { name: "Message" }),
			"What about my latest pull request?{Enter}",
		);
		await expect(args.onMessageSubmit).toHaveBeenCalledOnce();
		await expect(
			await canvas.findByText(/not why it beat invalidating on write/u, {}, { timeout: 5000 }),
		).toBeVisible();
		await expect(
			await canvas.findAllByRole("button", { name: "Good response" }, { timeout: 5000 }),
		).toHaveLength(1);
		await expect(canvas.getByRole("button", { name: "Send message" })).toBeDisabled();
	},
};

/** The reply that links feedback, kept for its own snapshot at the reflow width. */
export const Reflow: Story = {
	args: { messages: [CONVERSATION[4] ?? userMessage("turn-5", "And?"), REPLY_WITH_FEEDBACK] },
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("the decision")).toBeVisible();
	},
};
