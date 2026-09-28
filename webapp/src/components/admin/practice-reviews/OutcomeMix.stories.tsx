import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, within } from "storybook/test";

import { withStandardPage } from "@/stories/decorators";
import { expectNoPageOverflow } from "@/stories/reflow";

import { practiceReviewOverview, reviewOverviewScope } from "./fixtures";
import { OutcomeMix } from "./OutcomeMix";
import {
	feedbackSlots,
	markedIncorrectSlot,
	observationSlots,
	slotsTotal,
} from "./review-outcomes";

const observationNoun = (total: number): string => (total === 1 ? "observation" : "observations");
const feedbackNoun = (total: number): string =>
	total === 1 ? "piece of feedback" : "pieces of feedback";

const OBSERVATIONS = observationSlots(practiceReviewOverview.observations, reviewOverviewScope);
const FEEDBACK = feedbackSlots(practiceReviewOverview.feedback, reviewOverviewScope);

/**
 * A total, how it changed, then what it splits into — as counts in words, never as a bar: each
 * count wears the words, icon and tone of the registry that owns its value, and opens the list of
 * exactly the rows it counts. A count that opens a list carries a faint underline at rest.
 *
 * A flag such as "marked incorrect" overlaps the parts — a positive outcome marked incorrect is
 * still a positive outcome — so it is listed apart, under its own name, and never added.
 */
const meta = {
	component: OutcomeMix,
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
		total: slotsTotal(OBSERVATIONS),
		slots: OBSERVATIONS,
		flags: {
			label: "Observations checked by an admin",
			slots: [
				markedIncorrectSlot(practiceReviewOverview.observationsInvalidated, reviewOverviewScope),
			],
		},
		noun: observationNoun,
		label: "Observations by outcome",
		delta: "12 more than the previous 30 days",
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
		const legend = within(canvas.getByRole("list", { name: "Observations by outcome" }));
		await expect(legend.getAllByRole("listitem").map((item) => item.textContent)).toEqual([
			"35 positive outcomes",
			"27 negative outcomes",
			"10 not applicable",
			"6 undetermined",
		]);
		const positive = new URL(
			legend.getByRole<HTMLAnchorElement>("link", { name: "35 positive outcomes" }).href,
		);
		await expect(positive.pathname).toBe("/w/demo/admin/practices/reviews/observations");
		await expect(positive.searchParams.get("outcome")).toBe('["POSITIVE"]');
		await expect(positive.searchParams.get("from")).toBe(reviewOverviewScope.from);
		const flags = within(canvas.getByRole("list", { name: "Observations checked by an admin" }));
		const incorrect = new URL(
			flags.getByRole<HTMLAnchorElement>("link", { name: "3 marked incorrect" }).href,
		);
		await expect(incorrect.searchParams.get("invalidated")).toBe("true");
	},
};

/**
 * Feedback by delivery family. Each family opens the list filtered to every state it stands for,
 * and a family with none in it is left out.
 */
export const Feedback: Story = {
	args: {
		total: slotsTotal(FEEDBACK),
		slots: FEEDBACK,
		flags: undefined,
		noun: feedbackNoun,
		label: "Feedback by delivery",
		delta: undefined,
		children: undefined,
	},
	play: async ({ canvas }) => {
		const legend = within(canvas.getByRole("list", { name: "Feedback by delivery" }));
		await expect(legend.getAllByRole("listitem").map((item) => item.textContent)).toEqual([
			"3 awaiting approval",
			"1 prepared",
			"14 delivered",
			"5 withheld",
			"1 failed to deliver",
		]);
		const withheld = new URL(
			legend.getByRole<HTMLAnchorElement>("link", { name: "5 withheld" }).href,
		);
		await expect(withheld.pathname).toBe("/w/demo/admin/practices/reviews/feedback");
		await expect(withheld.searchParams.get("deliveryState")).toBe(
			'["SUPPRESSED","DISCARDED","SUPERSEDED"]',
		);
	},
};

/** A practice's feedback opens the feedback citing that practice. */
export const OnePractice: Story = {
	args: {
		...Feedback.args,
		slots: feedbackSlots(practiceReviewOverview.feedback, {
			...reviewOverviewScope,
			practiceSlug: "thin-controllers",
		}),
	},
	play: async ({ canvas }) => {
		const legend = within(canvas.getByRole("list", { name: "Feedback by delivery" }));
		const delivered = new URL(
			legend.getByRole<HTMLAnchorElement>("link", { name: "14 delivered" }).href,
		);
		await expect(delivered.searchParams.get("practiceSlug")).toBe('["thin-controllers"]');
		await expect(delivered.searchParams.get("deliveryState")).toBe(
			'["DELIVERED","PARTIALLY_DELIVERED"]',
		);
	},
};

/** Counts built with no range name no list, and read as words. */
export const WordsOnly: Story = {
	args: {
		...Feedback.args,
		slots: feedbackSlots(practiceReviewOverview.feedback),
		label: "Feedback by delivery",
	},
	play: async ({ canvas }) => {
		const legend = within(canvas.getByRole("list", { name: "Feedback by delivery" }));
		await expect(legend.getAllByRole("listitem")).toHaveLength(5);
		await expect(legend.queryAllByRole("link")).toHaveLength(0);
	},
};

/** Nothing counted: a nought and no legend. */
export const NothingCounted: Story = {
	args: {
		total: 0,
		slots: observationSlots({ strengths: 0, problems: 0, notApplicable: 0, undetermined: 0 }),
		flags: { label: "Observations checked by an admin", slots: [markedIncorrectSlot(0)] },
		delta: "Same as the previous 30 days",
		children: undefined,
	},
	play: async ({ canvas }) => {
		canvas.getByText("0");
		canvas.getByText("Same as the previous 30 days");
		await expect(canvas.queryByRole("list")).not.toBeInTheDocument();
	},
};

/** The legend wraps rather than pushing the page sideways. */
export const Reflow: Story = {
	args: Feedback.args,
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	play: async ({ canvas }) => {
		canvas.getByRole("list", { name: "Feedback by delivery" });
		await expectNoPageOverflow();
	},
};
