import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";

import {
	mockMergeBinding,
	mockPullRequestBinding,
	mockPullRequestPolicy,
	mockPullRequestWorkType,
} from "@/mocks/fixtures/practice";
import { expectNoOverflowingElement } from "@/stories/reflow";

import { PracticeEvidenceSummary } from "./PracticeEvidenceSummary";

const meta = {
	component: PracticeEvidenceSummary,
	args: {
		policy: mockPullRequestPolicy,
		bindings: [mockPullRequestBinding, mockMergeBinding],
		sources: mockPullRequestWorkType.allowedSources,
		signals: mockPullRequestWorkType.signals,
		workTypeLabel: "Pull or merge request",
		validation: {
			status: "AUTHOR_DECLARED",
			sourceContractVersion: "1.2.0",
			policyDigest: "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
			reviewRuleFingerprint: `v4:${"0".repeat(64)}`,
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
	args: { bindings: [mockPullRequestBinding] },
};

export const ScopedReviewer: Story = {
	args: {
		bindings: [
			{
				...mockPullRequestBinding,
				subject: "REVIEWER",
				appliesWhen: {
					absentSays: "the change has no Swift code",
					anyOf: [{ changedPathMatches: ["**/*.swift"] }],
				},
			},
		],
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText("reviewer")).toBeVisible();
		await expect(canvas.getByText("Changed path matches **/*.swift")).toBeVisible();
		await expect(canvas.getByText("Otherwise skip: the change has no Swift code")).toBeVisible();
	},
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
