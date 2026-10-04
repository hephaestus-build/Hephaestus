import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn } from "storybook/test";

import { MissingRecordEmpty } from "./MissingRecordEmpty";

/**
 * The half of a detail page's failure that is not a failure: no record, and nothing that went wrong.
 *
 * Detail pages used to fold this into `QueryErrorAlert` by passing it `error={undefined}`, which
 * classifies an absent status as a lost connection and offers Retry under a destructive alert. That
 * is a guess dressed as a diagnosis — and the case it is guessing about is not the one a reader
 * assumes, because a deleted record answers 404 and reaches the alert as a real error.
 */
const meta = {
	component: MissingRecordEmpty,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	args: {
		title: "We could not load this feedback",
		onRetry: fn(),
	},
} satisfies Meta<typeof MissingRecordEmpty>;

export default meta;
type Story = StoryObj<typeof meta>;

/** Trying again is the whole remedy, so it is the only thing offered. */
export const Default: Story = {
	play: async ({ args, canvas, userEvent }) => {
		// Not "error", not "destructive": nothing failed, so the surface must not claim one did.
		await expect(canvas.queryByRole("alert")).toBeNull();

		await userEvent.click(canvas.getByRole("button", { name: "Retry" }));
		await expect(args.onRetry).toHaveBeenCalledTimes(1);
	},
};

/** A caller with nothing to retry — a page whose query the reader cannot re-ask — gets no button. */
export const NothingToRetry: Story = {
	args: { onRetry: undefined },
	play: async ({ canvas }) => {
		await expect(canvas.queryByRole("button")).toBeNull();
		await expect(canvas.getByText("We could not load this feedback")).toBeVisible();
	},
};

/** The longest title a detail page passes, at the width where it has the least room. */
export const Reflow: Story = {
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	args: { title: "We could not load this review activity" },
};
