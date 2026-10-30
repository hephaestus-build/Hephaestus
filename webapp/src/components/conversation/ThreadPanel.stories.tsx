import type { Meta, StoryObj } from "@storybook/react-vite";
import { cn } from "cn";
import { useState } from "react";
import { expect, fn, screen, waitFor, within } from "storybook/test";

import { Button } from "@/components/ui/button";
import { expectSettledVisible } from "@/stories/overlay";

import { ConversationComposer } from "./ConversationComposer";
import { ConversationMessage } from "./ConversationMessage";
import { ConversationTimeline } from "./ConversationTimeline";
import { ConversationTimelineSkeleton } from "./ConversationTimelineSkeleton";
import { DEVELOPER, entry, ready, SLACK_ANSWER, SLACK_QUESTION, WORKSPACE_SLUG } from "./fixtures";
import { pixelsPerRem, ThreadGone, ThreadPanel, type ThreadPanelProps } from "./ThreadPanel";

const REPLY = { ...SLACK_ANSWER, parentEntryId: SLACK_QUESTION.id };

const OWN_REPLY = entry({
	id: "e1000000-0000-4000-8000-000000000031",
	kind: "USER",
	text: "Here is the detail I may want to remove later.",
	parentEntryId: SLACK_QUESTION.id,
});

function thread(focusOnMount = false) {
	return (
		<ConversationTimeline
			label="Thread"
			workspaceSlug={WORKSPACE_SLUG}
			state={ready({ entries: [REPLY] })}
			developer={DEVELOPER}
			onCopy={fn()}
			lead={<ConversationMessage message={SLACK_QUESTION} developer={DEVELOPER} />}
			composer={
				<ConversationComposer
					label="Reply in thread"
					placeholder="Reply in thread"
					focusOnMount={focusOnMount}
					onSend={fn()}
				/>
			}
		/>
	);
}

function OpensFromButton(args: ThreadPanelProps) {
	const [open, setOpen] = useState(false);
	return (
		<div className="flex h-dvh">
			<div className="flex-1 p-6">
				<Button variant="link" onClick={() => setOpen(true)}>
					3 replies
				</Button>
			</div>
			{open && (
				<ThreadPanel
					{...args}
					focusHeading
					onClose={() => {
						args.onClose();
						setOpen(false);
					}}
				/>
			)}
		</div>
	);
}

const meta = {
	component: ThreadPanel,
	parameters: { layout: "fullscreen" },
	tags: ["autodocs"],
	// The page measures the region its conversation and thread share: 64rem, 1024px at 16px text.
	args: { id: "thread-panel", availableRem: 64, onClose: fn(), children: thread() },
	render: (args) => (
		<div className="flex h-dvh">
			<div className="flex-1 p-6 text-muted-foreground">
				The conversation stays beside the thread.
			</div>
			<ThreadPanel {...args} />
		</div>
	),
} satisfies Meta<typeof ThreadPanel>;

export default meta;
type Story = StoryObj<typeof meta>;

/** With room to spare, a column beside the conversation: not a dialog, so the conversation stays usable. */
export const Default: Story = {
	play: async ({ args, canvas, userEvent }) => {
		const panel = canvas.getByRole("complementary", { name: "Thread" });
		await expect(panel).toHaveAttribute("id", "thread-panel");
		await userEvent.click(canvas.getByRole("textbox", { name: "Reply in thread" }));
		await userEvent.keyboard("{Escape}");
		await expect(args.onClose).toHaveBeenCalledOnce();
	},
};

/**
 * The heading takes focus as the thread opens, and shows the same ring a button does, so a
 * keyboard reader sees where they are. Escape hands focus back to the control that opened the
 * thread.
 */
export const ReturnsFocus: Story = {
	render: (args) => <OpensFromButton {...args} />,
	play: async ({ args, canvas, userEvent }) => {
		const opener = canvas.getByRole("button", { name: "3 replies" });
		opener.focus();
		await userEvent.keyboard("{Enter}");
		const heading = canvas.getByRole("heading", { name: "Thread" });
		await expect(heading).toHaveFocus();
		await expect(heading.matches(":focus-visible")).toBe(true);
		await expect(getComputedStyle(heading).boxShadow).not.toBe("none");
		await userEvent.keyboard("{Escape}");
		await expect(args.onClose).toHaveBeenCalledOnce();
		await expect(opener).toHaveFocus();
	},
};

/** On a phone there is no room beside the conversation, so the thread is a drawer over it. */
export const Mobile: Story = {
	// 375px at 16px text.
	args: { availableRem: 23.4375 },
	parameters: { viewport: { defaultViewport: "mobile" } },
	play: async () => {
		const panel = await screen.findByRole("dialog", { name: "Thread" });
		await expectSettledVisible(panel);
	},
};

