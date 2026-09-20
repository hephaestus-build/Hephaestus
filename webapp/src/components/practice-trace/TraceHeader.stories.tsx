import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, userEvent } from "storybook/test";

import { artifactTrace, documentArtifactTrace, untouchedArtifactTrace } from "./fixtures";
import { TraceHeader } from "./TraceHeader";

/**
 * What the work is, where it lives, and the one thing a reader can do about it from here.
 *
 * The button is offered only for the kinds the request endpoint accepts. Nothing on the wire says
 * which those are, so being wrong in this direction costs a missing button rather than a broken one.
 */
const meta = {
	component: TraceHeader,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	args: {
		trace: artifactTrace,
		onRequestReview: fn(),
		requestPending: false,
	},
} satisfies Meta<typeof TraceHeader>;

export default meta;
type Story = StoryObj<typeof meta>;

export const PullRequest: Story = {
	play: async ({ args, canvas }) => {
		await expect(
			canvas.getByRole("heading", { name: /Member-facing review activity/u }),
		).toBeVisible();
		// The kind is named for a reader; the wire id never reaches the page.
		await expect(canvas.getByText("Pull or merge request")).toBeVisible();
		await expect(canvas.queryByText("scm.pull_request")).not.toBeInTheDocument();
		await expect(canvas.getByRole("link", { name: /Open the original/u })).toHaveAttribute(
			"href",
			"https://github.com/ls1intum/Hephaestus/pull/1423",
		);

		await userEvent.click(canvas.getByRole("button", { name: "Review this now" }));
		await expect(args.onRequestReview).toHaveBeenCalledTimes(1);
	},
};

/** While the ask is in flight the button says so and refuses a second one. */
export const Asking: Story = {
	args: { requestPending: true },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("button", { name: "Asking…" })).toBeDisabled();
	},
};

export const Issue: Story = {
	args: { trace: untouchedArtifactTrace },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Issue")).toBeVisible();
		await expect(canvas.getByRole("button", { name: "Review this now" })).toBeVisible();
	},
};

/** A document is reviewed on the occasion its source produces; asking by hand would be refused. */
export const DocumentHasNoButtonToAsk: Story = {
	args: { trace: documentArtifactTrace },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Document")).toBeVisible();
		await expect(canvas.queryByText("docs.document")).not.toBeInTheDocument();
		await expect(canvas.queryByRole("button", { name: "Review this now" })).not.toBeInTheDocument();
	},
};

/** A deleted or unlinkable artifact keeps its title and loses only what it no longer has. */
export const UnlinkableArtifact: Story = {
	args: {
		trace: {
			...artifactTrace,
			artifactKind: "chat.conversation_thread",
			number: undefined,
			container: undefined,
			url: undefined,
		},
	},
	play: async ({ canvas }) => {
		await expect(
			canvas.queryByRole("link", { name: /Open the original/u }),
		).not.toBeInTheDocument();
		await expect(canvas.queryByText("#1423")).not.toBeInTheDocument();
		await expect(canvas.queryByRole("button", { name: "Review this now" })).not.toBeInTheDocument();
	},
};
