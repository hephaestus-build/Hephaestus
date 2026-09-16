import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, within } from "storybook/test";

import type { ListPracticeReviewFeedbackResponse, ReviewFeedback } from "@/api/types.gen";
import { withStandardPage, withWidePage } from "@/stories/decorators";
import { StatefulPatch } from "@/stories/stateful";
import { expectNoPageOverflow } from "@/test/reflow";

import { FeedbackListPage } from "./FeedbackListPage";
import { type FeedbackSearch, feedbackQuery, REVIEW_PAGE_SIZE } from "./review-search";
import type { ReviewPeople } from "./ReviewPersonFacet";
import { manyFeedback, reviewFeedback, workspaceMembers } from "./story-mock-data";

const PEOPLE: ReviewPeople = {
	options: workspaceMembers
		.filter((member): member is typeof member & { userId: number } => member.userId != null)
		.map((member) => ({
			userId: member.userId,
			label: member.userName ?? `#${member.userId}`,
			secondary: member.userLogin,
		})),
	capped: false,
	isLoading: false,
	isError: false,
};

/**
 * Every row a story has to choose from. It travels in the `feedback` arg because that is the prop
 * the screen reads, and {@link feedbackPage} narrows it to one page before the screen sees it — so
 * an arg set in Controls is a pool to filter, not a page already cut.
 */
function pool(rows: ReviewFeedback[]): ListPracticeReviewFeedbackResponse {
	return {
		content: rows,
		page: {
			number: 0,
			size: REVIEW_PAGE_SIZE,
			totalElements: rows.length,
			totalPages: Math.max(1, Math.ceil(rows.length / REVIEW_PAGE_SIZE)),
		},
	};
}

/**
 * The route fetches; this screen only draws what it is handed. To keep the facets live in a story
 * without a network, the filtering the server does is applied here — over the query object the route
 * would actually send. That is what keeps the "Why withheld" facet honest: the URL carries families
 * and `feedbackQuery` expands them to the individual reasons rows actually carry, so a story that
 * filtered on the family name would pass while the real request returned nothing.
 */
function feedbackPage(
	candidates: ReviewFeedback[],
	search: FeedbackSearch,
): ListPracticeReviewFeedbackResponse {
	const query = feedbackQuery(search, REVIEW_PAGE_SIZE);
	const selects = (selected: string[] | undefined, actual: string | undefined) =>
		selected === undefined ||
		selected.length === 0 ||
		(actual !== undefined && selected.includes(actual));
	const rows = candidates.filter(
		(row) =>
			(!query.from || row.createdAt >= new Date(query.from)) &&
			(!query.to || row.createdAt < new Date(query.to)) &&
			selects(query.deliveryState, row.deliveryState) &&
			selects(query.channel, row.channel) &&
			selects(query.suppressionReason, row.suppressionReason) &&
			(query.recipientUserId === undefined || row.recipient?.id === query.recipientUserId),
	);
	const number = query.page;
	return {
		content: rows.slice(number * REVIEW_PAGE_SIZE, (number + 1) * REVIEW_PAGE_SIZE),
		page: {
			number,
			size: REVIEW_PAGE_SIZE,
			totalElements: rows.length,
			totalPages: Math.max(1, Math.ceil(rows.length / REVIEW_PAGE_SIZE)),
		},
	};
}

const meta = {
	title: "Workspace admin/Practice reviews/Delivery",
	component: FeedbackListPage,
	parameters: {
		layout: "fullscreen",
		chromatic: { viewports: [320, 768, 1440] },
	},
	decorators: [withWidePage, withStandardPage],
	tags: ["autodocs"],
	args: {
		workspaceSlug: "demo",
		search: { deliveryState: undefined, withheldFamily: undefined, channel: undefined },
		onSearchChange: fn(),
		feedback: pool(reviewFeedback),
		isLoading: false,
		error: undefined,
		onRetry: fn(),
		people: PEOPLE,
	},
	// The screen is controlled: with a frozen `search` prop every facet reads as dead. The rows are
	// recomputed from that search the way the route's query would be re-run.
	render: (args) => (
		<StatefulPatch initial={args.search}>
			{(search, onSearchChange) => (
				<FeedbackListPage
					{...args}
					search={search}
					onSearchChange={onSearchChange}
					feedback={args.feedback && feedbackPage(args.feedback.content ?? [], search)}
				/>
			)}
		</StatefulPatch>
	),
} satisfies Meta<typeof FeedbackListPage>;

export default meta;
type Story = StoryObj<typeof meta>;

/**
 * The withheld rows are what this set exists for: each carries its own precise sentence, and no two
 * of them say the same thing, which is what keeps the reason from reading as decoration.
 */
export const Default: Story = {
	play: async ({ canvas }) => {
		await canvas.findByText("11 pieces of feedback.");
		for (const name of ["Outcome", "Place", "Why withheld", "Recipient"]) {
			canvas.getByRole("combobox", { name });
		}
		// "Composed" is neither when the feedback was delivered nor when the observation was made.
		canvas.getByRole("button", { name: "Composed" });
		canvas.getByText("Nearly the same as other feedback from the same review.");
		canvas.getByText("Found while reviewing past work, which is measured but never sent.");
		canvas.getByText("The developer has opted out of AI feedback.");
		canvas.getByText("The work was already merged, so a note on it would arrive too late.");
	},
};