/** Before the page has measured its region, the thread does not guess that a column fits. */
export const NotMeasuredYet: Story = {
	args: { availableRem: undefined },
	play: async () => {
		await expectSettledVisible(await screen.findByRole("dialog", { name: "Thread" }));
		await expect(screen.queryByRole("complementary", { name: "Thread" })).toBeNull();
	},
};

/**
 * Regions the page measures on an 820px and a 1000px window at 16px text, with the sidebar open or
 * collapsed to its icons. Only a region that leaves the conversation 24rem beside the 26rem column
 * gets the column, whatever the window: this canvas is 1440px wide. A drawer still closes with
 * Close, and leaves as every drawer does before the page drops the thread: its exit, a fade under
 * reduced motion, is still running when Close returns.
 */
const opensAsDrawer: Story["play"] = async ({ args, userEvent }) => {
	const panel = await screen.findByRole("dialog", { name: "Thread" });
	await expectSettledVisible(panel);
	await expect(screen.queryByRole("complementary", { name: "Thread" })).toBeNull();
	await userEvent.click(within(panel).getByRole("button", { name: "Close thread" }));
	await expect(args.onClose).not.toHaveBeenCalled();
	await waitFor(async () => {
		await expect(args.onClose).toHaveBeenCalledOnce();
	});
	await expect(screen.queryByRole("dialog", { name: "Thread" })).toBeNull();
};

/** 820px, sidebar open: 564px (35.25rem) for both, a column would leave the conversation 148px. */
export const TabletSidebarOpen: Story = { args: { availableRem: 35.25 }, play: opensAsDrawer };

/** 820px, sidebar as icons: 772px (48.25rem), a column would leave the conversation 356px. */
export const TabletSidebarCollapsed: Story = { args: { availableRem: 48.25 }, play: opensAsDrawer };

/** 1000px, sidebar open: 744px (46.5rem), a column would leave the conversation 328px. */
export const LaptopSidebarOpen: Story = { args: { availableRem: 46.5 }, play: opensAsDrawer };

type PlayUser = Parameters<NonNullable<Story["play"]>>[0]["userEvent"];

/** Opens the drawer from its control, closes it with `close`, and expects focus back on the control. */
function returnsFocusFromDrawer(
	close: (panel: HTMLElement, userEvent: PlayUser) => Promise<void>,
): Story["play"] {
	return async ({ args, canvas, userEvent }) => {
		const opener = canvas.getByRole("button", { name: "3 replies" });
		await userEvent.click(opener);
		const panel = await screen.findByRole("dialog", { name: "Thread" });
		await expectSettledVisible(panel);
		await close(panel, userEvent);
		await waitFor(async () => {
			await expect(args.onClose).toHaveBeenCalledOnce();
		});
		await expect(screen.queryByRole("dialog", { name: "Thread" })).toBeNull();
		await waitFor(async () => {
			await expect(opener).toHaveFocus();
		});
	};
}

/** As a drawer, Close hands focus back to the control that opened the thread once the drawer has left. */
export const DrawerReturnsFocus: Story = {
	args: { availableRem: 46.5 },
	render: (args) => <OpensFromButton {...args} />,
	play: returnsFocusFromDrawer(async (panel, userEvent) => {
		await userEvent.click(within(panel).getByRole("button", { name: "Close thread" }));
	}),
};

/** A cached thread mounts its reply field focused and still closes back to its opener. */
export const DrawerReplyReturnsFocus: Story = {
	args: { availableRem: 46.5, children: thread(true) },
	render: (args) => <OpensFromButton {...args} />,
	play: async ({ args, canvas, userEvent }) => {
		const opener = canvas.getByRole("button", { name: "3 replies" });
		await userEvent.click(opener);
		const panel = await screen.findByRole("dialog", { name: "Thread" });
		await expectSettledVisible(panel);
		await expect(within(panel).getByRole("textbox", { name: "Reply in thread" })).toHaveFocus();
		await userEvent.click(within(panel).getByRole("button", { name: "Close thread" }));
		await waitFor(async () => {
			await expect(args.onClose).toHaveBeenCalledOnce();
		});
		await expect(screen.queryByRole("dialog", { name: "Thread" })).toBeNull();
		await waitFor(async () => {
			await expect(opener).toHaveFocus();
		});
	},
};

