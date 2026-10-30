import type { Meta, StoryObj } from "@storybook/react-vite";
import type { ComponentProps } from "react";
import { expect, fn } from "storybook/test";

import { useStoryFeedbackResponses } from "@/stories/feedback-ratings";
import { detailReviews } from "@/stories/practice-detail-story-mock-data";

import { ItemFeedbackPage } from "./ItemFeedbackPage";
import type { ReviewFeedState } from "./reviews";

function Answerable(props: ComponentProps<typeof ItemFeedbackPage>) {
	const responses = useStoryFeedbackResponses();
	return <ItemFeedbackPage {...props} responses={responses} />;
}

const readyFeed = {
	status: "ready",
	reviews: detailReviews,
	hasMore: false,
	isLoadingMore: false,
	onLoadMore: fn(),
} satisfies ReviewFeedState;

const meta = {
	component: ItemFeedbackPage,
	tags: ["autodocs"],
	parameters: { layout: "padded" },
	args: { feed: readyFeed },
	render: (args) => <Answerable {...args} />,
} satisfies Meta<typeof ItemFeedbackPage>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ canvas }) => {
		// The reader arrives from a comment on the work, so the page names which work it is.
		await expect(
			canvas.getByRole("heading", { level: 1, name: "Your feedback on pull request #902" }),
		).toBeVisible();
		await expect(canvas.getAllByRole("link", { name: /^#902\b/u })[0]).toHaveAttribute(
			"href",
			detailReviews[0]?.review.item.url ?? "",
		);
		await expect(canvas.getByText(/Workspace admins read your reason/u)).toBeVisible();
		await expect(canvas.getByRole("list", { name: "Reviews" })).toBeVisible();
		await expect(
			canvas.getAllByRole("group", { name: /^Your response to the feedback/u }),
		).toHaveLength(2);
	},
};

/** A reader who arrives from a comment on the work finds the way to all their feedback. */
export const WithProfileLink: Story = {
	args: { profileLink: <a href="#practice-profile">See all your feedback</a> },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("link", { name: "See all your feedback" })).toBeVisible();
	},
};

export const NoReadableResults: Story = {
	args: { feed: { ...readyFeed, reviews: [] } },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("No observations are available here")).toBeVisible();
		canvas.getByText(/Observations can be hidden/u);
		await expect(canvas.queryByText(/written for somebody else/u)).toBeNull();
		await expect(canvas.queryByRole("list", { name: "Reviews" })).toBeNull();
	},
};

export const Loading: Story = {
	args: { feed: { status: "loading" } },
	play: async ({ canvas }) => {
		canvas.getByText("Loading reviews…");
		await expect(canvas.queryByRole("list", { name: "Reviews" })).toBeNull();
	},
};

export const LoadFailed: Story = {
	args: {
		feed: {
			status: "error",
			error: { status: 500, detail: "The review history is temporarily unavailable." },
			onRetry: fn(),
		},
	},
	play: async ({ args, canvas, userEvent }) => {
		await expect(canvas.getByText("We could not load reviews")).toBeVisible();
		await userEvent.click(canvas.getByRole("button", { name: "Retry" }));
		if (args.feed.status !== "error") {
			throw new Error("This story is the error branch");
		}
		await expect(args.feed.onRetry).toHaveBeenCalledOnce();
	},
};

export const ReadOnly: Story = {
	render: (args) => <ItemFeedbackPage {...args} />,
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("list", { name: "Reviews" })).toBeVisible();
		await expect(canvas.queryByRole("group")).toBeNull();
		// No responses here, so the page does not tell the reader how to give one.
		await expect(canvas.queryByText(/Mark feedback addressed/u)).toBeNull();
		await expect(canvas.queryByText(/Workspace admins read your reason/u)).toBeNull();
	},
};
