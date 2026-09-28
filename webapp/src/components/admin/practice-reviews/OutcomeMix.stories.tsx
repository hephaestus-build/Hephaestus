import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, within } from "storybook/test";

import { withStandardPage } from "@/stories/decorators";
import { expectNoPageOverflow } from "@/stories/reflow";

import { practiceReviewOverview, reviewOverviewScope } from "./fixtures";
import { OutcomeBar, OutcomeLegend, OutcomeMix } from "./OutcomeMix";
import { feedbackSlots, markedIncorrectSlot, observationSlots } from "./review-outcomes";

const observationNoun = (total: number): string => (total === 1 ? "observation" : "observations");

/**
 * A total, then how it splits: a bar for the eye and a legend with the contract. The bar is a
 * picture of the legend and hidden from assistive technology, so the legend's words come from the
 * registry that owns each value, and each count opens the list of exactly the rows it counts.
 *
 * A flag such as "marked incorrect" overlaps the parts — an incorrect strength is still a strength —
 * so it is a legend entry the bar never draws and the total never adds.
 */
const meta = {
	component: OutcomeMix,
	subcomponents: { OutcomeBar, OutcomeLegend },
	parameters: { layout: "padded" },
	decorators: [
		withStandardPage,
		(Story) => (
			<div className="max-w-md">
				<Story />
			</div>
		),
	],
	tags: ["autodocs"],
	args: {
		workspaceSlug: "demo",
		slots: observationSlots(practiceReviewOverview.observations, reviewOverviewScope),
		flags: [
			markedIncorrectSlot(practiceReviewOverview.observationsInvalidated, reviewOverviewScope),
		],
		noun: observationNoun,
		label: "Observations by outcome",
		children: <p className="text-xs text-muted-foreground">The total over time draws here.</p>,
	},
	argTypes: {
		slots: { control: false },
		flags: { control: false },
		noun: { control: false },
		children: { control: false },
	},
} satisfies Meta<typeof OutcomeMix>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Observations: Story = {
	play: async ({ canvas }) => {
		// The flag is listed but not added: 35 + 27 + 10 + 6, not + 3.
		canvas.getByText("78");
		canvas.getByText("observations");
		canvas.getByText("The total over time draws here.");
		const legend = within(canvas.getByRole("list", { name: "Observations by outcome" }));
		await expect(legend.getAllByRole("listitem").map((item) => item.textContent)).toEqual([
			"35 strengths",
			"27 improvements",
			"10 not applicable",
			"6 undetermined",
			"3 marked incorrect",
		]);
		const strengths = new URL(
			legend.getByRole<HTMLAnchorElement>("link", { name: "35 strengths" }).href,
		);
		await expect(strengths.pathname).toBe("/w/demo/admin/practices/reviews/observations");
		await expect(strengths.searchParams.get("outcome")).toBe('["POSITIVE"]');
		await expect(strengths.searchParams.get("from")).toBe(reviewOverviewScope.from);
		const incorrect = new URL(
			legend.getByRole<HTMLAnchorElement>("link", { name: "3 marked incorrect" }).href,
		);
		await expect(incorrect.searchParams.get("invalidated")).toBe("true");
	},
};

/** Zeroes are left out of the legend: ten noughts would hide the numbers that matter. */
export const Feedback: Story = {
	args: {
		slots: feedbackSlots(practiceReviewOverview.feedback, reviewOverviewScope),
		flags: undefined,
		noun: (total) => (total === 1 ? "piece of feedback" : "pieces of feedback"),
		label: "Feedback by delivery state",
	},
	play: async ({ canvas }) => {
		canvas.getByText("24");
		const legend = within(canvas.getByRole("list", { name: "Feedback by delivery state" }));
		await expect(legend.getAllByRole("listitem").map((item) => item.textContent)).toEqual([
			"3 awaiting approval",
			"14 delivered",
			"1 prepared",
			"1 replaced by newer",
			"3 withheld",
			"1 failed to deliver",
			"1 rejected",
		]);
		await expect(
			new URL(legend.getByRole<HTMLAnchorElement>("link", { name: "3 awaiting approval" }).href)
				.pathname,
		).toBe("/w/demo/admin/practices/reviews/feedback");
	},
};

/**
 * Feedback has no practice filter, so a practice's feedback counts build no list to open and read
 * as words: a link would open rows the count never counted.
 */
export const WordsOnly: Story = {
	args: {
		slots: feedbackSlots(practiceReviewOverview.feedback, {
			...reviewOverviewScope,
			practiceSlug: "thin-controllers",
		}),
		flags: undefined,
		noun: (total) => (total === 1 ? "piece of feedback cited it" : "pieces of feedback cited it"),
		label: "Feedback citing this practice",
	},
	play: async ({ canvas }) => {
		const legend = within(canvas.getByRole("list", { name: "Feedback citing this practice" }));
		await expect(legend.getAllByRole("listitem")).toHaveLength(7);
		await expect(legend.queryAllByRole("link")).toHaveLength(0);
	},
};

/** Nothing counted: a nought, and neither what it splits over time, nor a bar, nor a legend. */
export const NothingCounted: Story = {
	args: {
		slots: observationSlots({ strengths: 0, problems: 0, notApplicable: 0, undetermined: 0 }),
		flags: [markedIncorrectSlot(0)],
	},
	play: async ({ canvas }) => {
		canvas.getByText("0");
		await expect(canvas.queryByText("The total over time draws here.")).not.toBeInTheDocument();
		await expect(canvas.queryByRole("list")).not.toBeInTheDocument();
	},
};

/** The legend wraps rather than pushing the page sideways. */
export const Reflow: Story = {
	args: {
		slots: feedbackSlots(practiceReviewOverview.feedback, reviewOverviewScope),
		flags: undefined,
		label: "Feedback by delivery state",
	},
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	play: async ({ canvas }) => {
		canvas.getByRole("list", { name: "Feedback by delivery state" });
		await expectNoPageOverflow();
	},
};
