import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";

import {
	mockMergeBinding,
	mockPullRequestBinding,
	mockPullRequestPolicy,
	mockPullRequestWorkType,
} from "@/mocks/fixtures/practice";
import { expectNoOverflowingElement } from "@/test/reflow";

import { PracticeEvidenceSummary } from "./PracticeEvidenceSummary";

const meta = {
	title: "Shared/Practice catalog/Evidence summary",
	component: PracticeEvidenceSummary,
	args: {
		policy: mockPullRequestPolicy,
		bindings: [mockPullRequestBinding, mockMergeBinding],
		sources: mockPullRequestWorkType.allowedSources,
		signals: mockPullRequestWorkType.signals,
		workTypeLabel: "Pull or merge request",
		validation: {
			status: "AUTHOR_DECLARED",
			sourceContractVersion: "1.1.0",
			policyDigest: "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
			reviewRuleFingerprint: `v2:${"0".repeat(64)}`,
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
			canvas.getByText(/Nobody has measured how often this practice is right/),
		).toBeVisible();
		await expect(canvas.getByText(/^Rules/)).toHaveTextContent(
			"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
		);
	},
};

export const OneOccasion: Story = {
	args: { bindings: [mockPullRequestBinding] },
};

export const NoOccasion: Story = {
	args: { bindings: [] },
};

export const NarrowViewport: Story = {
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	play: async ({ canvasElement }) => {
		await expectNoOverflowingElement(canvasElement);
	},
};
