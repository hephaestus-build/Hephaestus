import type { Meta, StoryObj } from "@storybook/react-vite";
import type { ComponentProps } from "react";
import { expect, within } from "storybook/test";

import { useStoryFeedbackResponses } from "@/stories/feedback-ratings";
import { detailFeedback, ownFeedback } from "@/stories/practice-detail-story-mock-data";
import { daysBefore } from "@/stories/story-clock";
import { expectGenuinelyDisabled } from "@/test/controls";

import { OwnFeedbackList } from "./OwnFeedbackList";

function Answerable({
	pending = false,
	...props
}: ComponentProps<typeof OwnFeedbackList> & { pending?: boolean }) {
	const responses = useStoryFeedbackResponses({ pending });
	return <OwnFeedbackList {...props} responses={responses} />;
}

const ratedHelpful = {
	...detailFeedback,
	response: { usefulness: "HELPFUL", resolution: null, comment: null, createdAt: daysBefore(1) },
} satisfies ComponentProps<typeof OwnFeedbackList>["feedback"][number];

const meta = {
	component: OwnFeedbackList,
	tags: ["autodocs"],
	parameters: { layout: "padded" },
	args: { feedback: [ratedHelpful] },
	render: (args) => <Answerable {...args} />,
} satisfies Meta<typeof OwnFeedbackList>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ canvas }) => {
		await expect(canvas.getByText("On the work")).toBeVisible();
		await expect(
			canvas.getByRole("link", { name: /^Read feedback comment 1(?: \(opens in a new tab\))?$/u }),
		).toHaveAttribute("href", detailFeedback.copies[0]?.externalUrl);
		await expect(
			canvas.getByRole("link", { name: /^Read feedback comment 2(?: \(opens in a new tab\))?$/u }),
		).toHaveAttribute("href", detailFeedback.copies[1]?.externalUrl);
		const response = canvas.getByRole("group", {
			name: "Your response to the feedback on the work",
		});
		await expect(
			within(response)
				.getAllByRole("button")
				.map((button) => button.textContent),
		).toStrictEqual(["Addressed", "Disagree", "Not applicable", "Clear response"]);
		await expect(canvas.getByText("Helpful")).toBeVisible();
		await expect(canvas.queryByRole("textbox")).toBeNull();
	},
};

/**
 * Feedback that never reached the reader is not listed: a withheld, awaiting, rejected or failed
 * piece is not shown as posted on the work, even with a recorded comment link.
 */
export const OnlyDeliveredFeedback: Story = {
	args: {
		feedback: [
			ratedHelpful,
			...(["WITHHELD", "AWAITING_APPROVAL", "REJECTED", "FAILED"] as const).map((state, index) =>
				ownFeedback(`00000000-0000-4000-8000-0000000006a${index}`, {
					state,
					deliveredAt: null,
					copies: detailFeedback.copies,
				}),
			),
		],
	},
	play: async ({ canvas }) => {
		const list = canvas.getByRole("list", { name: "Your feedback" });
		await expect(list.querySelectorAll(":scope > li")).toHaveLength(1);
		await expect(canvas.getAllByText("On the work")).toHaveLength(1);
	},
};

/** Nothing reached the reader: no list at all, not an empty one. */
export const NothingDelivered: Story = {
	args: {
		feedback: [
			ownFeedback("00000000-0000-4000-8000-0000000006b0", { state: "WITHHELD", deliveredAt: null }),
		],
	},
	play: async ({ canvas }) => {
		await expect(canvas.queryByRole("list", { name: "Your feedback" })).toBeNull();
	},
};

export const RecordsAResponse: Story = {
	play: async ({ canvas, userEvent }) => {
		await userEvent.click(canvas.getByRole("button", { name: "Addressed" }));
		const field = canvas.getByRole("textbox", { name: "Anything to add?" });
		await expect(field).not.toBeRequired();
		await userEvent.click(canvas.getByRole("button", { name: "Skip" }));
		await expect(canvas.getByRole("button", { name: "Addressed" })).toHaveAttribute(
			"aria-pressed",
			"true",
		);
		await expect(canvas.getByText("Helpful")).toBeVisible();
		await expect(canvas.queryByRole("textbox")).toBeNull();

		await userEvent.click(canvas.getByRole("button", { name: "Not applicable" }));
		await userEvent.type(
			canvas.getByRole("textbox", { name: "Anything to add?" }),
			"Adopted in the follow-up.",
		);
		await userEvent.click(canvas.getByRole("button", { name: "Send" }));
		await expect(canvas.getByRole("button", { name: "Not applicable" })).toHaveAttribute(
			"aria-pressed",
			"true",
		);
		await expect(canvas.getByText("Adopted in the follow-up.")).toBeVisible();
		await expect(canvas.getByText("Helpful")).toBeVisible();
	},
};

