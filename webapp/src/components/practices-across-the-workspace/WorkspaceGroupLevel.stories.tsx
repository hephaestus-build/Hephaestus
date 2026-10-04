import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, userEvent, within } from "storybook/test";

import { DetailDrawerStack } from "@/components/layout/detail-drawer/DetailDrawerStack";
import { withPageBehind } from "@/stories/decorators";
import { expectSettledVisible, settledDrawerPanel } from "@/stories/overlay";
import {
	MANY_PRACTICES,
	PACKAGING_GROUP,
	WITHHELD,
} from "@/stories/practices-across-the-workspace-story-data";
import { expectNoPanelOverflow } from "@/stories/reflow";
import { Stateful } from "@/stories/stateful";

import { WorkspaceGroupLevel } from "./WorkspaceGroupLevel";

const CONTEXT = {
	readerCounted: true,
	developersWithAStanding: 28,
	minimumOthers: 3,
} as const;

/**
 * A practice group's practices over Practices across the workspace. The level has no page of its
 * own, so every story mounts a real drawer over a page.
 */
const meta = {
	component: WorkspaceGroupLevel,
	parameters: { layout: "fullscreen" },
	decorators: [withPageBehind],
	args: {
		path: { behind: [{ label: "Practices across the workspace", depth: 0 }], onClose: fn() },
		state: { status: "ready", group: PACKAGING_GROUP, context: CONTEXT },
		onGoToProfile: fn(),
		onGoToPractice: fn(),
	},
	argTypes: { path: { control: false } },
	render: (args) => (
		<Stateful initial={[{ kind: "practice-group", id: PACKAGING_GROUP.groupSlug }]}>
			{(stack, setStack) => (
				<DetailDrawerStack
					stack={stack}
					size="detailWide"
					onClose={(depth) => setStack(stack.slice(0, depth))}
				>
					{(_entry, level) => (
						<WorkspaceGroupLevel
							{...args}
							nested={level.nested}
							path={{
								behind: args.path.behind,
								onClose: (depth) => {
									args.path.onClose(depth);
									setStack(stack.slice(0, depth));
								},
							}}
						/>
					)}
				</DetailDrawerStack>
			)}
		</Stateful>
	),
	tags: ["autodocs"],
} satisfies Meta<typeof WorkspaceGroupLevel>;

export default meta;
type Story = StoryObj<typeof meta>;

/**
 * The head carries the crumb, the group and its split with the You marker, and the way to the group
 * in the reader's profile. The reader's own standing and trend are there, not here.
 */
