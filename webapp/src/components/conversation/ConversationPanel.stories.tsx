import type { Meta, StoryObj } from "@storybook/react-vite";
import { useState } from "react";
import { expect, fn, screen, waitFor, within } from "storybook/test";

import { RemovableToken } from "@/components/common/RemovableToken";
import { Button } from "@/components/ui/button";
import { expectDismissed, expectSettledVisible } from "@/stories/overlay";

import { ConversationComposer } from "./ConversationComposer";
import { ConversationMessage } from "./ConversationMessage";
import { ConversationPanel, type ConversationPanelProps } from "./ConversationPanel";
import { ConversationTimeline } from "./ConversationTimeline";
import {
	CONVERSATION,
	DEVELOPER,
	ready,
	SLACK_ANSWER,
	SLACK_QUESTION,
	WORKSPACE_SLUG,
} from "./fixtures";

/** Long enough to scroll in the panel, so the jump to the end has somewhere to go. */
const LONG_CONVERSATION = [
	...CONVERSATION.map((entry) => ({
		...entry,
		id: `earlier-${entry.id}`,
		seq: entry.seq - 100,
		...(entry.turnId === undefined ? {} : { turnId: `earlier-${entry.turnId}` }),
		...(entry.requestId === undefined ? {} : { requestId: `earlier-${entry.requestId}` }),
	})),
	...CONVERSATION,
];

function threadTimeline() {
	return (
		<ConversationTimeline
			label="Thread"
			workspaceSlug={WORKSPACE_SLUG}
			state={ready({ entries: [{ ...SLACK_ANSWER, parentEntryId: SLACK_QUESTION.id }] })}
			developer={DEVELOPER}
			onCopy={fn()}
			lead={<ConversationMessage message={SLACK_QUESTION} developer={DEVELOPER} />}
			composer={
				<ConversationComposer label="Reply in thread" placeholder="Reply in thread" onSend={fn()} />
			}
		/>
	);
}

const conversation = (
	<ConversationTimeline
		label="Your conversation"
		workspaceSlug={WORKSPACE_SLUG}
		state={ready({ entries: LONG_CONVERSATION })}
		developer={DEVELOPER}
		onCopy={fn()}
		threads={{ panelId: "conversation-panel-thread", onOpen: fn() }}
		composer={
			<ConversationComposer
				label="Message Hephaestus"
				placeholder="Message Hephaestus"
				onSend={fn()}
				context={
					<RemovableToken
						label="From Practice profile"
						removeLabel="Do not send this page with your message"
						onRemove={fn()}
					/>
				}
			/>
		}
	/>
);

const meta = {
	component: ConversationPanel,
	parameters: { layout: "fullscreen" },
	tags: ["autodocs"],
	args: { onOpenInConversation: fn(), children: conversation },
	render: (args) => (
		<div className="relative h-screen w-full bg-background">
			<main className="p-8">
				<h1 className="text-2xl font-bold">Practice profile</h1>
				<p className="mt-2 text-muted-foreground">Review recent activity and team progress.</p>
			</main>
			<ConversationPanel {...args} />
		</div>
	),
} satisfies Meta<typeof ConversationPanel>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ canvas }) => {
		const launcher = canvas.getByRole("button", { name: "Ask Hephaestus" });
		const mark = launcher.querySelector("svg");
		await expect(mark).not.toBeNull();
		await expect(mark?.getBoundingClientRect().width).toBe(56);
	},
};

/** An answer landed while the panel was closed: a dot on the launcher, which also says so by name. */
export const Unread: Story = {
	args: { unread: true },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("button", { name: "Ask Hephaestus, new answer" })).toBeVisible();
	},
};

