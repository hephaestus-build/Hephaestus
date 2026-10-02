import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, userEvent, within } from "storybook/test";

import { DetailDrawerStack } from "@/components/layout/detail-drawer/DetailDrawerStack";
import { withPageBehind } from "@/stories/decorators";
import { expectSettledVisible, settledDrawerPanel } from "@/stories/overlay";
import { PACKAGING_GROUP } from "@/stories/practices-across-the-workspace-story-data";
import { expectNoPanelOverflow } from "@/stories/reflow";
import { Stateful } from "@/stories/stateful";

import { WorkspaceGroupLevel } from "./WorkspaceGroupLevel";

const CONTEXT = {
	window: "DAYS_30",
	readerCounted: true,
	observedDevelopers: 28,
	minimumOthers: 5,
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
		workspaceSlug: "aet",
		state: { status: "ready", group: PACKAGING_GROUP, context: CONTEXT },
		onViewPractice: fn(),
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

/** The head carries the reader's standing and trend, the way to their group, and the group's split. */
export const Default: Story = {
	play: async ({ args }) => {
		const panel = await settledDrawerPanel();
		const level = within(panel);
		await expectSettledVisible(level.getByRole("heading", { name: "Packaging work for review" }));
		await expect(level.getByRole("button", { name: "Needs attention" })).toBeVisible();
		await expect(level.getByRole("button", { name: "More positive recently" })).toBeVisible();
		await expect(
			level.getByRole("link", {
				name: "Open the group Packaging work for review on your Practice profile",
			}),
		).toHaveAttribute("href", expect.stringContaining("practice-group%3Areview-ready-work"));
		const table = within(
			level.getByRole("table", { name: "Practices of Packaging work for review" }),
		);
		await expect(table.getAllByRole("button", { name: /^View practice /u })).toHaveLength(5);
		await userEvent.click(
			table.getByRole("button", { name: "View practice Scope the change to one concern" }),
		);
		await expect(args.onViewPractice).toHaveBeenCalledWith(
			"review-ready-work",
			"scope-to-one-concern",
		);
		await expect(table.getByRole("img", { name: /20 have a standing, 8 none yet/u })).toBeVisible();
		await expect(table.getByText("Held back: too few developers to compare yet.")).toBeVisible();
		await expect(level.queryByText(/See practices/u)).toBeNull();
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
		await expect(level.getByText("No practices here yet")).toBeVisible();
	},
};

/** A group by a slug the page does not list: a hand typed or stale address. */
export const UnknownGroup: Story = {
	args: { state: { status: "missing" } },
	play: async () => {
		const level = within(await settledDrawerPanel());
		await expect(level.getByText("No practice group here by that name")).toBeVisible();
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
