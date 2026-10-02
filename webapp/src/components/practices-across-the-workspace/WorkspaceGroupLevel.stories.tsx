import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, within } from "storybook/test";

import { DetailDrawerStack } from "@/components/layout/detail-drawer/DetailDrawerStack";
import { withPageBehind } from "@/stories/decorators";
import { expectSettledVisible, settledDrawerPanel } from "@/stories/overlay";
import { PACKAGING_GROUP } from "@/stories/practices-across-the-workspace-story-data";
import { expectNoPanelOverflow } from "@/stories/reflow";
import { Stateful } from "@/stories/stateful";

import { WorkspaceGroupLevel } from "./WorkspaceGroupLevel";

/**
 * A practice group's practices over Practices across the workspace. The level has no page of its
 * own, so every story mounts a real drawer over a page.
 */
const meta = {
	component: WorkspaceGroupLevel,
	parameters: { layout: "fullscreen" },
	decorators: [withPageBehind],
	args: {
		path: { behind: [{ label: "Across the workspace", depth: 0 }], onClose: fn() },
		workspaceSlug: "aet",
		group: PACKAGING_GROUP,
		context: { window: "TERM", readerCounted: true, observedDevelopers: 24, minimumOthers: 5 },
		showWorkspace: true,
		isLoading: false,
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
								onClose: (depth) => setStack(stack.slice(0, depth)),
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
	play: async () => {
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
		await expect(table.getAllByRole("link", { name: /^View practice /u })).toHaveLength(5);
		await expect(
			table.getByRole("link", { name: "View practice Scope the change to one concern" }),
		).toHaveAttribute("href", expect.stringContaining("practice%3Ascope-to-one-concern"));
		await expect(table.getByText("16 have a standing, 8 none yet; split held back")).toBeVisible();
		await expect(
			table.getByText("Split held back: 24 developers observed this term."),
		).toBeVisible();
		await expect(level.queryByText(/See practices/u)).toBeNull();
	},
};

/** The workspace turned off: the reader's own words and the two ways out, no split anywhere. */
export const WorkspaceHidden: Story = {
	args: { showWorkspace: false },
	play: async () => {
		const level = within(await settledDrawerPanel());
		await expect(level.queryAllByRole("img")).toStrictEqual([]);
		await expect(level.getByRole("columnheader", { name: "Your standing" })).toBeVisible();
	},
};

export const Loading: Story = {
	args: { group: undefined, context: undefined, isLoading: true },
	play: async () => {
		await settledDrawerPanel();
		await expect(screen.getByRole("table")).toHaveAttribute("aria-busy", "true");
	},
};

export const NoPractices: Story = {
	args: { group: { ...PACKAGING_GROUP, practices: [] } },
	play: async () => {
		const level = within(await settledDrawerPanel());
		await expect(level.getByText("No practices here yet")).toBeVisible();
	},
};

/** A group by a slug the page does not list: a hand typed or stale address. */
export const UnknownGroup: Story = {
	args: { group: undefined },
	play: async () => {
		const level = within(await settledDrawerPanel());
		await expect(level.getByText("No practice group here by that name")).toBeVisible();
	},
};

export const Reflow: Story = {
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	play: async () => {
		await expectNoPanelOverflow(await settledDrawerPanel());
	},
};
