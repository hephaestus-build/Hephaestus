import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, userEvent, within } from "storybook/test";

import {
	ACROSS_WORKSPACE,
	ALL_ESTIMATES,
	COLLAPSED_WORKSPACE,
	EMPTY_WORKSPACE,
	GATED_WORKSPACE,
	HALFWAY_ESTIMATES,
} from "@/stories/practices-across-the-workspace-story-data";
import { expectNoPageOverflow } from "@/stories/reflow";
import { Stateful } from "@/stories/stateful";
import { expectUnavailable } from "@/test/controls";

import type { Estimate } from "./across-workspace-copy";
import { PracticesAcrossTheWorkspacePage } from "./PracticesAcrossTheWorkspacePage";

/**
 * Design C, reflect first, then reveal: every practice group asks where the reader thinks they
 * stand before it shows the workspace's split, and the rows keep catalogue order until nothing asks,
 * so neither a bar nor the order answers for the reader. The reader's own standing is a word in
 * every state, so a split held back says nothing the word does not.
 */
const meta = {
	component: PracticesAcrossTheWorkspacePage,
	tags: ["autodocs"],
	parameters: { layout: "padded" },
	args: {
		workspaceSlug: "aet",
		state: { status: "ready", overview: ACROSS_WORKSPACE },
		window: "TERM",
		onWindowChange: fn(),
		showWorkspace: true,
		onShowWorkspaceChange: fn(),
		askFirst: true,
		onAskFirstChange: fn(),
		estimates: {},
		onEstimate: fn(),
		onSkipRest: fn(),
		onStartOver: fn(),
	},
	// The estimates close their own loop, so a press reveals its row as it does in the app.
	render: (args) => (
		<Stateful<Readonly<Record<string, Estimate | undefined>>> initial={args.estimates}>
			{(estimates, setEstimates) => (
				<PracticesAcrossTheWorkspacePage
					{...args}
					estimates={estimates}
					onEstimate={(groupSlug, estimate) => {
						args.onEstimate?.(groupSlug, estimate);
						setEstimates({ ...estimates, [groupSlug]: estimate });
					}}
				/>
			)}
		</Stateful>
	),
} satisfies Meta<typeof PracticesAcrossTheWorkspacePage>;

export default meta;
type Story = StoryObj<typeof meta>;

const rows = (canvas: { getByRole: (role: "list", options: { name: string }) => HTMLElement }) =>
	within(canvas.getByRole("list", { name: "All practice groups" })).getAllByRole("listitem");

/** The first visit: every group asks, no bar is drawn, and the rows are in catalogue order. */
export const Default: Story = {
	play: async ({ canvas, args }) => {
		await expect(canvas.queryAllByRole("img", { name: /developers observed/u })).toHaveLength(0);
		await expect(rows(canvas)[0]).toHaveTextContent(/^Acting on review feedback/u);
		const question = canvas.getByRole("group", {
			name: "Your estimate for Packaging work for review",
		});
		await expect(within(question).getByText("Only you see your estimate.")).toBeVisible();

		await userEvent.click(within(question).getByRole("button", { name: "Going well" }));
		await expect(args.onEstimate).toHaveBeenCalledWith("review-ready-work", "STRENGTH");
		await expect(
			canvas.getByText("You expected Going well; your latest reviewed work reads Needs attention."),
		).toBeVisible();
		await expect(
			canvas.getByText(
				"11 of the 24 developers observed here this term are Going well, so it is within reach.",
			),
		).toBeVisible();
		await expect(
			canvas.getByRole("img", { name: /^24 developers observed/u }),
		).toHaveAccessibleName(
			"24 developers observed in this workspace this term: 7 Needs attention, 6 Mixed feedback, 11 Going well. You: Needs attention.",
		);
		await expect(canvas.getByRole("img", { name: "1 of 8 answered" })).toBeVisible();
	},
};

/** Three of eight answered: two estimates and a skip, the other five still asking. */
export const Halfway: Story = {
	args: { estimates: HALFWAY_ESTIMATES },
	play: async ({ canvas }) => {
		await expect(
			canvas.getByText("3 of 8 answered. Each group shows the workspace once you answer it."),
		).toBeVisible();
		await expect(canvas.getAllByRole("group", { name: /^Your estimate for/u })).toHaveLength(5);
		await expect(
			canvas.getByText("You skipped the estimate; your latest reviewed work reads Going well."),
		).toBeVisible();
	},
};

/**
 * Everything answered: the rows sort as the practice profile does, needs attention first, and the
 * card counts where estimate and standing read alike without scoring it.
 */
