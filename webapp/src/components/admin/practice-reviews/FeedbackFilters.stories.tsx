import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, within } from "storybook/test";

import { withStandardPage } from "@/stories/decorators";
import { StatefulPatch } from "@/stories/stateful";

import { FeedbackFilters } from "./FeedbackFilters";
import type { FeedbackSearch } from "./review-search";
import type { ReviewPeople } from "./ReviewPersonFacet";
import { reviewArtifact, workspaceMembers } from "./story-mock-data";

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
 * The Delivery list's toolbar, on its own. It reports a patch and renders what it is given; which
 * rows come back is the route's business, so every state here is a `search` value rather than a
 * response.
 */
const meta = {
	component: FeedbackFilters,
	parameters: { layout: "padded", chromatic: { viewports: [320, 1440] } },
	decorators: [withStandardPage],
	tags: ["autodocs"],
	args: {
		search: { deliveryState: undefined, withheldFamily: undefined, channel: undefined },
		onPatch: fn(),
		onReset: fn(),
		people: PEOPLE,
		total: 11,
	},
	// Controlled: `selected` on every facet comes back through the same `search` the choice is
	// reported on, so a frozen value would leave the whole toolbar looking dead.
	render: (args) => (
		<StatefulPatch<FeedbackSearch> initial={args.search}>
			{(search, patch) => (
				<FeedbackFilters
					{...args}
					search={search}
					onPatch={(next) => {
						patch(next);
						args.onPatch(next);
					}}
					onReset={() => {
						patch({
							deliveryState: undefined,
							withheldFamily: undefined,
							channel: undefined,
							agentJobId: undefined,
							artifactKind: undefined,
							recipientUserId: undefined,
						});
						args.onReset();
					}}
				/>
			)}
		</StatefulPatch>
	),
} satisfies Meta<typeof FeedbackFilters>;

export default meta;
type Story = StoryObj<typeof meta>;

/** Nothing set: no Reset, and the count is the whole list rather than what survived a filter. */
export const Unfiltered: Story = {
	play: async ({ canvas }) => {
		canvas.getByText("11 pieces of feedback.");
		await expect(canvas.queryByRole("button", { name: "Reset" })).not.toBeInTheDocument();
	},
};

/** Reset appears as soon as anything is set, and the count changes its wording with it. */
export const FilteredCountReadsDifferently: Story = {
	args: {
		search: { deliveryState: ["DELIVERED"], withheldFamily: undefined, channel: undefined },
		total: 3,
	},
	play: async ({ canvas }) => {
		canvas.getByText("3 pieces of feedback match your filters.");
		canvas.getByRole("button", { name: "Reset" });
	},
};

/** One row of feedback is still a sentence that agrees with itself. */
export const ExactlyOneMatch: Story = {
	args: {
		search: { deliveryState: ["DELIVERED"], withheldFamily: undefined, channel: undefined },
		total: 1,
	},
	play: async ({ canvas }) => {
		canvas.getByText("1 piece of feedback matches your filters.");
	},
};

export const ReportsAChosenOutcome: Story = {
	play: async ({ args, canvas, userEvent }) => {
		await userEvent.click(canvas.getByRole("combobox", { name: "Outcome" }));
		const listbox = await screen.findByRole("listbox", { name: "Outcome options" });
		await userEvent.click(await within(listbox).findByRole("option", { name: /Delivered/u }));
		await expect(args.onPatch).toHaveBeenCalledWith({ deliveryState: ["DELIVERED"] });
	},
};

/**
 * Arriving from a review or from a piece of work, the scope is a filter the reader did not set and
 * has to be able to see and drop. The work is named rather than printed as an id.
 */
export const ScopedToOnePieceOfWork: Story = {
	args: {
		search: {
			deliveryState: undefined,
			withheldFamily: undefined,
			channel: undefined,
			artifactKind: "scm.pull_request",
			artifactId: 42,
		},
		scopedArtifact: reviewArtifact,
		total: 4,
	},
	play: async ({ canvas }) => {
		canvas.getByText(/Reviewed work/u);
		canvas.getByText(/PR #1423/u);
	},
};

export const ScopedToOneReview: Story = {
	args: {
		search: {
			deliveryState: undefined,
			withheldFamily: undefined,
			channel: undefined,
			agentJobId: "11111111-1111-1111-1111-111111111111",
		},
		total: 4,
	},
	play: async ({ canvas }) => {
		canvas.getByText(/Review/u);
	},
};

/**
 * The person the list is filtered to may not be on the fetched member page; the facet names them from
 * the row that is on screen rather than showing a bare id.
 */
export const FilteredToSomebodyOffThePage: Story = {
	args: {
		search: {
			deliveryState: undefined,
			withheldFamily: undefined,
			channel: undefined,
			recipientUserId: 404,
		},
		recipientName: "Barbara Liskov",
		total: 2,
	},
	play: async ({ canvas }) => {
		await canvas.findByRole("combobox", { name: "Recipient: Barbara Liskov" });
	},
};

/** Below `sm` the facet chips collapse to a count, so the applied values need their own pill row. */
export const Mobile: Story = {
	args: {
		search: {
			deliveryState: ["DELIVERED", "SUPPRESSED"],
			withheldFamily: undefined,
			channel: undefined,
		},
		total: 7,
	},
	parameters: { chromatic: { viewports: [320] }, viewport: { defaultViewport: "reflow" } },
	play: async ({ canvas }) => {
		await canvas.findByTitle("Outcome: Delivered");
	},
};