/** The same conversation as the Hephaestus page; Open full page takes it there. */
export const Open: Story = {
	play: async ({ args, canvas, userEvent }) => {
		const launcher = canvas.getByRole("button", { name: "Ask Hephaestus" });
		await userEvent.click(launcher);
		const panel = await screen.findByRole("dialog", { name: /Hephaestus/u });
		await expectSettledVisible(panel);
		await expect(getComputedStyle(document.body).overflow).toBe("hidden");
		await userEvent.click(screen.getByRole("button", { name: "Open full page" }));
		await expect(args.onOpenInConversation).toHaveBeenCalledOnce();
		await userEvent.keyboard("{Escape}");
		await expectDismissed();
		await expect(launcher).toHaveFocus();
	},
};

/** Opens a thread from inside the open panel, as a summary button in the conversation does. */
function OpensThread(args: ConversationPanelProps) {
	const [open, setOpen] = useState(false);
	return (
		<ConversationPanel
			{...args}
			thread={open ? { focusTitle: true, onBack: () => setOpen(false) } : undefined}
		>
			{open ? (
				args.children
			) : (
				<Button variant="link" onClick={() => setOpen(true)}>
					1 reply
				</Button>
			)}
		</ConversationPanel>
	);
}

/**
 * On a tablet the panel stays one column of at most 28rem. A thread replaces the conversation in it
 * rather than squeezing beside it, its title takes focus, and Back returns to the conversation.
 */
export const ThreadOnTablet: Story = {
	args: { children: threadTimeline() },
	parameters: { viewport: { defaultViewport: "tablet" } },
	render: (args) => <OpensThread {...args} />,
	play: async ({ canvas, userEvent }) => {
		await userEvent.click(canvas.getByRole("button", { name: "Ask Hephaestus" }));
		await userEvent.click(await screen.findByRole("button", { name: "1 reply" }));
		const panel = await screen.findByRole("dialog", { name: "Thread" });
		await expectSettledVisible(panel);
		await expect(panel.getBoundingClientRect().width).toBeLessThanOrEqual(28 * 16);
		await waitFor(async () => {
			await expect(within(panel).getByRole("heading", { name: "Thread" })).toHaveFocus();
		});
		await expect(within(panel).getByRole("textbox", { name: "Reply in thread" })).toBeVisible();
		await userEvent.click(within(panel).getByRole("button", { name: "Back to the conversation" }));
		await expect(await screen.findByRole("button", { name: "1 reply" })).toBeVisible();
	},
};

/** A thread is pushed over the conversation; Back returns to it. */
export const ThreadPushed: Story = {
	args: {
		thread: { onBack: fn(), focusTitle: false },
		children: threadTimeline(),
	},
	play: async ({ args, canvas, userEvent }) => {
		await userEvent.click(canvas.getByRole("button", { name: "Ask Hephaestus" }));
		const panel = await screen.findByRole("dialog", { name: "Thread" });
		await expectSettledVisible(panel);
		await userEvent.click(screen.getByRole("button", { name: "Back to the conversation" }));
		await expect(args.thread?.onBack).toHaveBeenCalledOnce();
	},
};

/**
 * Escape takes one level at a time, as a stack of detail drawers does: out of the thread to the
 * conversation, then out of the panel. Closing the whole panel from a thread would lose the
 * reader's place one press too early.
 */
export const EscapeInThread: Story = {
	args: { children: threadTimeline() },
	render: (args) => <OpensThread {...args} />,
	play: async ({ canvas, userEvent }) => {
		const launcher = canvas.getByRole("button", { name: "Ask Hephaestus" });
		await userEvent.click(launcher);
		await userEvent.click(await screen.findByRole("button", { name: "1 reply" }));
		await expectSettledVisible(await screen.findByRole("dialog", { name: "Thread" }));
		await userEvent.keyboard("{Escape}");
		await expect(await screen.findByRole("button", { name: "1 reply" })).toBeVisible();
		await expect(screen.getByRole("dialog", { name: /^Ask Hephaestus/u })).toBeVisible();
		await userEvent.keyboard("{Escape}");
		await expectDismissed();
		await expect(launcher).toHaveFocus();
	},
};
