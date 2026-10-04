import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, userEvent, within } from "storybook/test";

import type { PracticeDefinition, PracticeReleaseProposal } from "@/api/types.gen";
import { mockPullRequestReviewFields, mockPullRequestPolicy } from "@/mocks/fixtures/practice";

import { PracticeReleaseReview } from "./PracticeReleaseReview";

const base: PracticeDefinition = {
	name: "Explain the change",
	...mockPullRequestReviewFields,
	criteria: "Explain the old behavior",
	automatedReviewPolicy: mockPullRequestPolicy,
	whyItMatters: "Reviewers need context.",
	deliveryBehavior: { summaryOnly: false },
};

const proposal: PracticeReleaseProposal = {
	slug: "explain-the-change",
	base,
	current: {
		...base,
		criteria: "Our own review criteria",
		whyItMatters: "Our team needs context.",
	},
	offered: {
		...base,
		criteria: "Explain the change and why it matters",
		deliveryBehavior: { summaryOnly: true },
	},
	baseSource: "EXACT_ADOPTION",
	offeredDigest: "offered",
	etag: "proposal",
	currentRevision: 2,
	fields: [
		{ field: "CRITERIA", offeredChanged: true, conflict: true },
		{ field: "WHY_IT_MATTERS", offeredChanged: false, conflict: false },
		{ field: "DELIVERY_BEHAVIOR", offeredChanged: true, conflict: false },
	],
};

const meta = {
	component: PracticeReleaseReview,
	args: { proposal, pending: false, onAccept: fn(), onDecline: fn() },
	parameters: { layout: "padded" },
	tags: ["autodocs"],
} satisfies Meta<typeof PracticeReleaseReview>;

export default meta;
type Story = StoryObj<typeof meta>;

export const ConflictingUpdate: Story = {
	play: async ({ args }) => {
		const accept = screen.getByRole("button", { name: "Accept selected fields" });
		await expect(accept).toBeDisabled();
		await expect(screen.getByText("Local edit")).toBeVisible();
		await userEvent.click(
			within(
				screen.getByRole("radiogroup", { name: "Use a version for Review criteria" }),
			).getByRole("radio", { name: "Proposed" }),
		);
		await expect(accept).toBeEnabled();
		await userEvent.click(accept);
		await expect(args.onAccept).toHaveBeenCalledWith({
			CRITERIA: "OFFERED",
			DELIVERY_BEHAVIOR: "OFFERED",
		});
	},
};

/** Delivery choices read as sentences on both sides, never as the object the wire carries. */
export const DeliveryInWords: Story = {
	play: async () => {
		await expect(
			screen.getByText("Feedback stays in the summary, not on a changed line."),
		).toBeVisible();
		await expect(screen.getAllByText("No special delivery rules.").length).toBeGreaterThan(0);
		await expect(screen.queryByText(/summaryOnly/u)).not.toBeInTheDocument();
	},
};

export const RecoveredBase: Story = {
	args: { proposal: { ...proposal, baseSource: "CURRENT_DEFINITION" } },
	play: async ({ args }) => {
		await expect(screen.getByText(/Choose a version for each changed field/u)).toBeVisible();
		const accept = screen.getByRole("button", { name: "Accept selected fields" });
		const delivery = within(
			screen.getByRole("radiogroup", { name: "Use a version for Feedback delivery" }),
		);
		await expect(delivery.getByRole("radio", { name: "Proposed" })).not.toBeChecked();
		await expect(delivery.getByRole("radio", { name: "Current" })).not.toBeChecked();
		await userEvent.click(
			within(
				screen.getByRole("radiogroup", { name: "Use a version for Review criteria" }),
			).getByRole("radio", { name: "Proposed" }),
		);
		await expect(accept).toBeDisabled();
		await userEvent.click(delivery.getByRole("radio", { name: "Current" }));
		await expect(accept).toBeEnabled();
		await userEvent.click(accept);
		await expect(args.onAccept).toHaveBeenCalledWith({
			CRITERIA: "OFFERED",
			DELIVERY_BEHAVIOR: "CURRENT",
		});
	},
};

export const RecordedVersionBase: Story = {
	...RecoveredBase,
	args: { proposal: { ...proposal, baseSource: "REVISION_FINGERPRINT_MATCH" } },
};

export const Submitting: Story = {
	args: { pending: true },
};
