import type { Meta, StoryObj } from "@storybook/react-vite";
import { useState } from "react";
import { expect, fn, screen, userEvent, waitFor, within } from "storybook/test";

import {
	ACROSS_WORKSPACE,
	ACROSS_WORKSPACE_TILES,
	EARLIER_REVIEWS_WORKSPACE,
	EMPTY_WORKSPACE,
	MANY_GROUPS_WORKSPACE,
	NOBODY_TILES,
	NOBODY_WORKSPACE,
	ONE_DEVELOPER_TILES,
	ONE_DEVELOPER_WORKSPACE,
	READER_NOT_COUNTED_WORKSPACE,
	TWO_DEVELOPERS_WORKSPACE,
} from "@/stories/practices-across-the-workspace-story-data";
import { expectNoPageOverflow } from "@/stories/reflow";

import {
	FOCUS_FOOTER_COPY,
	PracticeFocusFooter,
} from "@/components/practice-focus/PracticeFocusFooter";
import { EARLIER_REVIEWS_COUNTED } from "@/components/practice-vocabulary/standing-basis-defs";

import { FIGURES_LOAD_ERROR, GROUPS_LOAD_ERROR } from "./across-workspace-copy";
import {
	PracticesAcrossTheWorkspacePage,
	type PracticesAcrossTheWorkspacePageProps,
} from "./PracticesAcrossTheWorkspacePage";

const meta = {
	component: PracticesAcrossTheWorkspacePage,
	tags: ["autodocs"],
	parameters: { layout: "fullscreen" },
	args: {
		state: { status: "ready", overview: ACROSS_WORKSPACE },
		tiles: { status: "ready", tiles: ACROSS_WORKSPACE_TILES },
		window: "DAYS_30",
		onWindowChange: fn(),
		onOpenGroup: fn(),
	},
} satisfies Meta<typeof PracticesAcrossTheWorkspacePage>;

export default meta;
type Story = StoryObj<typeof meta>;

const groupsTable = (canvas: {
	getByRole: (role: "table", options: { name: string }) => HTMLElement;
}) => within(canvas.getByRole("table", { name: "All practice groups" }));

const figuresSection = (canvas: {
	getByRole: (role: "heading", options: { level: number; name: string }) => HTMLElement;
}) => canvas.getByRole("heading", { level: 2, name: "Your figures" }).closest("section");

const groupsSection = (canvas: {
	getByRole: (role: "heading", options: { level: number; name: string }) => HTMLElement;
}) => canvas.getByRole("heading", { level: 2, name: "All practice groups" }).closest("section");

const EARLIER_NOTE = `${EARLIER_REVIEWS_COUNTED.title}.`;

/** Presses an (i) and returns its panel, which opens in a portal outside the canvas. */
async function openTip(canvas: { getByRole: typeof screen.getByRole }, name: string) {
	await userEvent.click(canvas.getByRole("button", { name }));
	const panel = await screen.findByRole("dialog", { name });
	await waitFor(async () => {
		await expect(panel).toBeVisible();
	});
	return panel;
}

async function closeTip() {
	await userEvent.keyboard("{Escape}");
}

export const Default: Story = {
	play: async ({ canvas, args }) => {
		await expect(canvas.getByText(/21 developers with a standing/u)).toBeVisible();
		// A standing is an AI reading of the work, never a grade: the page says so before any count.
		await expect(canvas.getByText(/Not a grade\.$/u)).toBeVisible();
		// The legend's (i) says each developer counts once, and that small counts show too.
		const bars = within(await openTip(canvas, "About the bars"));
		await expect(
			bars.getByText(/Each developer counts once, however much of their work was reviewed\./u),
		).toBeVisible();
		await expect(bars.getByText(/Every bar shows all its counts, however small\./u)).toBeVisible();
		await closeTip();
		const table = groupsTable(canvas);
		await expect(
			table.getByRole("img", {
				name: "28 developers in this workspace: 7 Needs attention, 6 Mixed feedback, 8 Going well, 7 none yet. You are in the Needs attention part.",
			}),
		).toBeVisible();
		// A group with parts of one, two and none draws like any other.
		await expect(
			table.getByRole("img", {
				name: "28 developers in this workspace: 1 Needs attention, 0 Mixed feedback, 2 Going well, 25 none yet. You are in the part with no standing yet.",
			}),
		).toBeVisible();
		// Only the tiles read the window: the toggle sits in their section, and the bars name none.
		await expect(figuresSection(canvas)).toContainElement(
			canvas.getByRole("toolbar", { name: "Time range" }),
		);
		await expect(groupsSection(canvas)?.textContent).not.toMatch(
			/7 days|30 days|90 days|All time/u,
		);
		// The open feedback reads now, whatever the window.
		await expect(canvas.getByText("pieces open now")).toBeVisible();
		// Every standing here is current, so the page says nothing about earlier reviews.
		await expect(canvas.queryByText(EARLIER_NOTE)).toBeNull();
		await userEvent.click(
			table.getByRole("button", { name: "Open group Packaging work for review" }),
		);
		await expect(args.onOpenGroup).toHaveBeenCalledWith("review-ready-work");
	},
};

