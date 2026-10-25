import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, userEvent, within } from "storybook/test";

import { DetailDrawerStack } from "@/components/layout/detail-drawer/DetailDrawerStack";
import { withPageBehind } from "@/stories/decorators";
import { expectSettledVisible, settledDrawerPanel } from "@/stories/overlay";
import {
	EARLIER_REVIEWS_WORKSPACE,
	MANY_PRACTICES,
	PACKAGING_GROUP,
	split,
} from "@/stories/practices-across-the-workspace-story-data";
import { expectNoPanelOverflow } from "@/stories/reflow";
import { Stateful } from "@/stories/stateful";

import { ACROSS_THE_WORKSPACE, GROUPS_LOAD_ERROR } from "./across-workspace-copy";
import { CAME_FROM_YOUR_PROFILE } from "./WorkspaceComparisonTable";
import { WorkspaceGroupLevel } from "./WorkspaceGroupLevel";

const EARLIER_PACKAGING = EARLIER_REVIEWS_WORKSPACE.groups.find(
	(each) => each.groupSlug === PACKAGING_GROUP.groupSlug,
);

/**
 * A practice group's practices over Practices across the workspace. The level has no page of its
 * own, so every story mounts a real drawer over a page.
 */
const meta = {
	component: WorkspaceGroupLevel,
	parameters: { layout: "fullscreen" },
	decorators: [withPageBehind],
	args: {
		path: { behind: [{ label: ACROSS_THE_WORKSPACE, depth: 0 }], onClose: fn() },
		state: { status: "ready", group: PACKAGING_GROUP },
		onGoToPractice: fn(),
	},
	argTypes: { path: { control: false } },
	render: (args) => (
		<Stateful initial={[{ kind: "practice-group", id: PACKAGING_GROUP.groupSlug }]}>
			{(stack, setStack) => (
				<DetailDrawerStack stack={stack} onClose={(depth) => setStack(stack.slice(0, depth))}>
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

export const Default: Story = {
	play: async ({ args }) => {
		const level = within(await settledDrawerPanel());
		await expectSettledVisible(level.getByRole("heading", { name: "Packaging work for review" }));
		// The group's own split beside the title, with the reader marked.
		await expect(
			level.getAllByRole("img", {
				name: "28 developers in this workspace: 7 Needs attention, 6 Mixed feedback, 8 Going well, 7 none yet. You are in the Needs attention part.",
			}),
		).toHaveLength(1);
		const table = within(
			level.getByRole("table", { name: "Practices of Packaging work for review" }),
		);
		await expect(
			table.getAllByRole("button", { name: /^Open in your Practice profile /u }),
		).toHaveLength(5);
		await expect(
			table.getByRole("img", {
				name: "28 developers in this workspace: 9 Needs attention, 4 Mixed feedback, 8 Going well, 7 none yet. You are in the Needs attention part.",
			}),
		).toBeVisible();
		await userEvent.click(
			table.getByRole("button", {
				name: "Open in your Practice profile Scope the change to one concern",
			}),
		);
		await expect(args.onGoToPractice).toHaveBeenCalledWith("scope-to-one-concern");
		// Every standing is current, so nothing here is marked as earlier reviews.
		await expect(level.queryByRole("button", { name: /^Based on earlier reviews/u })).toBeNull();
	},
};

/**
 * The reader's group standing and two of their practices rest on earlier reviews. Each says so in
 * the words the Practice profile uses, criteria unknown where the reviews did not record them.
 */
export const EarlierReviews: Story = {
	args: {
		state:
			EARLIER_PACKAGING === undefined
				? { status: "missing" }
				: { status: "ready", group: EARLIER_PACKAGING },
	},
	play: async () => {
		const level = within(await settledDrawerPanel());
		await expect(
			level.getAllByRole("button", { name: "Based on earlier reviews, criteria unknown" }),
		).toHaveLength(2);
		await expect(level.getAllByRole("button", { name: "Based on earlier reviews" })).toHaveLength(
			1,
		);
	},
};

/** No developer has a standing in any practice yet: the reason shows once, not per row. */
export const NobodyYet: Story = {
	args: {
		state: {
			status: "ready",
			group: {
				...PACKAGING_GROUP,
				yourStanding: null,
				split: split(0, 0, 0, 0),
				practices: PACKAGING_GROUP.practices.map((one) => ({
					...one,
					yourStanding: null,
					split: split(0, 0, 0, 0),
				})),
			},
		},
	},
	play: async () => {
		const level = within(await settledDrawerPanel());
		await expect(level.getAllByText("No developer has a standing yet.")).toHaveLength(2);
	},
};

/**
 * More practices than the panel holds: the table keeps its full height and the level's body
 * scrolls, so the last rows stay in reach.
 */
export const ManyPractices: Story = {
	args: {
		state: { status: "ready", group: { ...PACKAGING_GROUP, practices: MANY_PRACTICES } },
	},
	play: async () => {
		const level = within(await settledDrawerPanel());
		const table = level.getByRole("table", { name: "Practices of Packaging work for review" });
		await expect(
			within(table).getAllByRole("button", { name: /^Open in your Practice profile /u }),
		).toHaveLength(24);
	},
};

export const Loading: Story = {
	args: { state: { status: "loading" } },
	play: async () => {
		await settledDrawerPanel();
		await expect(screen.getByRole("table")).toHaveAttribute("aria-busy", "true");
	},
};

export const LoadingDark: Story = {
	args: Loading.args,
	globals: { theme: "dark" },
};

const onRetryOverview = fn();

/** The overview failed: the level says so and retries, rather than closing over a stale address. */
export const LoadError: Story = {
	args: { state: { status: "error", error: new Error("Network down"), onRetry: onRetryOverview } },
	play: async () => {
		const level = within(await settledDrawerPanel());
		await expect(level.getByText(GROUPS_LOAD_ERROR)).toBeVisible();
		await userEvent.click(level.getByRole("button", { name: "Retry" }));
		await expect(onRetryOverview).toHaveBeenCalledOnce();
	},
};

export const NoPractices: Story = {
	args: { state: { status: "ready", group: { ...PACKAGING_GROUP, practices: [] } } },
	play: async () => {
		const level = within(await settledDrawerPanel());
		await expect(level.getByText("No practices yet")).toBeVisible();
	},
};

/** A slug the page does not list, such as a stale address. */
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
		await userEvent.click(level.getByRole("button", { name: /Across the workspace/u }));
		await expect(args.path.onClose).toHaveBeenCalledWith(0);
	},
};

export const Reflow: Story = {
	args: EarlierReviews.args,
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	play: async () => {
		await expectNoPanelOverflow(await settledDrawerPanel());
	},
};

/**
 * The practice the reader came from in their Practice profile keeps the bar on its leading edge,
 * and says so to a screen reader: `aria-current` on its row and a hidden sentence after its name.
 */
export const MarkedPractice: Story = {
	args: { focusPracticeSlug: PACKAGING_GROUP.practices[0]?.practiceSlug },
	play: async () => {
		const marked = within(await settledDrawerPanel())
			.getAllByRole("row")
			.filter((row) => row.getAttribute("aria-current") === "true");
		await expect(marked).toHaveLength(1);
		const [row] = marked;
		await expect(row).toHaveTextContent(CAME_FROM_YOUR_PROFILE);
		await expect(row).not.toHaveAttribute("data-state", "open");
	},
};
