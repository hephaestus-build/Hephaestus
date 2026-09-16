import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, within } from "storybook/test";

import { TraceRefusalAlert } from "./TraceRefusalAlert";

/**
 * Why an ask started nothing. A refusal is a 200 carrying the workspace's own answer, not an error,
 * so it is shown on the page rather than as a toast — a toast is gone before the reader has
 * finished reading it.
 *
 * The sentence is the server's, verbatim; the fix link is keyed on the coded reason beside it, so
 * re-worded prose can never cost a reader the way out.
 */
const meta = {
	component: TraceRefusalAlert,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	args: {
		workspaceSlug: "demo",
		canAdminister: true,
		refusal: {
			status: "REFUSED",
			reason: "REVIEW_MODEL_UNBOUND",
			reasonDescription: "No AI model is set up to run reviews in this workspace.",
		},
	},
} satisfies Meta<typeof TraceRefusalAlert>;

export default meta;
type Story = StoryObj<typeof meta>;

/** An admin can go and undo this one, so the way out travels with the sentence. */
export const OffersTheFixToAnAdmin: Story = {
	play: async ({ canvas }) => {
		const alert = within(canvas.getByRole("alert"));
		await expect(
			alert.getByText("No AI model is set up to run reviews in this workspace."),
		).toBeVisible();
		await expect(alert.getByRole("link", { name: "Set up a review model" })).toHaveAttribute(
			"href",
			"/w/demo/admin/models",
		);
	},
};

/**
 * The same refusal for a member. The link is withheld rather than shown-and-bounced: `/admin` turns
 * every non-admin away, losing the page they were reading.
 */
export const WithholdsTheFixFromAMember: Story = {
	args: { canAdminister: false },
	play: async ({ canvas }) => {
		const alert = within(canvas.getByRole("alert"));
		await expect(
			alert.getByText("No AI model is set up to run reviews in this workspace."),
		).toBeVisible();
		await expect(alert.queryByRole("link")).not.toBeInTheDocument();
	},
};

/** An allowance that refills is not something anybody can go and change, so nothing is offered. */
export const NoFixExists: Story = {
	args: {
		refusal: {
			status: "REFUSED",
			reason: "REQUESTER_QUOTA_EXHAUSTED",
			reasonDescription:
				"You have asked for as many reviews as an hour allows; the allowance refills.",
		},
	},
	play: async ({ canvas }) => {
		const alert = within(canvas.getByRole("alert"));
		await expect(alert.getByText(/the allowance refills/u)).toBeVisible();
		await expect(alert.queryByRole("link")).not.toBeInTheDocument();
	},
};

/** A server that sent no sentence still gets a heading and a body rather than an empty alert. */
export const WithoutASentence: Story = {
	args: { refusal: { status: "REFUSED" } },
	play: async ({ canvas }) => {
		const alert = within(canvas.getByRole("alert"));
		await expect(alert.getByText("No review was started.")).toBeVisible();
		await expect(alert.queryByRole("link")).not.toBeInTheDocument();
	},
};