/** A thread opened directly has no opener; closing it does not focus an unrelated page control. */
export const DrawerColdLoadFocus: Story = {
	args: { availableRem: 46.5 },
	beforeEach: () => {
		if (document.activeElement instanceof HTMLElement) {
			document.activeElement.blur();
		}
	},
	render: (args) => (
		<div className="flex h-dvh">
			<Button variant="link">An unrelated page action</Button>
			<ThreadPanel {...args} />
		</div>
	),
	play: async ({ args, userEvent }) => {
		const panel = await screen.findByRole("dialog", { name: "Thread" });
		await expectSettledVisible(panel);
		await userEvent.click(within(panel).getByRole("button", { name: "Close thread" }));
		await waitFor(async () => {
			await expect(args.onClose).toHaveBeenCalledOnce();
		});
		await expect(screen.queryByRole("dialog", { name: "Thread" })).toBeNull();
		await waitFor(async () => {
			await expect(document.activeElement).toBe(document.body);
		});
	},
};

/** Escape closes the drawer the same way, and focus lands on the same control. */
export const DrawerEscapeReturnsFocus: Story = {
	args: { availableRem: 46.5 },
	render: (args) => <OpensFromButton {...args} />,
	play: returnsFocusFromDrawer(async (_panel, userEvent) => {
		await userEvent.keyboard("{Escape}");
	}),
};

/**
 * A region drawn `pixels` wide, converted to `rem` the way the page converts its measurement: at
 * the root font size the browser shows now.
 */
function DrawnRegion({
	className,
	pixels,
	...args
}: ThreadPanelProps & { className: string; pixels: number }) {
	return (
		<div className={cn("flex h-dvh", className)}>
			<div className="min-w-0 flex-1 p-6 text-muted-foreground">
				The conversation stays beside the thread.
			</div>
			<ThreadPanel {...args} availableRem={pixels / pixelsPerRem()} />
		</div>
	);
}

/** The conversation as drawn beside the column keeps at least its 24rem, at the text size now. */
const keepsConversationBeside: Story["play"] = async ({ canvas }) => {
	await expect(canvas.getByRole("complementary", { name: "Thread" })).toBeVisible();
	const conversation = canvas.getByText("The conversation stays beside the thread.");
	await expect(conversation.getBoundingClientRect().width).toBeGreaterThanOrEqual(
		24 * pixelsPerRem(),
	);
};

/** 1000px, sidebar collapsed to icons: 952px, so the conversation keeps 536px beside the column. */
export const LaptopSidebarCollapsed: Story = {
	render: (args) => <DrawnRegion {...args} className="w-[952px]" pixels={952} />,
	play: keepsConversationBeside,
};

/** The reader's browser shows text at 20px instead of the default 16px, for one story. */
function largerText() {
	const root = document.documentElement;
	root.style.fontSize = "20px";
	return () => {
		root.style.removeProperty("font-size");
	};
}

/**
 * The same 952px region with larger text: the 26rem column is 520px, and the 432px left over is
 * less than the conversation's 24rem, 480px. The thread opens over the conversation instead.
 */
export const LargerText: Story = {
	beforeEach: largerText,
	render: (args) => <DrawnRegion {...args} className="w-[952px]" pixels={952} />,
	play: opensAsDrawer,
};

/** With larger text, a region of 52rem, 1040px, again holds the column beside a 24rem conversation. */
export const LargerTextWithRoom: Story = {
	beforeEach: largerText,
	render: (args) => <DrawnRegion {...args} className="w-[1040px]" pixels={1040} />,
	play: keepsConversationBeside,
};

export const Loading: Story = {
	args: { children: <ConversationTimelineSkeleton rows={2} label="Loading the thread…" /> },
	play: async ({ canvas }) => {
		const panel = canvas.getByRole("complementary", { name: "Thread" });
		await expect(
			await within(panel).findByText("Loading the thread…", undefined, { timeout: 3000 }),
		).toBeVisible();
	},
};

/** No thread on that message is the developer's, such as from a wrong link. */
export const NotFound: Story = {
	args: { children: <ThreadGone /> },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("We could not find this thread")).toBeVisible();
	},
};

/** The message the thread replies to was deleted: its words go, and its replies stay with their own Delete. */
export const DeletedParent: Story = {
	args: {
		children: (
			<ConversationTimeline
				label="Thread"
				workspaceSlug={WORKSPACE_SLUG}
				state={ready({ entries: [REPLY, OWN_REPLY] })}
				developer={DEVELOPER}
				onCopy={fn()}
				actions={{ onVote: fn(), onDelete: fn(), onStop: fn(), onDiscardSend: fn() }}
				lead={
					<ConversationMessage
						message={{ ...SLACK_QUESTION, deleted: true, text: "" }}
						developer={DEVELOPER}
					/>
				}
			/>
		),
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText("This message was deleted.")).toBeVisible();
		await expect(canvas.getByText(OWN_REPLY.text)).toBeVisible();
		// The reply keeps its own Delete; the query fails on its own if it is gone.
		canvas.getByRole("button", { name: /^Delete message \(/u });
	},
};
