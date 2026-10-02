import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, userEvent, within } from "storybook/test";

import {
	mockConversationReviewFields,
	mockConversationWorkType,
	mockDocumentReviewFields,
	mockDocumentWorkType,
	mockIssueReviewFields,
	mockIssueWorkType,
	mockMergeReviewFields,
	mockPullRequestReviewFields,
	mockPullRequestWorkType,
} from "@/mocks/fixtures/practice";
import { expectNoOverflowingElement } from "@/stories/reflow";
import { Stateful } from "@/stories/stateful";

import { outcome } from "./fixtures";
import {
	PracticeReviewSettingsEditor,
	type PracticeOccasionMode,
} from "./PracticeReviewSettingsEditor";

const meta = {
	component: PracticeReviewSettingsEditor,
	args: {
		options: mockPullRequestWorkType,
		reviewFields: mockPullRequestReviewFields,
		mode: "reviewed",
		onChange: fn(),
	},
	// Storybook's default docgen (`react-docgen`) does no type resolution, so a locally declared
	// string union arrives as an unknown type and infers a JSON object editor. Naming the three
	// states here is the difference between a control that switches the editor and a text box.
	argTypes: {
		mode: {
			control: "radio",
			options: ["reviewed", "human-review", "guidance-only"] satisfies PracticeOccasionMode[],
		},
	},
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	render: (args) => (
		<Stateful initial={args.reviewFields}>
			{(reviewFields, setBinding) => (
				<PracticeReviewSettingsEditor
					{...args}
					reviewFields={reviewFields}
					onChange={(next) => {
						args.onChange(next);
						setBinding(next);
					}}
				/>
			)}
		</Stateful>
	),
} satisfies Meta<typeof PracticeReviewSettingsEditor>;

export default meta;
type Story = StoryObj<typeof meta>;

/** One occasion: the moments, the draft question, and one evidence list — no card around them. */
export const PullRequestLifecycle: Story = {
	play: async ({ canvas }) => {
		const strip = within(canvas.getByRole("group", { name: "Reviews when" }));
		await expect(strip.getByRole("checkbox", { name: /^Opened/u })).toBeChecked();
		await expect(strip.getByRole("checkbox", { name: /^Merged/u })).not.toBeChecked();
		await expect(
			strip.getByRole("checkbox", { name: "New commits pushed every time" }),
		).toBeVisible();
		// Nothing numbers the occasion, because a practice only ever has the one.
		await expect(canvas.queryByText(/Occasion 1/u)).toBeNull();
		await expect(canvas.queryByRole("button", { name: /Add occasion/u })).toBeNull();
	},
};

export const IssueLifecycle: Story = {
	args: { options: mockIssueWorkType, reviewFields: mockIssueReviewFields },
	play: async ({ canvas }) => {
		const strip = within(canvas.getByRole("group", { name: "Reviews when" }));
		await expect(strip.getByRole("checkbox", { name: "Details changed every time" })).toBeChecked();
		// An issue is never a draft, so the question is not asked.
		await expect(canvas.queryByRole("checkbox", { name: "Draft" })).toBeNull();
	},
};

export const DocumentLifecycle: Story = {
	args: { options: mockDocumentWorkType, reviewFields: mockDocumentReviewFields },
	play: async ({ canvas }) => {
		const strip = within(canvas.getByRole("group", { name: "Reviews when" }));
		await expect(strip.getByRole("checkbox", { name: /^Published/u })).toBeChecked();
		await expect(strip.getByRole("checkbox", { name: "Content changed every time" })).toBeChecked();
		await expect(strip.getByRole("checkbox", { name: /^Archived/u })).not.toBeChecked();
	},
};

/** A conversation offers one moment, so the strip is one node and there are no bands to tell apart. */
export const ConversationHasOneMoment: Story = {
	args: { options: mockConversationWorkType, reviewFields: mockConversationReviewFields },
	play: async ({ canvas }) => {
		const strip = within(canvas.getByRole("group", { name: "Reviews when" }));
		await expect(strip.getAllByRole("checkbox")).toHaveLength(1);
		await expect(canvas.queryByText("Along the way")).toBeNull();
	},
};

/**
 * At the merge the threads are read whole, which is what licenses the review to say nobody ever
 * resolved one. A practice that wants a cheaper read at a different moment is a second practice.
 */
export const ReadingASourceWhole: Story = {
	args: { reviewFields: mockMergeReviewFields },
	play: async ({ canvas }) => {
		const strip = within(canvas.getByRole("group", { name: "Reviews when" }));
		await expect(strip.getByRole("checkbox", { name: /^Merged/u })).toBeChecked();
		await expect(
			within(canvas.getByRole("group", { name: "What this review reads" })).getByText(
				"· captured whole",
			),
		).toBeVisible();
	},
};

/**
 * Asking by hand is a second way in, not a moment nobody is allowed to tick: it is stated once, under
 * the evidence it reads, and never offered on the strip.
 */