/** The row whose practices are open over the page keeps a bar on its leading edge. */
export const GroupOpen: Story = {
	args: { openGroupSlug: "review-ready-work" },
	play: async ({ canvas }) => {
		const row = groupsTable(canvas)
			.getAllByRole("row")
			.find((each) => each.textContent.startsWith("Packaging work for review"));
		await expect(row).toHaveAttribute("data-state", "open");
	},
};

/** The reader is the only developer: the bar and the band still show, with the reader in them. */
export const OneDeveloper: Story = {
	args: {
		state: { status: "ready", overview: ONE_DEVELOPER_WORKSPACE },
		tiles: { status: "ready", tiles: ONE_DEVELOPER_TILES },
	},
	play: async ({ canvas }) => {
		await expect(
			groupsTable(canvas).getByRole("img", {
				name: "1 developer in this workspace: 0 Needs attention, 1 Mixed feedback, 0 Going well, 0 none yet. You are in the Mixed feedback part.",
			}),
		).toBeVisible();
		await expect(canvas.getByText(/1 developer with a standing/u)).toBeVisible();
		await expect(canvas.getAllByText("Typical range:", { exact: false }).length).toBeGreaterThan(0);
		await expect(canvas.queryByText("No developer is counted here yet.")).toBeNull();
	},
};

/** Two developers, the reader one of them: every count shows, however small. */
export const TwoDevelopers: Story = {
	args: { state: { status: "ready", overview: TWO_DEVELOPERS_WORKSPACE } },
	play: async ({ canvas }) => {
		await expect(
			groupsTable(canvas).getByRole("img", {
				name: "2 developers in this workspace: 1 Needs attention, 0 Mixed feedback, 1 Going well, 0 none yet. You are in the Going well part.",
			}),
		).toBeVisible();
	},
};

/** The reader has no standing that counts: the bars count the others, and mark nobody. */
export const ReaderNotCounted: Story = {
	args: { state: { status: "ready", overview: READER_NOT_COUNTED_WORKSPACE } },
	play: async ({ canvas }) => {
		const table = groupsTable(canvas);
		await expect(
			table.getByRole("img", {
				name: "2 developers in this workspace: 1 Needs attention, 0 Mixed feedback, 1 Going well, 0 none yet.",
			}),
		).toBeVisible();
		await expect(table.queryByText("You")).toBeNull();
	},
};

/**
 * Some counted standings rest on earlier reviews: the page says so once, and the reader's own row
 * carries the same mark as their Practice profile, criteria unknown included.
 */
export const EarlierReviews: Story = {
	args: { state: { status: "ready", overview: EARLIER_REVIEWS_WORKSPACE } },
	play: async ({ canvas }) => {
		await expect(canvas.getByText(EARLIER_NOTE)).toBeVisible();
		const mark = groupsTable(canvas).getByRole("button", {
			name: "Based on earlier reviews, criteria unknown",
		});
		await expect(mark).toBeVisible();
		// Earlier assessments do not establish whether the current criteria are met.
		await expect(mark).toHaveAccessibleDescription(
			/They do not show whether the current criteria are met\./u,
		);
	},
};

/** Nobody has a standing yet: no tile has a range, and no bar counts anybody. */
export const NobodyYet: Story = {
	args: {
		state: { status: "ready", overview: NOBODY_WORKSPACE },
		tiles: { status: "ready", tiles: NOBODY_TILES },
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText(/0 developers with a standing/u)).toBeVisible();
		await expect(canvas.getAllByText("No developer is counted here yet.")).toHaveLength(3);
		// Nobody has open feedback, so that tile says so in place of a band at nought.
		await expect(canvas.getByText("Most developers here have no open feedback.")).toBeVisible();
		// Once for the whole table, not once per group.
		await expect(canvas.getAllByText("No developer has a standing yet.")).toHaveLength(1);
	},
};

/** Many groups: the table lists every one, as the Practice profile does, with no paging. */
export const ManyGroups: Story = {
	args: { state: { status: "ready", overview: MANY_GROUPS_WORKSPACE } },
	play: async ({ canvas }) => {
		const table = groupsTable(canvas);
		await expect(table.getAllByRole("button", { name: /^Open group /u })).toHaveLength(26);
	},
};

/** Another window on its way: the last figures stay, and only their section is busy. */
export const SwitchingWindow: Story = {
	args: {
		tiles: { status: "ready", tiles: ACROSS_WORKSPACE_TILES, stale: true },
		window: "DAYS_90",
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("table", { name: "All practice groups" })).toBeVisible();
		await expect(figuresSection(canvas)).toHaveAttribute("aria-busy", "true");
		await expect(groupsSection(canvas)).not.toHaveAttribute("aria-busy");
	},
};

export const Empty: Story = {
	args: { state: { status: "ready", overview: EMPTY_WORKSPACE } },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("No practices set up yet")).toBeVisible();
	},
};