export const Revealed: Story = {
	args: { estimates: ALL_ESTIMATES },
	play: async ({ canvas, args }) => {
		const [first, second] = rows(canvas);
		await expect(first).toHaveTextContent(/^Communicating in the open/u);
		await expect(second).toHaveTextContent(/^Packaging work for review/u);
		await expect(
			canvas.getByText(
				"You estimated 7 practice groups and skipped 1. In 2, your estimate and your latest reviewed work read the same; in 3, they read differently; 2 have no standing to compare yet.",
			),
		).toBeVisible();
		// The marker stands on a split that is shown with the reader inside it, and only there.
		const failure = rows(canvas).find((row) => row.textContent.startsWith("Handling failure well"));
		await expect(failure).toBeDefined();
		if (failure !== undefined) {
			await expect(within(failure).getByText("Mixed feedback")).toBeVisible();
			await expect(within(failure).getByRole("img")).toHaveAccessibleName(
				"24 developers observed in this workspace this term: 19 have a standing, 5 none yet. The split is held back while one standing would cover fewer than five developers other than you. You: Mixed feedback.",
			);
		}
		await userEvent.click(canvas.getByRole("button", { name: "Start over" }));
		await expect(args.onStartOver).toHaveBeenCalled();
	},
};

/** With "Ask me first" off the page shows at once, sorted by standing, with no estimate sentence. */
export const WithoutAsking: Story = {
	args: { askFirst: false },
	play: async ({ canvas }) => {
		await expect(canvas.queryAllByRole("group", { name: /^Your estimate for/u })).toHaveLength(0);
		await expect(canvas.queryByText(/^You expected/u)).toBeNull();
		await expect(rows(canvas)[0]).toHaveTextContent(/^Communicating in the open/u);
	},
};

/** The workspace turned off: the reader's own figures and standings, and no figure about anyone else. */
export const WorkspaceHidden: Story = {
	args: { showWorkspace: false },
	play: async ({ canvas }) => {
		await expect(canvas.queryAllByRole("img", { name: /developers observed/u })).toHaveLength(0);
		await expect(canvas.queryByText(/Most developers here/u)).toBeNull();
		await expectUnavailable(canvas.getByRole("switch", { name: "Ask me first" }));
		await expect(canvas.getByText(/pieces of your work reviewed, this term\.$/u)).toHaveTextContent(
			/^17 pieces of your work reviewed, this term\.$/u,
		);
	},
};

/** Too few developers observed: every middle half and every split is withheld, the reader's words stay. */
export const Gated: Story = {
	args: { state: { status: "ready", overview: GATED_WORKSPACE }, askFirst: false },
	play: async ({ canvas }) => {
		await expect(
			canvas.getAllByText("Needs more data before the workspace shows here."),
		).toHaveLength(3);
		await expect(
			canvas.getAllByText("Not enough developers observed here to compare yet."),
		).toHaveLength(8);
		await expect(canvas.queryAllByRole("img", { name: /developers observed/u })).toHaveLength(0);
	},
};

/** Each split collapses to has a standing against none yet, and no row carries a You marker. */
export const Collapsed: Story = {
	args: { state: { status: "ready", overview: COLLAPSED_WORKSPACE }, askFirst: false },
	play: async ({ canvas }) => {
		await expect(canvas.getAllByRole("img", { name: /17 have a standing/u })).toHaveLength(8);
		// The legend names the marker once; no bar draws it.
		await expect(canvas.getAllByText("You", { exact: true })).toHaveLength(1);
	},
};

export const Empty: Story = {
	args: { state: { status: "ready", overview: EMPTY_WORKSPACE } },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("No practice groups here yet")).toBeVisible();
	},
};

export const Loading: Story = {
	args: { state: { status: "loading" } },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Loading the practice groups")).toHaveClass("sr-only");
		await expect(canvas.getByRole("heading", { level: 1 })).toHaveTextContent(
			"Practices across the workspace",
		);
	},
};

export const LoadError: Story = {
	args: { state: { status: "error", error: new Error("Network down"), onRetry: fn() } },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Couldn't load the workspace")).toBeVisible();
		await expect(canvas.queryByRole("list", { name: "All practice groups" })).toBeNull();
	},
};

export const Reflow: Story = {
	args: { estimates: ALL_ESTIMATES },
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	play: expectNoPageOverflow,
};

/** An administrator's read-only view of the developer's page: nothing asks, and the switch is gone. */
export const ReadOnlyView: Story = {
	args: { canEstimate: false },
	play: async ({ canvas }) => {
		await expect(canvas.queryByRole("switch", { name: "Ask me first" })).toBeNull();
		await expect(canvas.queryAllByRole("group", { name: /^Your estimate for/u })).toHaveLength(0);
		await expect(canvas.getAllByRole("img", { name: /developers observed/u })).toHaveLength(7);
	},
};
