import type { Meta, StoryObj } from "@storybook/react";
import { fn } from "storybook/test";

import type { ChatMessageVote } from "@/api/types.gen";
import type { ChatMessage } from "@/lib/types";
import { STORY_NOW } from "@/stories/story-clock";

import { Chat } from "./Chat";

const meta = {
	component: Chat,
	parameters: { layout: "fullscreen" },
	tags: ["autodocs"],
	argTypes: {
		status: {
			control: "select",
			options: ["submitted", "streaming", "ready", "error"],
		},
	},
	args: {
		messages: [],
		votes: [],
		status: "ready",
		readonly: false,
		isAtBottom: true,
		attachments: [],
		onMessageSubmit: fn(),
		onStop: fn(),
		attachmentUpload: { onFileUpload: fn(async () => []), onAttachmentsChange: fn() },
		onMessageEdit: fn(),
		onCopy: fn(),
		onVote: fn(),
		scrollToBottom: fn(),
		inputPlaceholder: "Ask me anything...",
	},
} satisfies Meta<typeof Chat>;

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
	{
		id: "msg-5",
		role: "user",
		parts: [
			{
				type: "text",
				text: "Perfect! One more thing - could you update the poem to mention that she's starting 3rd grade soon? I think that would make it even more special.",
			},
		],
	},
	{
		id: "msg-6",
		role: "assistant",
		parts: [
			{
				type: "text",
				text: "What a great idea! Adding that milestone will make the poem even more personal and meaningful. Let me update it to celebrate both her birthday and this exciting new chapter.",
			},
			{
				type: "text",
				text: `Updated document: Birthday Poem for Emma\n\nEight Candles Bright

Today you turn eight, our shining star,
With dreams that travel oh so far.
Like unicorns with silky manes,
Dancing through the rainbow lanes.

Third grade awaits with books to read,
New friends to meet, new goals to lead.
Your laughter sparkles, pure and true,
A magic only found in you.

Eight years of joy, eight years of light,
Making every day so bright.
Growing bigger, growing smart,
With such a loving, caring heart.

So blow your candles, make a wish,
For all the dreams upon your list.
Our little unicorn so dear,
We celebrate another year!

Happy 8th Birthday! 🦄🌈📚`,
			},
		],
	},
	{
		id: "msg-7",
		role: "user",
		parts: [
			{
				type: "text",
				text: "Wonderful! One last request - can you help me create a simple shopping list for her party? We're having about 10 kids over.",
			},
		],
	},
	{
		id: "msg-8",
		role: "assistant",
		parts: [
			{
				type: "text",
				text: "Of course! Let me create a practical shopping list for a fun 8th birthday party with 10 kids. I'll include everything from decorations to food and party favors.",
			},
			{
				type: "text",
				text: `Created document: Birthday Party Shopping List\n\nEmma's 8th Birthday Party Shopping List
🦄 For 10 kids + family 🌈

## DECORATIONS
- [ ] Unicorn/rainbow themed tablecloth
- [ ] Colorful balloons (pink, purple, blue, rainbow)
- [ ] Birthday banner
- [ ] Paper plates and cups (unicorn theme)
- [ ] Napkins
- [ ] Plastic forks and spoons

## FOOD & DRINKS
- [ ] Birthday cake (or ingredients to make one)
- [ ] Juice boxes or punch
- [ ] Pizza (2-3 large pizzas)
- [ ] Fresh fruit (strawberries, grapes, watermelon)
- [ ] Veggie tray with ranch dip
- [ ] Chips and pretzels
- [ ] Ice cream (vanilla and chocolate)

## PARTY ACTIVITIES
- [ ] Party games supplies
- [ ] Craft materials (if doing a craft activity)
- [ ] Music playlist ready

## PARTY FAVORS
- [ ] Small gift bags
- [ ] Stickers (unicorn/rainbow theme)
- [ ] Small toys or trinkets
- [ ] Candy or small treats

## DON'T FORGET
- [ ] Candles (number 8!)
- [ ] Lighter/matches
- [ ] Camera for photos
- [ ] Thank you cards

**Budget estimate: $80-120 for everything!** 🎉`,
			},
		],
	},
	{
		id: "msg-9",
		role: "user",
		parts: [
			{
				type: "text",
				text: "This is so helpful! Thank you for making Emma's birthday planning so much easier. You've created everything I need! 🎉",
			},
		],
	},
	{
		id: "msg-10",
		role: "assistant",
		parts: [
			{
				type: "text",
				text: "You're so welcome! I'm thrilled I could help make Emma's 8th birthday extra special. She's going to love the personalized poem, and it sounds like you have everything planned for a magical celebration. I hope she has the most wonderful day filled with unicorns, rainbows, and lots of birthday joy! 🦄🌈🎂✨",
			},
		],
	},
];

const CONVERSATION_VOTES: ChatMessageVote[] = [
	{
		messageId: "msg-2",
		isUpvoted: true,
		updatedAt: new Date(STORY_NOW),
	},
	{
		messageId: "msg-6",
		isUpvoted: true,
		updatedAt: new Date(STORY_NOW),
	},
	{
		messageId: "msg-8",
		isUpvoted: true,
		updatedAt: new Date(STORY_NOW),
	},
];

const SAMPLE_ATTACHMENTS = [
	{
		name: "party-requirements.md",
		url: "https://example.com/party-requirements.md",
		contentType: "text/markdown",
	},
	{
		name: "budget-constraints.pdf",
		url: "https://example.com/budget.pdf",
		contentType: "application/pdf",
	},
];

export const FullConversationDemo: Story = {
	args: {
		messages: CONVERSATION_MESSAGES,
		votes: CONVERSATION_VOTES,
		attachments: [],
	},
};

export const EmptyState: Story = {
	args: {
		messages: [],
		attachments: [],
	},
};

export const WithPendingAttachments: Story = {
	args: {
		messages: CONVERSATION_MESSAGES.slice(0, 2),
		attachments: SAMPLE_ATTACHMENTS,
	},
};

export const StreamingResponse: Story = {
	args: {
		messages: [
			...CONVERSATION_MESSAGES.slice(0, 3),
			{
				id: "msg-streaming",
				role: "assistant",
				parts: [
					{
						type: "text",
						text: "I'm crafting a personalized birthday card message that will capture your daughter's special day...",
					},
				],
			},
		],
		status: "streaming",
	},
};

export const ReadOnlyArchive: Story = {
	args: {
		messages: CONVERSATION_MESSAGES,
		votes: CONVERSATION_VOTES,
		readonly: true,
	},
};

export const WithoutAttachments: Story = {
	args: {
		messages: CONVERSATION_MESSAGES.slice(0, 4),
		attachmentUpload: undefined,
		inputPlaceholder: "Send a message (file attachments disabled)...",
	},
};

export const ErrorState: Story = {
	args: {
		messages: CONVERSATION_MESSAGES.slice(0, 3),
		status: "error",
		onReload: fn(),
	},
};