const LOADING_ARGS = {
	state: { status: "loading" },
	tiles: { status: "loading" },
} satisfies Partial<PracticesAcrossTheWorkspacePageProps>;

/**
 * While the server reads: the skeletons in the loaded page's shape and every region busy, as the
 * Practice profile loads; no counting line and no wait note.
 */
export const Loading: Story = {
	args: LOADING_ARGS,
	play: async ({ canvas }) => {
		await expect(figuresSection(canvas)).toHaveAttribute("aria-busy", "true");
		await expect(groupsSection(canvas)).toHaveAttribute("aria-busy", "true");
		await expect(canvas.queryByText(/^Counting /u)).toBeNull();
		await expect(canvas.queryByRole("button", { name: "About the wait" })).toBeNull();
	},
};

export const LoadingDark: Story = {
	args: LOADING_ARGS,
	globals: { theme: "dark" },
};

/** At 390px the skeletons keep to the page. */
export const LoadingPhone: Story = {
	args: LOADING_ARGS,
	globals: { viewport: { value: "phone390" } },
	play: async () => {
		await expectNoPageOverflow();
	},
};

/** The page as the route draws it: loading until the counts arrive. The button stands in for them. */
function CountsArrive(props: PracticesAcrossTheWorkspacePageProps) {
	const [arrived, setArrived] = useState(false);
	return (
		<>
			<PracticesAcrossTheWorkspacePage {...props} {...(arrived ? {} : LOADING_ARGS)} />
			<button type="button" className="sr-only" onClick={() => setArrived(true)}>
				Counts arrive
			</button>
		</>
	);
}

/** When the counts arrive the regions stop being busy, and the figures keep their height. */
export const LoadingToLoaded: Story = {
	render: (args) => <CountsArrive {...args} />,
	play: async ({ canvas }) => {
		const tileHeights = () =>
			[...(figuresSection(canvas)?.querySelectorAll("li") ?? [])].map(
				(tile) => tile.getBoundingClientRect().height,
			);
		const loading = tileHeights();
		await userEvent.click(canvas.getByRole("button", { name: "Counts arrive" }));
		await canvas.findByRole("button", { name: "Open group Packaging work for review" });
		await expect(figuresSection(canvas)).not.toHaveAttribute("aria-busy");
		await expect(groupsSection(canvas)).not.toHaveAttribute("aria-busy");
		await expect(tileHeights()).toStrictEqual(loading);
	},
};

const onRetryOverview = fn();

/** Every region reads the overview, so its failure takes the page, with no window toggle to press. */
export const LoadError: Story = {
	args: { state: { status: "error", error: new Error("Network down"), onRetry: onRetryOverview } },
	play: async ({ canvas }) => {
		await expect(canvas.getByText(GROUPS_LOAD_ERROR)).toBeVisible();
		await expect(canvas.queryByRole("toolbar", { name: "Time range" })).toBeNull();
		await userEvent.click(canvas.getByRole("button", { name: "Retry" }));
		await expect(onRetryOverview).toHaveBeenCalledOnce();
	},
};

const onRetryTiles = fn();

/** The window's figures failed: their section says so, and the bars, which read no window, stay. */
export const TilesLoadError: Story = {
	args: { tiles: { status: "error", error: new Error("Network down"), onRetry: onRetryTiles } },
	play: async ({ canvas }) => {
		await expect(canvas.getByText(FIGURES_LOAD_ERROR)).toBeVisible();
		await expect(canvas.getByRole("table", { name: "All practice groups" })).toBeVisible();
		await userEvent.click(canvas.getByRole("button", { name: "Retry" }));
		await expect(onRetryTiles).toHaveBeenCalledOnce();
	},
};

export const Reflow: Story = {
	args: { state: { status: "ready", overview: EARLIER_REVIEWS_WORKSPACE } },
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	play: async (context) => {
		await expectNoPageOverflow();
		// The page cannot overflow past a table's own frame, so each table is checked inside it.
		for (const table of context.canvas.getAllByRole("table")) {
			await expect(table.offsetWidth).toBeLessThanOrEqual(table.parentElement?.clientWidth ?? 0);
		}
	},
};

export const Dark: Story = {
	args: { state: { status: "ready", overview: EARLIER_REVIEWS_WORKSPACE } },
	globals: { theme: "dark" },
};

const onSwitchToProfile = fn();

/** Under the table of every group, the way to the same list in the reader's own Practice profile. */
export const FocusFooter: Story = {
	args: {
		focusFooter: (
			<PracticeFocusFooter
				{...FOCUS_FOOTER_COPY.workspaceAllGroups}
				placement="page"
				accessibleLabel={FOCUS_FOOTER_COPY.workspaceAllGroups.label}
				onSwitch={onSwitchToProfile}
			/>
		),
	},
	play: async ({ canvas }) => {
		const button = canvas.getByRole("button", { name: "See your own standing" });
		await expect(button).toBeVisible();
		await userEvent.click(button);
		await expect(onSwitchToProfile).toHaveBeenCalledOnce();
	},
};
