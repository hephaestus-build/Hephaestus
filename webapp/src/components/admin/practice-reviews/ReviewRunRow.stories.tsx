import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, within } from "storybook/test";

import type { ReviewRunSummary } from "@/api/types.gen";
import { expectNoPageOverflow } from "@/stories/reflow";

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

/**
 * One row of the Reviews list: the work's title as the only link, the facts that place it, what the
 * review produced, and its status in a slot whose width is the same on every row of a list.
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
	args: { workspaceSlug: "demo", search: {}, review: completed },
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

/** A finished review, with both tallies drawn in full — zeroes included. */
export const Completed: Story = {
	play: async ({ canvas }) => {
		canvas.getByRole("link", { name: "Cache the workspace member lookup on the review path" });
		const observations = canvas.getByRole("list", { name: "Observations" });
		await expect(within(observations).getAllByRole("listitem")).toHaveLength(4);
		await expect(observations).toHaveTextContent("0 not applicable");
		await expect(canvas.getByRole("list", { name: "Feedback" })).toHaveTextContent("2 delivered");
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
		await expect(canvas.queryByRole("list", { name: "Feedback" })).not.toBeInTheDocument();
	},
};

/**
 * The status slot keeps its width whatever the badge says, so the column holds down the list. At
 * narrow widths the reservation is dropped and the chips wrap instead of pushing the row off-screen.
 */
export const EveryOutcomeInOneList: Story = {
	parameters: { viewport: { defaultViewport: "reflow" } },
	render: (args) => (
		<>
			{[completed, conversation, running, failed].map((review) => (
				<ReviewRunRow key={review.id} {...args} review={review} />
			))}
		</>
	),
	play: async ({ canvas }) => {
		await expect(canvas.getAllByRole("listitem").length).toBeGreaterThanOrEqual(4);
		await expectNoPageOverflow();
	},
};
