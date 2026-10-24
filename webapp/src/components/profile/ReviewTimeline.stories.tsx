import type { Meta, StoryObj } from "@storybook/react-vite";
import { type ComponentProps, useState } from "react";
import { expect } from "storybook/test";

import type { ProfileReview } from "@/api/types.gen";
import {
	detailReview,
	listedResult,
	reviewRef,
	searchedAndFoundNothing,
} from "@/stories/practice-detail-story-mock-data";
import { conversation } from "@/stories/practice-profile-story-mock-data";
import { daysBefore } from "@/stories/story-clock";

import { ReviewTimeline } from "./ReviewTimeline";

const THREAD_REVIEW = "00000000-0000-0000-0000-000000000201";

const reviews: ProfileReview[] = [
	detailReview,
	{
		review: reviewRef(THREAD_REVIEW, conversation("#releases"), daysBefore(5)),
		positionOnWork: 1,
		totalOnWork: 1,
		results: [
			{
				...listedResult(searchedAndFoundNothing),
				id: "00000000-0000-0000-0000-000000000202",
				reviewId: THREAD_REVIEW,
				practiceSlug: "asks-answerable-questions",
				practiceName: "Ask questions a teammate can answer",
				summary: "The question leaves out the attempted fix",
			},
		],
		feedback: [],
	},
];

const meta = {
	component: ReviewTimeline,
	tags: ["autodocs"],
	parameters: { layout: "padded" },
	args: { reviews },
} satisfies Meta<typeof ReviewTimeline>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ canvas, userEvent }) => {
		const newest = canvas.getByRole("button", { name: /The refactor and the fix/u });
		const earlier = canvas.getByRole("button", { name: /The question leaves out/u });
		await expect(newest).toHaveAttribute("aria-expanded", "true");
		await expect(earlier).toHaveAttribute("aria-expanded", "true");
		await expect(canvas.getAllByRole("list", { name: "Problems" })).toHaveLength(2);
		await expect(canvas.getByText("Ask questions a teammate can answer")).toBeVisible();
		await userEvent.click(earlier);
		await expect(earlier).toHaveAttribute("aria-expanded", "false");
		await expect(canvas.getAllByRole("list", { name: "Problems" })).toHaveLength(1);
	},
};

export const OnThePracticeLevel: Story = {
	// The practice level opens one row on arrival, the newest (`observation-filter.ts`).
	args: {
		showPracticeName: false,
		openOnArrival: (result) => result.id === detailReview.results[0]?.id,
	},
	play: async ({ canvas, userEvent }) => {
		await expect(canvas.queryByText("Ask questions a teammate can answer")).not.toBeInTheDocument();
		await expect(canvas.getByText("The question leaves out the attempted fix")).toBeVisible();

		const newest = canvas.getByRole("button", { name: /The refactor and the fix/u });
		const earlier = canvas.getByRole("button", { name: /The question leaves out/u });
		await expect(newest).toHaveAttribute("aria-expanded", "true");
		await expect(earlier).toHaveAttribute("aria-expanded", "false");

		await userEvent.click(earlier);
		await expect(earlier).toHaveAttribute("aria-expanded", "true");
		await expect(newest).toHaveAttribute("aria-expanded", "true");
	},
};

export const Empty: Story = {
	args: { reviews: [] },
	play: async ({ canvas }) => {
		await expect(canvas.queryAllByRole("listitem")).toHaveLength(0);
	},
};

function MovingTarget(args: ComponentProps<typeof ReviewTimeline>) {
	const [target, setTarget] = useState(detailReview.results[0]?.id);
	return (
		<>
			<button type="button" onClick={() => setTarget("00000000-0000-0000-0000-000000000202")}>
				Move the target
			</button>
			<ReviewTimeline {...args} openOnArrival={(result) => result.id === target} />
		</>
	);
}

/**
 * The row the rule opens moves when a filter or a link moves its target: the new target opens and
 * the old one closes, though both rows stay in the list.
 */
export const ArrivalTargetMoves: Story = {
	args: { showPracticeName: false },
	render: (args) => <MovingTarget {...args} />,
	play: async ({ canvas, userEvent }) => {
		const newest = canvas.getByRole("button", { name: /The refactor and the fix/u });
		await expect(newest).toHaveAttribute("aria-expanded", "true");
		await userEvent.click(canvas.getByRole("button", { name: "Move the target" }));
		await expect(canvas.getByRole("button", { name: /The question leaves out/u })).toHaveAttribute(
			"aria-expanded",
			"true",
		);
		await expect(canvas.getByRole("button", { name: /The refactor and the fix/u })).toHaveAttribute(
			"aria-expanded",
			"false",
		);
	},
};
