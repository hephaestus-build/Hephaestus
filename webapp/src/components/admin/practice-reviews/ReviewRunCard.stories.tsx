import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";

import { expectNoPageOverflow } from "@/stories/reflow";

import { reviewJob } from "./fixtures";
import { ReviewRunCard } from "./ReviewRunCard";

const COMPLETED_RUN = "11111111-1111-1111-1111-111111111111";
const FAILED_RUN = "bbbbbbbb-8888-8888-8888-888888888888";
const RUNNING_RUN = "aaaaaaaa-8888-8888-8888-888888888888";

/** How a review ran: the facts an operator checks when a review costs more or answers worse. */
const meta = {
	component: ReviewRunCard,
	parameters: { layout: "padded", chromatic: { viewports: [1440] } },
	tags: ["autodocs"],
	args: { job: reviewJob(COMPLETED_RUN) },
	argTypes: { job: { control: false } },
} satisfies Meta<typeof ReviewRunCard>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Completed: Story = {
	play: async ({ canvas }) => {
		// Reasoning is billed as output, so it is named as a part of what was written.
		await expect(canvas.getByText("(120 of it reasoning)")).toBeVisible();
		await expect(canvas.queryByText("Not yet")).not.toBeInTheDocument();
	},
};

/** Still running: nothing has finished, so the card says so rather than leaving the fact blank. */
export const Running: Story = {
	args: { job: reviewJob(RUNNING_RUN) },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Not yet")).toBeVisible();
	},
};

/**
 * The review failed before the reader arrived, so its failure is part of the record rather than news:
 * nothing is announced, and only the status icon carries the failure's colour.
 */
export const Failed: Story = {
	args: { job: reviewJob(FAILED_RUN) },
	play: async ({ canvas }) => {
		await expect(canvas.queryByRole("alert")).not.toBeInTheDocument();
		await expect(canvas.getByText("What went wrong")).toBeVisible();
		await expect(canvas.getByText(/Cannot compute diff/u)).toBeVisible();
	},
};

/** A stack trace's long tokens wrap inside the box rather than widening the page. */
export const FailedNarrow: Story = {
	args: {
		job: {
			...reviewJob(FAILED_RUN),
			errorMessage: `java.lang.IllegalStateException: ${"de.tum.cit.aet.hephaestus.agent.".repeat(4)}ReviewRunner`,
		},
	},
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	play: async ({ canvas }) => {
		await expect(canvas.getByText(/IllegalStateException/u)).toBeVisible();
		await expectNoPageOverflow();
	},
};
