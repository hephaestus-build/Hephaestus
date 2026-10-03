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
		onOpenPractice: fn(),
		onOpenOwnGroup: fn(),
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
		await userEvent.click(
			level.getByRole("button", { name: "Open your group Packaging work for review" }),
		);
		await expect(args.onOpenOwnGroup).toHaveBeenCalledOnce();
		const table = within(
			level.getByRole("table", { name: "Practices of Packaging work for review" }),
		);
		await expect(table.getAllByRole("button", { name: /^View practice /u })).toHaveLength(5);
		await userEvent.click(
			table.getByRole("button", { name: "View practice Scope the change to one concern" }),
		);
		await expect(args.onOpenPractice).toHaveBeenCalledWith("scope-to-one-concern");
		await expect(table.getAllByText("Held back so no one can be singled out.")).toHaveLength(2);
		// Why a practice is held back more often than its group, once, over its practices.
		await expect(
			level.getByText(/setting it beside the group's bar singles no one out/u),
		).toBeVisible();
		// The page's legend sits above the practices it explains.
		await expect(level.getByRole("list", { name: "What the bars show" })).toBeVisible();
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
