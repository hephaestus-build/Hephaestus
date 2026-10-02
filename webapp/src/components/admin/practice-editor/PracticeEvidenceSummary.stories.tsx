import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";
import { EMPTY_REVIEW_SETTINGS } from "./review-settings";

import {
	mockPullRequestReviewFields,
	mockPullRequestPolicy,
	mockPullRequestWorkType,
} from "@/mocks/fixtures/practice";
import { expectNoOverflowingElement } from "@/stories/reflow";

import { PracticeEvidenceSummary } from "./PracticeEvidenceSummary";

const meta = {
	component: PracticeEvidenceSummary,
	args: {
		policy: mockPullRequestPolicy,
		...mockPullRequestReviewFields,
		sources: mockPullRequestWorkType.allowedSources,
		signalOptions: mockPullRequestWorkType.signals,
		workTypeLabel: "Pull or merge request",
		validation: {
			status: "AUTHOR_DECLARED",
			sourceContractVersion: "1.2.0",
			policyDigest: "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
			reviewRuleFingerprint: `v5:${"0".repeat(64)}`,
		},
	},
	parameters: { layout: "padded" },
	tags: ["autodocs"],
} satisfies Meta<typeof PracticeEvidenceSummary>;

export default meta;
type Story = StoryObj<typeof meta>;

/**
 * The digests are what an author compares when a review claim is disputed, so they name the exact
 * policy and rules the declaration was made about.
 */
export const AuthorDeclared: Story = {
	play: async ({ canvas }) => {
		await expect(
			canvas.getByText(/Nobody has measured how often this practice is right/u),
		).toBeVisible();
		await expect(canvas.getByText(/^Rules/u)).toHaveTextContent(
			"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
		);
	},
};

export const OneOccasion: Story = {
	args: mockPullRequestReviewFields,
};

export const ScopedReviewer: Story = {
	args: {
		...mockPullRequestReviewFields,
		subject: "REVIEWER",
		precondition: {
			skipReason: "the change has no Swift code",
			anyOf: [{ changedPathMatches: ["**/*.swift"] }],
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Reviewer")).toBeVisible();
		await expect(canvas.getByText("Changed path matches **/*.swift")).toBeVisible();
		await expect(canvas.getByText("Otherwise skip: the change has no Swift code")).toBeVisible();
	},
};

/**
 * A gate on evidence being present, and a moment this version no longer offers: both in words, never
 * by the source's or the moment's wire name.
 */
export const GatedOnEvidence: Story = {
	args: {
		...mockPullRequestReviewFields,
		signals: [...mockPullRequestReviewFields.signals, "scm.pull_request.labeled"],
		precondition: {
			skipReason: "nobody has left a review thread yet",
			anyOf: [{ evidenceHasItems: "scm.review-threads" }],
		},
	},
	play: async ({ canvas, canvasElement }) => {
		await expect(canvas.getByText("Evidence present: Review threads and decisions")).toBeVisible();
		await expect(canvas.getByText("A moment this version no longer offers")).toBeVisible();
		await expect(canvasElement).not.toHaveTextContent(/scm\./u);
	},
};

export const NoOccasion: Story = {
	args: EMPTY_REVIEW_SETTINGS,
};

export const NarrowViewport: Story = {
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	play: async ({ canvasElement }) => {
		await expectNoOverflowingElement(canvasElement);
	},
};