export const Default: Story = {
	play: async ({ args }) => {
		const panel = await settledDrawerPanel();
		const level = within(panel);
		await expectSettledVisible(level.getByRole("heading", { name: "Packaging work for review" }));
		// The head's bar comes first; a practice that splits as its group does repeats its words.
		await expect(
			level.getAllByRole("img", {
				name: "28 developers with a current standing in this workspace: 7 Needs attention, 6 Mixed feedback, 8 Going well, 7 none yet. The You marker is on Needs attention.",
			})[0],
		).toBeVisible();
		// No standing badge, no trend and no window: this level is the workspace, not the profile.
		await expect(level.queryByRole("button", { name: "Needs attention" })).toBeNull();
		await expect(level.queryByText(/More positive recently/u)).toBeNull();
		await expect(level.queryByText(/^Last \d+ days$/u)).toBeNull();
		await expect(level.queryByText(/^You:/u)).toBeNull();
		await userEvent.click(
			level.getByRole("button", { name: "Go to your profile: Packaging work for review" }),
		);
		await expect(args.onGoToProfile).toHaveBeenCalledOnce();
		const table = within(
			level.getByRole("table", { name: "Practices of Packaging work for review" }),
		);
		await expect(table.getAllByRole("button", { name: /^View in your profile /u })).toHaveLength(5);
		await userEvent.click(
			table.getByRole("button", { name: "View in your profile Scope the change to one concern" }),
		);
		await expect(args.onGoToPractice).toHaveBeenCalledWith("scope-to-one-concern");
		await expect(table.getAllByText("Held back so no one can be singled out.")).toHaveLength(2);
		// Why a practice is held back more often than its group, once, over its practices.
		await expect(
			level.getByText(/The bar must also single no one out beside the group's bar\./u),
		).toBeVisible();
		// The page's legend sits above the practices it explains.
		await expect(level.getByRole("list", { name: "What the bars show" })).toBeVisible();
	},
};

/** A group held back: the head shows the empty track and its reason, and nothing of the reader. */
export const GroupHeldBack: Story = {
	args: {
		state: {
			status: "ready",
			group: { ...PACKAGING_GROUP, split: WITHHELD },
			context: CONTEXT,
		},
	},
	play: async () => {
		const level = within(await settledDrawerPanel());
		await expect(level.getAllByText("Held back so no one can be singled out.")).toHaveLength(3);
		await expect(level.queryByText(/^You:/u)).toBeNull();
		await expect(
			level.getByRole("button", { name: "Go to your profile: Packaging work for review" }),
		).toBeVisible();
	},
};

/** A reader with no current standing is in no count, so no part carries the You marker. */
export const ReaderNotCounted: Story = {
	args: {
		state: {
			status: "ready",
			group: PACKAGING_GROUP,
			context: { ...CONTEXT, readerCounted: false },
		},
	},
	play: async () => {
		const level = within(await settledDrawerPanel());
		// The head's bar comes first; a practice that splits as its group does repeats its words.
		await expect(
			level.getAllByRole("img", {
				name: "28 developers with a current standing in this workspace: 7 Needs attention, 6 Mixed feedback, 8 Going well, 7 none yet.",
			})[0],
		).toBeVisible();
		await expect(level.queryByText(/^You:/u)).toBeNull();
	},
};

/**
 * More practices than a page: the table keeps its full height and the level's body scrolls, so the
 * last rows and the way to the rest stay in reach.
 */
export const ManyPractices: Story = {
	args: {
		state: {
			status: "ready",
			group: { ...PACKAGING_GROUP, practices: MANY_PRACTICES },
			context: CONTEXT,
		},
	},
	play: async () => {
		const level = within(await settledDrawerPanel());
		const table = level.getByRole("table", { name: "Practices of Packaging work for review" });
		const frame = table.closest(".rounded-xl");
		if (!(frame instanceof HTMLElement)) {
			throw new Error("The table sits in its frame");
		}
		// The frame clips what overflows it, so it must be as tall as its rows.
		await expect(frame.scrollHeight).toBeLessThanOrEqual(frame.clientHeight + 1);
		const more = level.getByRole("button", { name: "Show more practices" });
		more.scrollIntoView();
		await userEvent.click(more);
		await expect(
			within(table).getAllByRole("button", { name: /^View in your profile /u }),
		).toHaveLength(24);
		await expect(frame.scrollHeight).toBeLessThanOrEqual(frame.clientHeight + 1);
	},
};

export const Loading: Story = {
	args: { state: { status: "loading" } },
	play: async () => {
		await settledDrawerPanel();
		await expect(screen.getByRole("table")).toHaveAttribute("aria-busy", "true");
	},
};

export const NoPractices: Story = {
	args: {
		state: { status: "ready", group: { ...PACKAGING_GROUP, practices: [] }, context: CONTEXT },
	},
	play: async () => {
		const level = within(await settledDrawerPanel());
		await expect(level.getByText("No practices yet")).toBeVisible();
		await expect(level.queryByText(/^Each bar counts developers/u)).toBeNull();
	},
};

/** A group by a slug the page does not list: a hand typed or stale address. */
export const UnknownGroup: Story = {
	args: { state: { status: "missing" } },
	play: async () => {
		const level = within(await settledDrawerPanel());
		await expect(level.getByText("We could not find this practice group")).toBeVisible();
	},
};

/** The crumb back to the page closes the level through the path the host hands it. */
export const ClosesToThePage: Story = {
	play: async ({ args }) => {
		const level = within(await settledDrawerPanel());
		await userEvent.click(level.getByRole("button", { name: /Practices across the workspace/u }));
		await expect(args.path.onClose).toHaveBeenCalledWith(0);
	},
};

export const Reflow: Story = {
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	play: async () => {
		await expectNoPanelOverflow(await settledDrawerPanel());
	},
};
