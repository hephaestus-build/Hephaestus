import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, waitFor, within } from "storybook/test";

import type { TracedArtifact } from "@/api/types.gen";
import { tracedArtifacts } from "@/components/practice-trace/fixtures";
import type { PagedListState } from "@/runtime/tanstack-query/infinite-list";
import { withStandardPage, withWidePage } from "@/stories/decorators";
import { expectSettledVisible } from "@/stories/overlay";
import { loadedList, narrowedList } from "@/stories/paged-list";
import { expectNoPageOverflow } from "@/stories/reflow";
import { StatefulPatch } from "@/stories/stateful";

import { REVIEW_PAGE_SIZE, type WorkSearch, workQuery } from "./review-search";
import { WorkListPage } from "./WorkListPage";

const loadMoreWork = fn();
const retryWork = fn();

/**
 * The work the endpoint would return for a search, computed from the fixture through `workQuery`,
 * the transformation the route sends, so a story cannot prove a filter the screen never asks for.
 */
function workFor(
	list: PagedListState<TracedArtifact>,
	search: WorkSearch,
): PagedListState<TracedArtifact> {
	const query = workQuery(search, REVIEW_PAGE_SIZE);
	return narrowedList(
		list,
		(work) => query.artifactKind === undefined || work.artifactKind === query.artifactKind,
		query.size,
	);
}

const meta = {
	component: WorkListPage,
	parameters: {
		layout: "fullscreen",
		chromatic: { viewports: [320, 768, 1440] },
	},
	decorators: [withWidePage, withStandardPage],
	tags: ["autodocs"],
	args: {
		search: {},
		onSearchChange: fn(),
		work: loadedList(tracedArtifacts),
	},
	// The screen is controlled: with a frozen `search` prop the kind filter reads as dead. The rows
	// follow the search the same way the route's query would.
	render: (args) => (
		<StatefulPatch initial={args.search}>
			{(search, patch) => (
				<WorkListPage
					{...args}
					search={search}
					onSearchChange={(next) => {
						patch(next);
						args.onSearchChange(next);
					}}
					work={workFor(args.work, search)}
				/>
			)}
		</StatefulPatch>
	),
} satisfies Meta<typeof WorkListPage>;

export default meta;
type Story = StoryObj<typeof meta>;

/**
 * The leading icon is whether anything recorded about the work started a review, so work nobody
 * reviewed stands out without opening it.
 */
export const Default: Story = {
	parameters: { viewport: { defaultViewport: "desktop" } },
	play: async ({ canvas }) => {
		await canvas.findByText("5 pieces of work.");
		const list = canvas.getByRole("list", { name: "Work, most recent first" });
		const [first] = within(list).getAllByRole("listitem");
		if (!first) {
			throw new Error("The list has no row");
		}
		within(first).getByRole("link", {
			name: "Member-facing review activity: say why a practice stayed quiet",
		});
		within(first).getByRole("button", { name: "Review started" });
		within(first).getByText("6 moments recorded · 2 started a review");
		within(list).getByText("Onboarding: your first week");
		await expect(within(list).getAllByRole("button", { name: "No review started" })).toHaveLength(
			2,
		);
	},
};

/** Every kind this build knows is offered, named, whether or not the page shows one. */
export const KindFilter: Story = {
	play: async ({ args, canvas, userEvent }) => {
		await canvas.findByText("5 pieces of work.");
		await userEvent.click(canvas.getByRole("combobox", { name: "Show" }));
		// The listbox is portalled, so it is on `screen` rather than in the canvas.
		await expectSettledVisible(await screen.findByRole("option", { name: "Documents" }));
		await userEvent.click(screen.getByRole("option", { name: "Issues" }));
		await expect(args.onSearchChange).toHaveBeenCalledWith({ kind: "scm.issue" });
		await canvas.findByText("1 piece of work matches your filters.");
		canvas.getByRole("link", { name: "Define the practice-binding contract" });
	},
};

export const Mobile: Story = {
	parameters: {
		chromatic: { viewports: [320, 768] },
		viewport: { defaultViewport: "reflow" },
	},
	play: async ({ canvas }) => {
		await canvas.findByText("5 pieces of work.");
		await expectNoPageOverflow();
	},
};

/** The skeleton draws `REVIEW_PAGE_SIZE` rows, so the first page replaces it without a jump. */
export const Loading: Story = {
	args: { work: { status: "loading" } },
	parameters: { chromatic: { viewports: [1440] } },
	render: (args) => <WorkListPage {...args} />,
	play: async ({ canvas }) => {
		await canvas.findByText("Loading work");
		await expect(
			canvas.queryByRole("list", { name: /^Work, most recent first/u }),
		).not.toBeInTheDocument();
	},
};

export const NothingRecorded: Story = {
	args: { work: loadedList([]) },
	parameters: { chromatic: { viewports: [1440] } },
	render: (args) => <WorkListPage {...args} />,
	play: async ({ canvas }) => {
		await canvas.findByText("Nothing has been recorded yet");
		// Nothing is filtered, so the empty state must not offer an action that would change nothing.
		await expect(canvas.queryByRole("button", { name: "Show all work" })).not.toBeInTheDocument();
	},
};

export const NoWorkOfThatKind: Story = {
	args: { search: { kind: "chat.conversation_thread" }, work: loadedList([]) },
	parameters: { chromatic: { viewports: [1440] } },
	render: (args) => <WorkListPage {...args} />,
	play: async ({ args, canvas, userEvent }) => {
		await canvas.findByText("No conversations recorded yet");
		await userEvent.click(canvas.getByRole("button", { name: "Show all work" }));
		await expect(args.onSearchChange).toHaveBeenCalledWith({ kind: undefined });
	},
};

/** A kind the server does not know is a 400, which is not retried: the reader changes the filter. */
export const LoadFailed: Story = {
	args: {
		work: {
			status: "error",
			error: { status: 400, title: "Bad Request", detail: "Unknown artifact kind." },
			onRetry: retryWork,
		},
	},
	parameters: { chromatic: { viewports: [1440] } },
	render: (args) => <WorkListPage {...args} />,
	play: async ({ canvas }) => {
		await canvas.findByText("We could not load the work");
		canvas.getByText(/Unknown artifact kind/u);
		await expect(canvas.queryByRole("button", { name: "Retry" })).not.toBeInTheDocument();
	},
};

export const LoadFailedWithoutAnAnswer: Story = {
	args: {
		work: { status: "error", error: new TypeError("Failed to fetch"), onRetry: retryWork },
	},
	parameters: { chromatic: { viewports: [1440] } },
	render: (args) => <WorkListPage {...args} />,
	play: async ({ canvas, userEvent }) => {
		await canvas.findByText("We could not load the work");
		await userEvent.click(canvas.getByRole("button", { name: "Retry" }));
		await expect(retryWork).toHaveBeenCalledTimes(1);
	},
};

/** More work than the first page: the next page is asked for when the end scrolls into view. */
export const LoadsMoreAtTheEnd: Story = {
	args: { work: loadedList(tracedArtifacts, { hasMore: true, onLoadMore: loadMoreWork }) },
	parameters: { chromatic: { viewports: [1440] } },
	play: async ({ canvas }) => {
		const more = await canvas.findByRole("button", { name: "Show more work" });
		more.scrollIntoView();
		await waitFor(async () => expect(loadMoreWork).toHaveBeenCalled());
	},
};
