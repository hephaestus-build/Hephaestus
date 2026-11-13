import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, userEvent, waitFor, within } from "storybook/test";

import type { ProductFeedbackCleanupAction, ProductFeedbackNeedsAction } from "@/api/types.gen";
import { daysBefore, hoursBefore, minutesBefore } from "@/stories/story-clock";

import { AdminProductFeedbackNeedsActionList } from "./AdminProductFeedbackNeedsActionList";

const refused = {
	id: "c1c1c1c1-0000-0000-0000-000000000001",
	kind: "BUG",
	createdAt: hoursBefore(2),
	stoppedAt: minutesBefore(50),
	reason: "GitHub refused it",
	route: "PUBLIC",
	repository: "hephaestus-build/Hephaestus",
} satisfies ProductFeedbackNeedsAction;

const unavailable = {
	id: "c1c1c1c1-0000-0000-0000-000000000002",
	kind: "IDEA",
	createdAt: hoursBefore(5),
	stoppedAt: hoursBefore(4),
	reason: "destination unavailable",
	route: "PRIVATE",
	repository: "hephaestus-build/product-feedback",
} satisfies ProductFeedbackNeedsAction;

/** Scrubbed and closed; only its edit history is left for a maintainer. */
const scrubbed = {
	id: "e1e1e1e1-0000-0000-0000-000000000001",
	step: "EDIT_HISTORY",
	repository: "hephaestus-build/product-feedback",
	issueNumber: 12,
	issueUrl: "https://github.com/hephaestus-build/product-feedback/issues/12",
	marker: "hephaestus-product-feedback:5d1c0a52-0000-0000-0000-000000000001",
	erasedAt: hoursBefore(3),
	since: hoursBefore(3),
} satisfies ProductFeedbackCleanupAction;

/** GitHub never answered for 30 days, so the app does not know whether the issue exists. */
const unfinished = {
	id: "e1e1e1e1-0000-0000-0000-000000000002",
	step: "UNFINISHED",
	repository: "hephaestus-build/Hephaestus",
	marker: "hephaestus-product-feedback:5d1c0a52-0000-0000-0000-000000000002",
	erasedAt: daysBefore(31),
	since: hoursBefore(1),
} satisfies ProductFeedbackCleanupAction;

const meta = {
	component: AdminProductFeedbackNeedsActionList,
	args: {
		state: { status: "ready", items: [refused, unavailable], cleanups: [] },
		pendingIds: new Set<string>(),
		onRetryFiling: fn(),
		onConfirmCleanup: fn(),
	},
	tags: ["autodocs"],
} satisfies Meta<typeof AdminProductFeedbackNeedsActionList>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ canvas, args }) => {
		await expect(
			canvas.getByText(
				"Stopped because of: GitHub refused it. It was going to a public issue in hephaestus-build/Hephaestus.",
			),
		).toBeVisible();
		const [first] = canvas.getAllByRole("button", { name: "Retry" });
		// Each Retry is described by its own row, so a screen reader can tell them apart.
		await expect(first).toHaveAccessibleDescription(/GitHub refused it/u);
		if (first !== undefined) {
			await userEvent.click(first);
		}
		await expect(args.onRetryFiling).toHaveBeenCalledWith(refused);
	},
};

export const Retrying: Story = {
	args: { pendingIds: new Set([refused.id]) },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("button", { name: "Retrying…" })).toBeDisabled();
	},
};

export const ErasedReports: Story = {
	args: { state: { status: "ready", items: [refused], cleanups: [scrubbed, unfinished] } },
	play: async ({ canvas, args, canvasElement }) => {
		await expect(
			canvas.getByRole("link", { name: /hephaestus-build\/product-feedback#12/u }),
		).toHaveAttribute("href", scrubbed.issueUrl);
		// Without an issue number, the marker is the only way to find the issue.
		await expect(canvas.getByText(unfinished.marker)).toBeVisible();
		const [first] = canvas.getAllByRole("button", { name: "Confirm done" });
		if (first === undefined) {
			throw new Error("No Confirm done button");
		}
		// The dialog renders in a portal, outside the story's canvas.
		const body = within(canvasElement.ownerDocument.body);
		const question = { name: "Is the erased text gone from GitHub?" };
		await userEvent.click(first);
		await userEvent.click(
			within(await body.findByRole("alertdialog", question)).getByRole("button", {
				name: "Cancel",
			}),
		);
		await waitFor(async () => expect(first).toHaveFocus());
		await expect(args.onConfirmCleanup).not.toHaveBeenCalled();

		await userEvent.click(first);
		const dialog = await body.findByRole("alertdialog", question);
		await userEvent.click(within(dialog).getByRole("button", { name: "Confirm done" }));
		await expect(args.onConfirmCleanup).toHaveBeenCalledWith(scrubbed);
	},
};

export const ConfirmingErasedReport: Story = {
	args: {
		state: { status: "ready", items: [], cleanups: [scrubbed] },
		pendingIds: new Set([scrubbed.id]),
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("button", { name: "Confirming…" })).toBeDisabled();
	},
};

export const Empty: Story = {
	args: { state: { status: "ready", items: [], cleanups: [] } },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Nothing needs action")).toBeVisible();
	},
};

export const Loading: Story = {
	args: { state: { status: "loading" } },
};

export const LoadError: Story = {
	args: { state: { status: "error", error: new TypeError("Failed to fetch"), onRetry: fn() } },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("We could not load the reports that need action")).toBeVisible();
	},
};

/** A stopped admin-routed report names its destination as a sentence. */
export const SavedForAdmins: Story = {
	args: {
		state: {
			status: "ready",
			items: [{ ...unavailable, route: "ADMINS", repository: undefined }],
			cleanups: [],
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText(/It was going to the instance admins in Reports/u)).toBeVisible();
	},
};
