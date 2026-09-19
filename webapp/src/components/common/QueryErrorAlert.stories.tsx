import type { Meta, StoryObj } from "@storybook/react";
import { expect, fn, userEvent } from "storybook/test";

import { QueryErrorAlert } from "./QueryErrorAlert";

/**
 * The one failed-query surface, shared by every section that loads over the network.
 *
 * The HTTP status decides three things: the severity, the guidance, and whether Retry is offered at
 * all — a 403 and a 503 are both errors, but only one gets better if you press a button.
 *
 * The server's `detail` says what happened and is more specific than anything we can infer, so it
 * leads; the status-derived guidance says what to do and follows. Callers supply the title because
 * only they know what the reader was doing.
 */
const meta = {
	component: QueryErrorAlert,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	args: {
		title: "We couldn't load the job history",
		onRetry: fn(),
	},
} satisfies Meta<typeof QueryErrorAlert>;

export default meta;
type Story = StoryObj<typeof meta>;

/** 503 — the server is having a bad time. Retrying is exactly right, so Retry is offered. */
export const ServiceUnavailable: Story = {
	args: {
		error: { status: 503, detail: "The GitHub API is unavailable." },
	},
	play: async ({ args, canvas }) => {
		canvas.getByText(/github api is unavailable/iu);
		canvas.getByText(/on our side/iu);
		await userEvent.click(canvas.getByRole("button", { name: /retry/iu }));
		await expect(args.onRetry).toHaveBeenCalledTimes(1);
	},
};

/**
 * 403 — the reader isn't allowed. Retrying re-asks a question already answered, so the button is
 * withheld even though the caller passed `onRetry`, and the copy points at the actual way out.
 */
export const Forbidden: Story = {
	args: {
		error: { status: 403, detail: "You are not an admin of this workspace." },
	},
	play: async ({ canvas }) => {
		canvas.getByText(/not an admin of this workspace/iu);
		canvas.getByText(/ask an admin for access/iu);
		await expect(canvas.queryByRole("button", { name: /retry/iu })).not.toBeInTheDocument();
	},
};

/** 404 — deleted in another tab, most likely. A reload helps; a retry doesn't. */
export const NotFound: Story = {
	args: {
		error: { status: 404, detail: "This connection no longer exists." },
	},
	play: async ({ canvas }) => {
		canvas.getByText(/deleted or moved/iu);
		await expect(canvas.queryByRole("button", { name: /retry/iu })).not.toBeInTheDocument();
	},
};

/**
 * 409 — usually not an error at all. Something else got there first, which is a warning, not a
 * failure, and re-asking would get the same answer.
 */
export const Conflict: Story = {
	args: {
		title: "We couldn't start the sync",
		error: { status: 409, detail: "A sync is already running for this connection." },
	},
	play: async ({ canvas }) => {
		canvas.getByText(/a sync is already running/iu);
		await expect(canvas.queryByRole("button", { name: /retry/iu })).not.toBeInTheDocument();
	},
};

/** 429 — a real "try again", just not yet. Retry stays, severity drops to a warning. */
export const RateLimited: Story = {
	args: {
		error: { status: 429, detail: "Rate limit exceeded" },
	},
	play: async ({ canvas }) => {
		// The server's detail carries no terminal punctuation; the alert must terminate it before
		// appending guidance rather than run the two together as "Rate limit exceeded Too many…".
		canvas.getByText(/Rate limit exceeded\. Too many requests/u);
		canvas.getByRole("button", { name: /retry/iu });
	},
};

/** 401 — the session lapsed. Nothing to retry; sign in again. */
export const Unauthorized: Story = {
	args: {
		error: { status: 401, title: "Unauthorized" },
	},
	play: async ({ canvas }) => {
		canvas.getByText(/session has expired/iu);
		await expect(canvas.queryByRole("button", { name: /retry/iu })).not.toBeInTheDocument();
	},
};

/**
 * 400 — the server rejected the request itself, so an identical retry is rejected identically.
 */
export const BadRequest: Story = {
	args: {
		error: { status: 400, detail: "nameWithOwner must be in owner/name form." },
	},
	play: async ({ canvas }) => {
		canvas.getByText(/owner\/name form/iu);
		await expect(canvas.queryByRole("button", { name: /retry/iu })).not.toBeInTheDocument();
	},
};

/**
 * No status at all — the request never reached a server (offline, DNS, CORS, abort). This is the one
 * unknown where retrying is the right guess, so Retry is offered.
 */
export const NetworkFailure: Story = {
	args: {
		error: new TypeError("Failed to fetch"),
	},
	play: async ({ canvas }) => {
		canvas.getByText(/check your connection/iu);
		canvas.getByRole("button", { name: /retry/iu });
	},
};

export const ReflowWithLongDetail: Story = {
	parameters: {
		viewport: { defaultViewport: "reflow" },
		chromatic: { viewports: [320] },
	},
	args: {
		title: "Couldn't load the instance delivery state",
		error: {
			status: 503,
			detail:
				"https://status.example.invalid/incidents/instance-delivery-state-could-not-be-verified-without-convenient-breakpoints",
		},
	},
};

/**
 * The server said nothing useful. The guidance stands on its own rather than being padded with a
 * generic "An unexpected error occurred" it would only repeat.
 */
export const NoServerDetail: Story = {
	args: {
		error: { status: 500 },
	},
	play: async ({ canvas }) => {
		canvas.getByText(/^Something went wrong on our side/u);
	},
};

/** A caller that omits `onRetry` gets no button, whatever the status says. */
export const NoRetryHandler: Story = {
	args: {
		error: { status: 503, detail: "The GitHub API is unavailable." },
		onRetry: undefined,
	},
	play: async ({ canvas }) => {
		await expect(canvas.queryByRole("button", { name: /retry/iu })).not.toBeInTheDocument();
	},
};