/**
 * The endpoint sends a fixed-character cut of the stored Markdown, which on a real note ends inside
 * a fenced code block. The row has to flatten that to prose and mark the cut, or the title reads as
 * `**🔴 …** · \`File.java:118\` You wrote: ```java`.
 */
export const LongFeedbackReadsAsProse: Story = {
	parameters: { chromatic: { viewports: [1440] } },
	play: async ({ canvas }) => {
		const row = await canvas.findByRole("link", { name: /2 issues to tighten in this change/u });
		await expect(row).toHaveAccessibleName(expect.stringContaining("…"));
		await expect(row).not.toHaveAccessibleName(expect.stringContaining("```"));
		await expect(canvas.queryByText(/```java/u)).not.toBeInTheDocument();
	},
};

export const FilterToOneRecipient: Story = {
	parameters: { chromatic: { viewports: [1440] } },
	play: async ({ canvas, userEvent }) => {
		await canvas.findByText("11 pieces of feedback.");
		await userEvent.click(canvas.getByRole("combobox", { name: "Recipient" }));
		const listbox = await screen.findByRole("listbox");
		await userEvent.click(
			await within(listbox).findByRole("option", { name: /Katherine Johnson/u }),
		);
		await canvas.findByRole("combobox", { name: "Recipient: Katherine Johnson" });
		await canvas.findByText("3 pieces of feedback match your filters.");
	},
};

/**
 * The facet offers families rather than every withholding reason, which is what fits it on the
 * toolbar. A row still shows its own precise sentence; only the question is grouped.
 */
export const WhyWithheldFacetOpen: Story = {
	parameters: { chromatic: { viewports: [1440] } },
	play: async ({ canvas, userEvent }) => {
		await userEvent.click(canvas.getByRole("combobox", { name: "Why withheld" }));
		const listbox = await screen.findByRole("listbox");
		await within(listbox).findByRole("option", { name: /The work moved on/u });
		within(listbox).getByRole("option", { name: /The developer's choice/u });
	},
};

/** Filtering by a family returns exactly the rows whose own sentence sits under that heading. */
export const FilterToOneWithholdingFamily: Story = {
	parameters: { chromatic: { viewports: [1440] } },
	play: async ({ canvas, userEvent }) => {
		await canvas.findByText("11 pieces of feedback.");
		const trigger = canvas.getByRole("combobox", { name: "Why withheld" });
		await userEvent.click(trigger);
		const listbox = await screen.findByRole("listbox", { name: "Why withheld options" });
		await userEvent.click(await within(listbox).findByRole("option", { name: /Housekeeping/u }));
		// Closed again: these facets are multi-select and stay open after a choice, so the next facet
		// would otherwise put a second listbox on screen.
		await userEvent.click(trigger);
		await canvas.findByText("1 piece of feedback matches your filters.");
		canvas.getByText("Nearly the same as other feedback from the same review.");

		// A place nothing under that family went to, so the two filters intersect to nothing.
		await userEvent.click(canvas.getByRole("combobox", { name: "Place" }));
		const places = await screen.findByRole("listbox", { name: "Place options" });
		await userEvent.click(await within(places).findByRole("option", { name: /In conversation/u }));
		await canvas.findByText("No feedback matches these filters");
		await userEvent.click(canvas.getByRole("button", { name: "Clear all filters" }));
		await canvas.findByText("11 pieces of feedback.");
	},
};

export const MoreThanOnePage: Story = {
	// The page is set rather than clicked: pagination is real links, so the page travels through the
	// router, and Storybook mounts this screen under a single bare route.
	args: {
		search: {
			page: 1,
			deliveryState: undefined,
			withheldFamily: undefined,
			channel: undefined,
		},
		feedback: pool(manyFeedback(60)),
	},
	parameters: { chromatic: { viewports: [1440] } },
	play: async ({ canvas }) => {
		await canvas.findByText("60 pieces of feedback.");
		const current = await canvas.findByRole("link", { name: "Go to page 2" });
		await expect(current).toHaveAttribute("aria-current", "page");
	},
};

export const Mobile: Story = {
	parameters: {
		chromatic: { disableSnapshot: true },
		viewport: { defaultViewport: "reflow" },
	},
	play: async ({ canvas }) => {
		await canvas.findByText("11 pieces of feedback.");
		await expectNoPageOverflow();
	},
};

/**
 * The error arrives as a prop, so nothing here depends on a request failing at the right moment. A
 * status-less error is the one that reads "check your connection" — see `QueryErrorAlert`.
 */
export const LoadFailed: Story = {
	parameters: { chromatic: { viewports: [1440] } },
	args: { feedback: undefined, error: { status: 500, detail: "Something went wrong." } },
	play: async ({ canvas }) => {
		await canvas.findByText("Couldn't load feedback");
	},
};

export const Loading: Story = {
	parameters: { chromatic: { viewports: [1440] } },
	args: { feedback: undefined, isLoading: true },
	play: async ({ canvas }) => {
		await canvas.findByText("Loading feedback");
	},
};

/** Nothing has been composed yet, which is not the same as a filter that matched nothing. */
export const NoFeedbackYet: Story = {
	parameters: { chromatic: { viewports: [1440] } },
	args: { feedback: pool([]) },
	play: async ({ canvas }) => {
		await canvas.findByText("No feedback yet");
	},
};