export const AskingByHandIsNotAMoment: Story = {
	play: async ({ canvas }) => {
		const strip = within(canvas.getByRole("group", { name: "Reviews when" }));
		await expect(canvas.queryByRole("checkbox", { name: /Review requested by hand/u })).toBeNull();
		await expect(strip.queryByText(/ask for this review by hand/u)).toBeNull();
		await expect(canvas.getByText(/ask for this review by hand/u)).toBeVisible();
	},
};

/** Nothing reviews a practice that runs no review, so nothing promises a hand-asked one either. */
export const GuidanceOnlyPromisesNoHandAskedReview: Story = {
	args: {
		mode: "guidance-only",
		reviewFields: { ...mockPullRequestReviewFields, evidenceRequirements: [] },
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText(/reads nothing, because no review runs/u)).toBeVisible();
		await expect(canvas.queryByText(/ask for this review by hand/u)).toBeNull();
	},
};

export const IncludingDrafts: Story = {
	args: { reviewFields: { ...mockPullRequestReviewFields, reviewWhen: {} } },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("checkbox", { name: "Draft" })).toBeChecked();
	},
};

export const RecordedButNotReviewed: Story = {
	args: { mode: "human-review" },
	play: async ({ canvas }) => {
		await expect(canvas.getByText(/nothing is reviewed while the practice asks/u)).toBeVisible();
		// Asking by hand would not review it either while it waits for a human.
		await expect(canvas.queryByText(/ask for this review by hand/u)).toBeNull();
	},
};

export const WithRecentOutcomes: Story = {
	args: {
		outcome: outcome({
			practiceSlug: "clear-pr-description",
			considered: 12,
			skipped: 5,
			blockers: [
				{ sourceKind: "scm.pull-request.diff", reasonCode: "SOURCE_EMPTY", reviewsAffected: 4 },
				{
					sourceKind: "scm.pull-request.comments",
					reasonCode: "SOURCE_INCOMPLETE",
					reviewsAffected: 1,
				},
			],
		}),
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText("7 of 12 reviews ran")).toBeVisible();
	},
};

/**
 * A submit sends focus to the control that has to change, and the message has to travel with it: on
 * its own it is text somewhere else on a long form.
 */
export const Invalid: Story = {
	args: {
		reviewFields: { signals: [], evidenceRequirements: [], reviewWhen: {}, subject: "AUTHOR" },
		error: "Choose when this practice is reviewed.",
		errorFocusId: "practice-occasion-signals",
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("group", { name: "Reviews when" })).toHaveAccessibleDescription(
			"Choose when this practice is reviewed.",
		);
		// Describing both groups would make the message mean "something on this form is wrong".
		await expect(
			canvas.getByRole("group", { name: "What this review reads" }),
		).not.toHaveAccessibleDescription("Choose when this practice is reviewed.");
	},
};

export const InvalidReviewConditions: Story = {
	args: {
		reviewFields: { ...mockPullRequestReviewFields, reviewWhen: { draftStatus: [] } },
		error: "Choose review conditions supported by this kind of work.",
		errorFocusId: "practice-occasion-reviewWhen",
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("group", { name: "Draft status" })).toHaveAccessibleDescription(
			"Choose review conditions supported by this kind of work.",
		);
		await expect(
			canvas.getByRole("group", { name: "Reviews when" }),
		).not.toHaveAccessibleDescription("Choose review conditions supported by this kind of work.");
	},
};

/**
 * A practice saved while asking by hand still counted as an occasion. The moment is no longer
 * offered, so it is drawn from what was saved — hiding it would leave nobody able to untick it.
 */
export const AMomentTheWorkTypeNoLongerOffers: Story = {
	args: {
		reviewFields: {
			...mockPullRequestReviewFields,
			signals: [...mockPullRequestReviewFields.signals, "scm.pull_request.manual_review"],
		},
	},
	play: async ({ args, canvas }) => {
		const stray = canvas.getByRole("checkbox", { name: /^Review requested by hand/u });
		await expect(stray).toBeChecked();

		await userEvent.click(stray);

		await expect(args.onChange).toHaveBeenCalledWith({
			...mockPullRequestReviewFields,
			signals: mockPullRequestReviewFields.signals,
			evidenceRequirements: mockPullRequestReviewFields.evidenceRequirements,
		});
	},
};

export const ChoosingAMoment: Story = {
	play: async ({ args, canvas }) => {
		const strip = within(canvas.getByRole("group", { name: "Reviews when" }));
		await userEvent.click(strip.getByRole("checkbox", { name: /^Review submitted/u }));

		// Sorted on the way out, so an untouched practice does not come back looking edited.
		await expect(args.onChange).toHaveBeenCalledWith({
			...mockPullRequestReviewFields,
			signals: [
				"scm.pull_request.opened",
				"scm.pull_request.ready",
				"scm.pull_request.reviewed",
				"scm.pull_request.synchronized",
			],
			evidenceRequirements: mockPullRequestReviewFields.evidenceRequirements,
		});
	},
};

export const NarrowViewport: Story = {
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	play: async ({ canvasElement }) => {
		await expectNoOverflowingElement(canvasElement);
	},
};
