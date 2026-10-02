import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, userEvent } from "storybook/test";

import { ActionConfirmation } from "~/components/action/ActionConfirmation";
import { READY, READY_ON_GITHUB } from "~/components/report/fixtures";
import type { ActionPreview } from "~/shared/review-actions";
import { workNoun } from "~/shared/work-noun";
import { expectNoHorizontalOverflow } from "~/stories/reflow";

const REQUEST: ActionPreview = {
	action: { kind: "request-review" },
	instanceHost: "hephaestus.build",
	workspace: READY.workspace,
	work: READY_ON_GITHUB.work,
};

/** The extension's own confirmation window, which a provider page can neither frame nor cover. */
const meta = {
	component: ActionConfirmation,
	tags: ["autodocs"],
	parameters: { layout: "fullscreen" },
	args: { state: { status: "ready", preview: REQUEST }, onConfirm: fn(), onClose: fn() },
} satisfies Meta<typeof ActionConfirmation>;

export default meta;
type Story = StoryObj<typeof meta>;

export const RequestReview: Story = {
	play: async ({ canvas, args }) => {
		await expect(canvas.getByRole("heading", { level: 1 })).toHaveTextContent(
			"Request a practice review",
		);
		await expect(canvas.getByText("HephaestusTest/lifecycle-validation #1")).toBeVisible();
		await expect(
			canvas.getByText(/reviews this pull request against the practices/u),
		).toBeVisible();
		await userEvent.click(canvas.getByRole("button", { name: "Request review" }));
		await expect(args.onConfirm).toHaveBeenCalledOnce();
		await userEvent.click(canvas.getByRole("button", { name: "Cancel" }));
		await expect(args.onClose).toHaveBeenCalledOnce();
	},
};

export const Loading: Story = { args: { state: { status: "loading" } } };

export const Sending: Story = {
	args: { state: { status: "sending", preview: REQUEST } },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("button", { name: "Request review" })).toBeDisabled();
		await expect(canvas.getByRole("button", { name: "Cancel" })).toBeDisabled();
	},
};

export const Requested: Story = {
	args: {
		state: {
			status: "done",
			preview: REQUEST,
			outcome: { kind: "request-review", status: "SUBMITTED" },
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("status", { name: "Review requested" })).toBeVisible();
		await expect(canvas.queryByRole("button", { name: "Request review" })).toBeNull();
	},
};

/** A 200 that started nothing is the server's answer, in its own words, not an error to retry. */
export const RequestRefusedByServer: Story = {
	args: {
		state: {
			status: "done",
			preview: REQUEST,
			outcome: {
				kind: "request-review",
				status: "REFUSED",
				reasonDescription:
					"This work was reviewed a few minutes ago; a later change gets its own review.",
			},
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("status", { name: "No review started" })).toHaveTextContent(
			"a later change gets its own review",
		);
		await expect(canvas.queryByRole("button", { name: /Try again|Request review/u })).toBeNull();
	},
};

/** Refused before anything was sent: the reader may no longer ask, and nothing changed. */
export const RefusedBeforeSending: Story = {
	args: {
		state: {
			status: "refused",
			preview: { ...REQUEST, work: READY.work },
			message: "Your account cannot ask for a review of this work.",
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("status", { name: "Nothing was changed" })).toHaveTextContent(
			"cannot ask for a review",
		);
	},
};

/** No answer came back: say so, and offer no second try from here. */
export const OutcomeUnknown: Story = {
	args: { state: { status: "unknown", preview: REQUEST } },
	play: async ({ canvas }) => {
		await expect(
			canvas.getByRole("status", { name: "No answer from Hephaestus" }),
		).toHaveTextContent("may or may not have gone through");
		await expect(canvas.queryByRole("button", { name: /Request review|Try again/u })).toBeNull();
		await expect(canvas.getByRole("button", { name: "Close" })).toBeVisible();
	},
};

export const NoLongerValid: Story = {
	args: {
		state: {
			status: "invalid",
			message:
				"This confirmation is no longer valid. Start again from the practice review on the page.",
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("heading", { level: 1 })).toHaveTextContent("Nothing to confirm");
	},
};

export const Narrow: Story = {
	parameters: { reflow: true },
	play: async ({ canvasElement }) => {
		await expectNoHorizontalOverflow(canvasElement);
	},
};

export const Dark: Story = { globals: { theme: "dark" } };

export const Nouns: Story = {
	play: async () => {
		await expect(workNoun({ kind: "scm.pull_request", provider: "GITLAB" })).toBe("merge request");
		await expect(workNoun({ kind: "scm.pull_request", provider: "GITHUB" })).toBe("pull request");
		await expect(workNoun({ kind: "scm.issue", provider: "GITLAB" })).toBe("issue");
	},
};
