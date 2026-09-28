import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, screen, within } from "storybook/test";

import type { ReviewRunSummary } from "@/api/types.gen";
import { expectSettledVisible } from "@/stories/overlay";
import { expectNoPageOverflow } from "@/stories/reflow";
import { levelsOpenedBy } from "@/test/detail-stack";

import { reviewRuns } from "./fixtures";
import { ReviewRowList } from "./ReviewRow";
import { ReviewRunRow } from "./ReviewRunRow";

function run(id: string): ReviewRunSummary {
	const found = reviewRuns.find((review) => review.id === id);
	if (!found) {
		throw new Error(`No review ${id} in the fixture`);
	}
	return found;
}

const completed = run("11111111-1111-1111-1111-111111111111");
const conversation = run("33333333-3333-3333-3333-333333333333");
const running = run("aaaaaaaa-8888-8888-8888-888888888888");
const failed = run("bbbbbbbb-8888-8888-8888-888888888888");
const processingFailed: ReviewRunSummary = { ...completed, resultProcessing: "FAILED" };

/**
 * One row of the Reviews list: the review's status as the leading icon, the work's title as the only
 * link, the facts that place it, and what the review produced.
 *
 * The tally is the part worth reading closely. A review that is still going and a review that
 * stopped early both have nothing to count, and neither gets a strip of zeroes — a strip means "this
 * finished and these are the numbers", so showing one for a run in flight would read as a finished
 * review that found nothing.
 */
const meta = {
	component: ReviewRunRow,
	parameters: { layout: "padded", chromatic: { viewports: [320, 1440] } },
	tags: ["autodocs"],
	args: { review: completed },
	decorators: [
		(Story) => (
			<ReviewRowList label="Practice reviews, newest first">
				<Story />
			</ReviewRowList>
		),
	],
} satisfies Meta<typeof ReviewRunRow>;

export default meta;
type Story = StoryObj<typeof meta>;

/**
 * A finished review: its observations as a strip that keeps its zeroes, so the four results line up
 * down the list, and its feedback as a sentence of only what happened.
 */
export const Completed: Story = {
	play: async ({ canvas, userEvent }) => {
		// The status is the icon, named and explained on hover or focus, and not repeated as a badge.
		// It is the row's first stop for a keyboard, since nothing else on the row says the words.
		const status = canvas.getByRole("button", { name: "Completed" });
		await expect(canvas.queryByText("Completed")).not.toBeInTheDocument();
		await userEvent.tab();
		await expect(status).toHaveFocus();
		await expectSettledVisible(await screen.findByText(/It ran to the end/u));
		// The title opens the review over the list rather than leaving it.
		await expect(
			levelsOpenedBy(
				canvas.getByRole("link", { name: "Cache the workspace member lookup on the review path" }),
			),
		).toEqual([`review:${completed.id}`]);
		const observations = canvas.getByRole("list", { name: "Observations" });
		await expect(within(observations).getAllByRole("listitem")).toHaveLength(4);
		await expect(observations).toHaveTextContent("0 not applicable");
		canvas.getByText("Feedback: 2 delivered · 2 withheld");
	},
};

/** The reviewed work is not always a pull request, and the row says which kind it was. */
export const AConversation: Story = {
	args: { review: conversation },
	play: async ({ canvas }) => {
		canvas.getByRole("link", { name: "How should we roll back the pricing migration?" });
		canvas.getByText(/engineering/u);
	},
};

/**
 * A review can run to the end and still fail to process what it produced. The status says it
 * completed, so the failure is the one qualifier the row carries.
 */
export const ResultProcessingFailed: Story = {
	args: { review: processingFailed },
	play: async ({ canvas }) => {
		canvas.getByRole("button", { name: "Completed" });
		canvas.getByText("Result processing failed");
	},
};

/** Nothing to count yet — and saying so beats printing five noughts that mean "not yet". */
export const StillRunning: Story = {
	args: { review: running },
	play: async ({ canvas }) => {
		canvas.getByText("Results appear as it finishes.");
		await expect(canvas.queryByRole("list", { name: "Observations" })).not.toBeInTheDocument();
	},
};

/** Nothing to count ever. The same absence, and deliberately not the same sentence. */
export const StoppedWithNothing: Story = {
	args: { review: failed },
	play: async ({ canvas }) => {
		canvas.getByText("It produced nothing before it stopped.");
		await expect(canvas.queryByRole("list", { name: "Observations" })).not.toBeInTheDocument();
	},
};

/** Every status in one list, at the reflow width, where the qualifier wraps under the facts. */
export const EveryOutcomeInOneList: Story = {
	parameters: { viewport: { defaultViewport: "reflow" } },
	render: (args) => (
		<>
			{[processingFailed, conversation, running, failed].map((review) => (
				<ReviewRunRow key={review.id} {...args} review={review} />
			))}
		</>
	),
	play: async ({ canvas }) => {
		for (const status of ["Completed", "Running", "Failed"]) {
			await expect(canvas.getAllByRole("button", { name: status }).length).toBeGreaterThan(0);
		}
		await expectNoPageOverflow();
	},
};

/**
 * The review open in the panel beside the list is the list's current item, and its row keeps a bar
 * on its leading edge, so the list says which row the panel belongs to; the other rows are neither.
 */
export const OpenInThePanel: Story = {
	parameters: {
		router: {
			initialUrl: `/?detail=${encodeURIComponent(JSON.stringify([`review:${completed.id}`]))}`,
		},
	},
	render: (args) => (
		<>
			{[completed, conversation, running].map((review) => (
				<ReviewRunRow key={review.id} {...args} review={review} />
			))}
		</>
	),
	play: async ({ canvas }) => {
		const open = await canvas.findByRole("link", {
			name: "Cache the workspace member lookup on the review path",
		});
		await expect(open).toHaveAttribute("aria-current", "page");
		for (const title of ["How should we roll back the pricing migration?", running.target.title]) {
			await expect(canvas.getByRole("link", { name: title })).not.toHaveAttribute("aria-current");
		}
	},
};
