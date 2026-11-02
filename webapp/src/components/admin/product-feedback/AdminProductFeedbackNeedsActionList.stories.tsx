import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, userEvent } from "storybook/test";

import type { ProductFeedbackNeedsAction } from "@/api/types.gen";
import { hoursBefore, minutesBefore } from "@/stories/story-clock";

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

const meta = {
	component: AdminProductFeedbackNeedsActionList,
	args: {
		state: { status: "ready", items: [refused, unavailable] },
		pendingIds: new Set<string>(),
		onRetryFiling: fn(),
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

export const Empty: Story = {
	args: { state: { status: "ready", items: [] } },
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
