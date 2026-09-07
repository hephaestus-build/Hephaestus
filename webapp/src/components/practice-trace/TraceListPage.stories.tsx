import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, userEvent, within } from "storybook/test";

import { withStandardPage, withWidePage } from "@/stories/decorators";
import { StatefulPatch } from "@/stories/stateful";
import { expectSettledVisible } from "@/test/overlay";
import { expectNoPageOverflow } from "@/test/reflow";

import { tracedArtifact, tracedArtifactPage } from "./story-mock-data";
import { TRACE_PAGE_SIZE, TraceListPage } from "./TraceListPage";

/**
 * The member-facing list of everything this workspace recorded. The route asks for the page; this
 * screen only shows it, so every state below is a prop rather than a mocked response.
 */
const meta = {
	title: "Practice trace/Review activity list",
	component: TraceListPage,
	parameters: {
		layout: "fullscreen",
		chromatic: { viewports: [320, 768, 1440] },
	},
	decorators: [withWidePage, withStandardPage],
	tags: ["autodocs"],
	args: {
		workspaceSlug: "demo",
		search: {},
		onSearchChange: fn(),
		artifacts: tracedArtifactPage(),
		isLoading: false,
		error: undefined,
		onRetry: fn(),
	},
	/**
	 * The picker and the pager are controlled, so a story passing only `fn()` could not show a chosen
	 * kind. The harness holds the answer and the spy still records the patch.
	 */
	render: (args) => (
		<StatefulPatch initial={args.search}>
			{(search, patch) => (
				<TraceListPage
					{...args}
					search={search}
					onSearchChange={(next) => {
						args.onSearchChange(next);
						patch(next);
					}}
				/>
			)}
		</StatefulPatch>
	),
} satisfies Meta<typeof TraceListPage>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ canvas }) => {
		// Written out rather than read back off the fixture, so a fixture that loses a row fails here
		// instead of quietly agreeing with the page.
		await expect(await canvas.findByText("5 pieces of work.")).toBeVisible();
		await expect(canvas.getByRole("link", { name: /Member-facing review activity/ })).toBeVisible();
		await expect(canvas.getByText("6 moments recorded · 2 started a review")).toBeVisible();
	},
};

export const UnlinkableArtifact: Story = {
	args: { artifacts: tracedArtifactPage([tracedArtifact(88)]) },
	play: async ({ canvas }) => {
		await expect(await canvas.findByText("1 piece of work.")).toBeVisible();
		await expect(canvas.getByText("1 moment recorded · 0 started a review")).toBeVisible();
	},
};

export const EveryKindIsNamed: Story = {
	play: async ({ canvas }) => {
		await expect(await canvas.findByText("Onboarding: your first week")).toBeVisible();
		await expect(canvas.queryByText("docs.document")).not.toBeInTheDocument();

		await userEvent.click(canvas.getByRole("combobox", { name: /Show/ }));
		// The listbox is portalled, so it is on `screen` rather than in the canvas.
		await expectSettledVisible(await screen.findByRole("option", { name: "Documents" }));
		screen.getByRole("option", { name: "Conversations" });
	},
};

/** The skeleton shows the page it is standing in for, so the rows land where the bars were. */
export const Loading: Story = {
	args: { isLoading: true, artifacts: undefined },
	play: async ({ canvas }) => {
		const status = (await canvas.findByText("Loading review activity")).closest('[role="status"]');
		if (!(status instanceof HTMLElement)) throw new Error("The skeleton is not a live region");
		// Counted, not eyeballed: a skeleton of four bars for a page of twenty is the jump a skeleton
		// exists to prevent.
		await expect(status.querySelectorAll(":scope > div")).toHaveLength(TRACE_PAGE_SIZE);
	},
};

export const NothingRecorded: Story = {
	args: { artifacts: tracedArtifactPage([]) },
	play: async ({ canvas }) => {
		await expect(await canvas.findByText("Nothing has been recorded here yet")).toBeVisible();
	},
};

export const FilteredToOneKind: Story = {
	args: {
		search: { kind: "scm.issue" },
		onSearchChange: fn(),
		artifacts: tracedArtifactPage([tracedArtifact(1430)]),
	},
	play: async ({ args, canvas }) => {
		await expect(await canvas.findByRole("combobox", { name: "Show" })).toHaveTextContent("Issues");

		await userEvent.click(canvas.getByRole("button", { name: /Reset/ }));
		await expect(args.onSearchChange).toHaveBeenCalledWith({ kind: undefined, page: undefined });
	},
};

/** The `ALL_KINDS` sentinel must not escape into the URL: it filters for a kind nothing ever has. */
export const ClearingTheFilterFromThePicker: Story = {
	args: { search: { kind: "scm.issue", page: 3 }, onSearchChange: fn() },
	play: async ({ args, canvas }) => {
		await userEvent.click(await canvas.findByRole("combobox", { name: "Show" }));
		await userEvent.click(await within(document.body).findByRole("option", { name: "All work" }));

		// The page number goes with it: a page of the filtered list is not a page of the unfiltered one.
		await expect(args.onSearchChange).toHaveBeenCalledWith({ kind: undefined, page: undefined });
	},
};

export const NoWorkOfThatKind: Story = {
	args: {
		search: { kind: "chat.conversation_thread" },
		onSearchChange: fn(),
		artifacts: tracedArtifactPage([]),
	},
	play: async ({ canvas }) => {
		await expect(await canvas.findByText("No conversations recorded yet")).toBeVisible();
		await expect(canvas.getByText(/Switch back to all work/)).toBeVisible();
	},
};

export const LoadFailed: Story = {
	args: {
		artifacts: undefined,
		error: { status: 400, title: "Bad Request", detail: "Unknown artifact kind." },
	},
	play: async ({ args, canvas }) => {
		await expect(await canvas.findByText("Couldn't load review activity")).toBeVisible();
		await expect(canvas.getByText(/Unknown artifact kind/)).toBeVisible();
		// A 400 is not retryable, so the alert withholds the button even though a handler was passed.
		await expect(canvas.queryByRole("button", { name: "Retry" })).not.toBeInTheDocument();
		await expect(args.onRetry).not.toHaveBeenCalled();
	},
};

/** No answer at all — offline, or a request that never landed. Retrying is exactly right. */
export const LoadFailedWithoutAnAnswer: Story = {
	args: { artifacts: undefined, error: new TypeError("Failed to fetch") },
	play: async ({ args, canvas }) => {
		await expect(await canvas.findByText("Couldn't load review activity")).toBeVisible();
		await userEvent.click(canvas.getByRole("button", { name: "Retry" }));
		await expect(args.onRetry).toHaveBeenCalledTimes(1);
	},
};

export const Mobile: Story = {
	parameters: {
		chromatic: { disableSnapshot: true },
		viewport: { defaultViewport: "reflow" },
	},
	play: async ({ canvas }) => {
		await expect(await canvas.findByText("5 pieces of work.")).toBeVisible();
		await expectNoPageOverflow();
	},
};
