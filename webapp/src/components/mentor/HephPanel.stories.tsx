import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen } from "storybook/test";

import { RemovableToken } from "@/components/common/RemovableToken";
import { expectDismissed, expectSettledVisible } from "@/stories/overlay";

import {
	CONVERSATION,
	DEVELOPER,
	ready,
	SLACK_ANSWER,
	SLACK_QUESTION,
	WORKSPACE_SLUG,
} from "./fixtures";
import { HephComposer } from "./HephComposer";
import { HephMessage } from "./HephMessage";
import { HephPanel } from "./HephPanel";
import { HephTimeline } from "./HephTimeline";

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

const conversation = (
	<HephTimeline
		label="Conversation with Heph"
		workspaceSlug={WORKSPACE_SLUG}
		state={ready({ entries: LONG_CONVERSATION })}
		developer={DEVELOPER}
		onCopy={fn()}
		threads={{ panelId: "heph-panel-thread", onOpen: fn() }}
		composer={
			<HephComposer
				label="Message Heph"
				placeholder="Message Heph"
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
	component: HephPanel,
	parameters: { layout: "fullscreen" },
	tags: ["autodocs"],
	args: { onOpenInMentor: fn(), children: conversation },
	render: (args) => (
		<div className="relative h-screen w-full bg-background">
			<main className="p-8">
				<h1 className="text-2xl font-bold">Practice profile</h1>
				<p className="mt-2 text-muted-foreground">Review recent activity and team progress.</p>
			</main>
			<HephPanel {...args} />
		</div>
	),
} satisfies Meta<typeof HephPanel>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ canvas }) => {
		const launcher = canvas.getByRole("button", { name: "Open Heph, AI mentor" });
		const mark = launcher.querySelector("svg");
		await expect(mark).not.toBeNull();
		await expect(mark?.getBoundingClientRect().width).toBe(56);
	},
};

/** An answer landed while the panel was closed: a dot on the launcher, which also says so by name. */
export const Unread: Story = {
	args: { unread: true },
	play: async ({ canvas }) => {
		await expect(
			canvas.getByRole("button", { name: "Open Heph, AI mentor, new answer" }),
		).toBeVisible();
	},
};

/** The same conversation as the Heph page; Open the Heph page takes it there. */
export const Open: Story = {
	play: async ({ args, canvas, userEvent }) => {
		const launcher = canvas.getByRole("button", { name: "Open Heph, AI mentor" });
		await userEvent.click(launcher);
		const panel = await screen.findByRole("dialog", { name: /Heph/u });
		await expectSettledVisible(panel);
		await expect(getComputedStyle(document.body).overflow).toBe("hidden");
		await userEvent.click(screen.getByRole("button", { name: "Open the Heph page" }));
		await expect(args.onOpenInMentor).toHaveBeenCalledOnce();
		await userEvent.keyboard("{Escape}");
		await expectDismissed();
		await expect(launcher).toHaveFocus();
	},
};

/** A thread is pushed over the conversation; Back returns to it. */
export const ThreadPushed: Story = {
	args: {
		thread: { onBack: fn(), focusTitle: false },
		children: (
			<HephTimeline
				label="Thread"
				workspaceSlug={WORKSPACE_SLUG}
				state={ready({ entries: [{ ...SLACK_ANSWER, parentEntryId: SLACK_QUESTION.id }] })}
				developer={DEVELOPER}
				onCopy={fn()}
				lead={<HephMessage message={SLACK_QUESTION} developer={DEVELOPER} />}
				composer={
					<HephComposer label="Reply in thread" placeholder="Reply in thread" onSend={fn()} />
				}
			/>
		),
	},
	play: async ({ args, canvas, userEvent }) => {
		await userEvent.click(canvas.getByRole("button", { name: "Open Heph, AI mentor" }));
		const panel = await screen.findByRole("dialog", { name: "Thread" });
		await expectSettledVisible(panel);
		await userEvent.click(screen.getByRole("button", { name: "Back to the conversation" }));
		await expect(args.thread?.onBack).toHaveBeenCalledOnce();
	},
};