export const DisagreementNeedsAReason: Story = {
	play: async ({ canvas, userEvent }) => {
		await userEvent.click(canvas.getByRole("button", { name: "Disagree" }));
		const field = canvas.getByRole("textbox", { name: "Why do you disagree?" });
		await expect(field).toBeRequired();
		await expect(field).toHaveAccessibleDescription(
			/Add your reason so a workspace admin can read it\./u,
		);
		await userEvent.click(canvas.getByRole("button", { name: "Send" }));
		await expect(canvas.getByRole("textbox", { name: "Why do you disagree?" })).toBeVisible();
		await userEvent.type(field, "The value is derived, not chosen.");
		await userEvent.click(canvas.getByRole("button", { name: "Send" }));
		await expect(canvas.getByRole("button", { name: "Disagree" })).toHaveAttribute(
			"aria-pressed",
			"true",
		);
		await expect(canvas.getByText("The value is derived, not chosen.")).toBeVisible();
		await expect(canvas.getByText("Helpful")).toBeVisible();
	},
};

export const ClearResponse: Story = {
	args: {
		feedback: [
			{
				...detailFeedback,
				response: {
					usefulness: "HELPFUL",
					resolution: "ADDRESSED",
					comment: "Split the change into two commits.",
					createdAt: daysBefore(1),
				},
			},
		],
	},
	play: async ({ canvas, userEvent }) => {
		await expect(canvas.getByRole("button", { name: "Addressed" })).toHaveAttribute(
			"aria-pressed",
			"true",
		);
		await expect(canvas.getByText("Split the change into two commits.")).toBeVisible();
		await userEvent.click(canvas.getByRole("button", { name: "Clear response" }));
		await expect(canvas.getByRole("button", { name: "Addressed" })).toHaveAttribute(
			"aria-pressed",
			"false",
		);
		await expect(canvas.queryByText("Helpful")).toBeNull();
		await expect(canvas.queryByText("Split the change into two commits.")).toBeNull();
		await expect(canvas.queryByRole("button", { name: "Clear response" })).toBeNull();
	},
};

export const ResponsePending: Story = {
	render: (args) => <Answerable {...args} pending />,
	play: async ({ canvas, userEvent }) => {
		await userEvent.click(canvas.getByRole("button", { name: "Not applicable" }));
		await userEvent.click(canvas.getByRole("button", { name: "Skip" }));
		const response = canvas.getByRole("group", {
			name: "Your response to the feedback on the work",
		});
		await expect(within(response).getByRole("button", { name: "Not applicable" })).toHaveAttribute(
			"aria-pressed",
			"true",
		);
		for (const button of within(response).getAllByRole("button")) {
			await expectGenuinelyDisabled(button);
		}
	},
};

export const SeveralPieces: Story = {
	args: {
		feedback: [
			ratedHelpful,
			ownFeedback("00000000-0000-0000-0000-0000000001d1", { destination: "PRACTICE_PAGE" }),
		],
	},
	play: async ({ canvas }) => {
		await expect(
			canvas.getAllByRole("group", { name: /^Your response to the feedback/u }),
		).toHaveLength(2);
		await expect(canvas.getAllByRole("link")).toHaveLength(2);
	},
};

export const ReadOnly: Story = {
	render: (args) => <OwnFeedbackList {...args} />,
	play: async ({ canvas }) => {
		await expect(canvas.getAllByRole("link")).toHaveLength(2);
		await expect(canvas.queryByRole("group")).toBeNull();
		await expect(canvas.queryByRole("button")).toBeNull();
	},
};

export const Empty: Story = {
	args: { feedback: [] },
	play: async ({ canvasElement }) => {
		await expect(canvasElement.querySelector("ul")).toBeNull();
	},
};
