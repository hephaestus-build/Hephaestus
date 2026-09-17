import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen } from "storybook/test";

import type { ChatMessageVote } from "@/api/types.gen";
import type { ChatMessage } from "@/lib/types";
import { expectDismissed, expectSettledVisible } from "@/stories/overlay";
import { STORY_NOW } from "@/stories/story-clock";

import { Chat } from "./Chat";
import { Copilot } from "./Copilot";

const meta: Meta<typeof Copilot> = {
	component: Copilot,
	parameters: { layout: "fullscreen" },
	tags: ["autodocs"],
} satisfies Meta<typeof Copilot>;

export default meta;
type Story = StoryObj<typeof meta>;

const CONVERSATION_MESSAGES: ChatMessage[] = [
	{
		id: "msg-1",
		role: "user",
		parts: [
			{
				type: "text",
				text: "I need help writing a poem for my daughter's birthday. She's turning 8 and loves unicorns and rainbows.",
			},
		],
	},
	{
		id: "msg-2",
		role: "assistant",
		parts: [
			{
				type: "text",
				text: "What a special milestone! I'd love to help you create a magical birthday poem for your daughter. Let me craft something that captures her love for unicorns and rainbows.",
			},
			{
				type: "text",
				text: `Created document: Birthday Poem for Emma\n\nEight Candles Bright

Today you turn eight, our shining star,
With dreams that travel oh so far.
Like unicorns with silky manes,
Dancing through the rainbow lanes.

Your laughter sparkles, pure and true,
A magic only found in you.
Eight years of joy, eight years of light,
Making every day so bright.

So blow your candles, make a wish,
For all the dreams upon your list.
Our little unicorn so dear,
We celebrate another year!

Happy 8th Birthday! 🦄🌈`,
			},
		],
	},
	{
		id: "msg-3",
		role: "user",
		parts: [
			{
				type: "text",
				text: "This is beautiful! Could you also create a simple birthday card message that I can write inside her card?",
			},
		],
	},
	{
		id: "msg-4",
		role: "assistant",
		parts: [
			{
				type: "text",
				text: "Absolutely! Let me create a sweet and simple birthday message that would be perfect for the inside of her birthday card.",
			},
			{
				type: "text",
				text: `Created document: Birthday Card Message\n\nDear Emma,

Happy 8th Birthday to our amazing little girl!

You bring so much joy and magic into our lives every single day. Watching you grow into such a kind, creative, and wonderful person has been the greatest gift.

May this new year be filled with unicorn adventures, rainbow discoveries, and all the happiness your heart can hold.

We love you to the moon and back!

With all our love,
Mom & Dad 💕

P.S. Don't forget to make a special wish when you blow out your candles! 🎂✨`,
			},
		],
	},
];

const CONVERSATION_VOTES = [
	{ messageId: "msg-2", isUpvoted: true, updatedAt: new Date(STORY_NOW) },
	{ messageId: "msg-4", isUpvoted: true, updatedAt: new Date(STORY_NOW) },
] satisfies ChatMessageVote[];

function CopilotPreview({
	messages,
	votes,
	inputPlaceholder,
}: {
	messages: ChatMessage[];
	votes: ChatMessageVote[];
	inputPlaceholder: string;
}) {
	return (
		<div className="relative h-screen w-full bg-background">
			<main className="p-8">
				<h1 className="text-2xl font-bold">Workspace overview</h1>
				<p className="mt-2 text-muted-foreground">Review recent activity and team progress.</p>
			</main>
			<Copilot onNewChat={fn()} onOpenFullChat={fn()}>
				<Chat
					messages={messages}
					votes={votes}
					status="ready"
					attachments={[]}
					onMessageSubmit={fn()}
					onStop={fn()}
					onFileUpload={fn()}
					onAttachmentsChange={fn()}
					onMessageEdit={fn()}
					onCopy={fn()}
					onVote={fn()}
					scrollToBottom={fn()}
					inputPlaceholder={inputPlaceholder}
					className="h-full max-h-none"
				/>
			</Copilot>
		</div>
	);
}

export const Default: Story = {
	render: () => <CopilotPreview messages={[]} votes={[]} inputPlaceholder="Ask me anything…" />,
	play: async ({ canvas }) => {
		// Button sizes a child svg to 16px unless a `size-*` class says otherwise; the mark fills the pill.
		const launcher = canvas.getByRole("button", { name: "Open Heph, AI mentor" });
		const mark = launcher.querySelector("svg");
		await expect(mark).not.toBeNull();
		await expect(mark?.getBoundingClientRect().width).toBe(56);
	},
};

export const WithConversation: Story = {
	render: () => (
		<CopilotPreview
			messages={CONVERSATION_MESSAGES}
			votes={CONVERSATION_VOTES}
			inputPlaceholder="Continue the conversation…"
		/>
	),
};

export const Opened: Story = {
	render: WithConversation.render,
	play: async ({ canvas, userEvent }) => {
		const launcher = canvas.getByRole("button", { name: "Open Heph, AI mentor" });
		await userEvent.click(launcher);
		const panel = await screen.findByRole("dialog", { name: /Heph/u });
		await expectSettledVisible(panel);
		await expect(panel.contains(document.activeElement)).toBe(true);
		await expect(getComputedStyle(document.body).overflow).toBe("hidden");
		await userEvent.keyboard("{Escape}");
		await expectDismissed();
		await expect(getComputedStyle(document.body).overflow).not.toBe("hidden");
		await expect(launcher).toHaveFocus();
	},
};
