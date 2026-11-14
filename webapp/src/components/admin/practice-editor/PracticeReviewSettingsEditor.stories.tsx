import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, userEvent, within } from "storybook/test";

import { expectNoOverflowingElement } from "@/stories/reflow";
import { Stateful } from "@/stories/stateful";

import {
	definitionOf,
	mockDocumentItemType,
	mockIssueItemType,
	mockPullRequestItemType,
	mockThreadItemType,
} from "./fixtures";
import { PracticeReviewSettingsEditor } from "./PracticeReviewSettingsEditor";

const { reviewOn, reads } = definitionOf();

const meta = {
	component: PracticeReviewSettingsEditor,
	args: {
		itemType: mockPullRequestItemType,
		value: { reviewOn, reads: reads.sources },
		reviewedBy: "HEPHAESTUS",
		feedbackOnWork: true,
		onChange: fn(),
	},
	argTypes: {
		reviewedBy: { control: "radio", options: ["HEPHAESTUS", "PEOPLE", "NOBODY"] },
	},
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	render: (args) => (
		<Stateful initial={args.value}>
			{(value, setValue) => (
				<PracticeReviewSettingsEditor
					{...args}
					value={value}
					onChange={(next) => {
						args.onChange(next);
						setValue(next);
					}}
				/>
			)}
		</Stateful>
	),
} satisfies Meta<typeof PracticeReviewSettingsEditor>;

export default meta;
type Story = StoryObj<typeof meta>;

export const PullRequest: Story = {
	play: async ({ canvas }) => {
		const strip = within(canvas.getByRole("group", { name: "Review on" }));
		await expect(strip.getByRole("checkbox", { name: /^Opened/u })).toBeChecked();
		await expect(strip.getByRole("checkbox", { name: /^Merged/u })).not.toBeChecked();
		await expect(canvas.getByRole("checkbox", { name: "Ready for review" })).toBeChecked();
		await expect(canvas.getByRole("group", { name: "Reads" })).toBeVisible();
		// Feedback can appear on a pull request, so the editor names no other place.
		await expect(canvas.queryByText(/never appears on the work itself/u)).toBeNull();
	},
};

export const Issue: Story = {
	args: {
		itemType: mockIssueItemType,
		value: {
			reviewOn: { events: ["scm.issue.opened", "scm.issue.updated"], state: {} },
			reads: [{ source: "scm.issue.core", need: "REQUIRED" }],
		},
	},
	play: async ({ canvas }) => {
		const strip = within(canvas.getByRole("group", { name: "Review on" }));
		await expect(strip.getByRole("checkbox", { name: "Details changed every time" })).toBeChecked();
		// An issue is never a draft, so the question is not asked.
		await expect(canvas.queryByRole("checkbox", { name: "Draft" })).toBeNull();
	},
};

export const Document: Story = {
	args: {
		itemType: mockDocumentItemType,
		feedbackOnWork: false,
		value: {
			reviewOn: { events: ["docs.document.published"], state: {} },
			reads: [{ source: "docs.document.core", need: "REQUIRED" }],
		},
	},
	play: async ({ canvas }) => {
		const strip = within(canvas.getByRole("group", { name: "Review on" }));
		await expect(strip.getByRole("checkbox", { name: /^Published/u })).toBeChecked();
		await expect(strip.getByRole("checkbox", { name: /^Archived/u })).not.toBeChecked();
		// Outline gets no feedback, so the editor says where it goes instead.
		await expect(canvas.getByText(/never appears on the work itself/u)).toBeVisible();
	},
};

export const ConversationHasOneEvent: Story = {
	args: {
		itemType: mockThreadItemType,
		feedbackOnWork: false,
		value: {
			reviewOn: { events: ["chat.thread.settled"], state: {} },
			reads: [{ source: "slack.conversation.thread", need: "REQUIRED" }],
		},
	},
	play: async ({ canvas }) => {
		const strip = within(canvas.getByRole("group", { name: "Review on" }));
		await expect(strip.getAllByRole("checkbox")).toHaveLength(1);
		await expect(canvas.queryByText("Along the way")).toBeNull();
		await expect(canvas.getByText(/never appears on the work itself/u)).toBeVisible();
	},
};

export const ReadingASourceWhole: Story = {
	args: {
		value: {
			reviewOn: { events: ["scm.pull_request.merged"], state: {} },
			reads: [
				{ source: "scm.pull-request.core", need: "REQUIRED" },
				{ source: "scm.repository.tree", need: "OPTIONAL" },
				{ source: "scm.review-threads", need: "COMPLETE" },
			],
		},
	},
	play: async ({ canvas }) => {
		await expect(
			within(canvas.getByRole("group", { name: "Reads" })).getByText("· captured whole"),
		).toBeVisible();
	},
};

export const ARequestIsNotAnEvent: Story = {
	play: async ({ canvas }) => {
		const strip = within(canvas.getByRole("group", { name: "Review on" }));
		await expect(strip.queryByText(/request this review/u)).toBeNull();
		await expect(canvas.getByText(/request this review on the work itself/u)).toBeVisible();
	},
};

export const ReviewedByPeople: Story = {
	args: { reviewedBy: "PEOPLE" },
	play: async ({ canvas }) => {
		await expect(
			canvas.getByText("No automated review runs, so this practice reads nothing."),
		).toBeVisible();
		await expect(canvas.queryByRole("group", { name: "Reads" })).toBeNull();
		await expect(canvas.queryByText(/request this review/u)).toBeNull();
	},
};

/**
 * A submit sends focus to the control that has to change, and the message has to travel with it: on
 * its own it is text somewhere else on a long form.
 */
export const Invalid: Story = {
	args: {
		value: { reviewOn, reads: [] },
		error: "Set at least one source to Required. A review cannot run without one.",
		errorFocusId: "practice-review-on-reads",
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("group", { name: "Reads" })).toHaveAccessibleDescription(
			"Set at least one source to Required. A review cannot run without one.",
		);
		// Describing both groups would make the message mean "something on this form is wrong".
		await expect(canvas.getByRole("group", { name: "Review on" })).not.toHaveAccessibleDescription(
			"Set at least one source to Required. A review cannot run without one.",
		);
	},
};

export const InvalidState: Story = {
	args: {
		value: {
			reviewOn: { ...reviewOn, state: { draftStatus: ["ARCHIVED"] } },
			reads: reads.sources,
		},
		error: "Choose states this kind of work offers.",
		errorFocusId: "practice-review-on-state",
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("group", { name: "When" })).toHaveAccessibleDescription(
			"Choose states this kind of work offers.",
		);
	},
};

export const ChoosingAnEvent: Story = {
	play: async ({ args, canvas }) => {
		const strip = within(canvas.getByRole("group", { name: "Review on" }));
		await userEvent.click(strip.getByRole("checkbox", { name: /^Merged/u }));

		// Sorted on the way out, so an untouched practice does not come back looking edited.
		await expect(args.onChange).toHaveBeenCalledWith({
			reviewOn: {
				events: ["scm.pull_request.merged", "scm.pull_request.opened", "scm.pull_request.ready"],
				state: reviewOn.state,
			},
			reads: reads.sources,
		});
	},
};

export const NarrowViewport: Story = {
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	play: async ({ canvasElement }) => {
		await expectNoOverflowingElement(canvasElement);
	},
};
