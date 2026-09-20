import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";

import type { CatalogPracticeSummary } from "@/api/types.gen";
import { mockAuthorDeclaredEvidenceValidation } from "@/mocks/fixtures/practice";

import { AvailablePracticeList } from "./AvailablePracticeList";

// The `detail` param a row would push. Read back off the href rather than compared as a whole URL,
// because a stack link preserves whatever search the surrounding route already had — in Storybook
// that is the preview iframe's own `sessionId`.
const detailParamOf = (link: HTMLElement) =>
	new URL(link.getAttribute("href") ?? "", "https://example.test").searchParams.get("detail");

const describeWhatAndWhy: CatalogPracticeSummary = {
	slug: "describe-what-and-why",
	name: "Describe what changed and why",
	artifactKind: "scm.pull_request",
	groupSlug: "review-ready-work",
	groupName: "Review-ready work",
	availability: "AVAILABLE",
	automatedReviewValidation: mockAuthorDeclaredEvidenceValidation,
};

const reviewScope: CatalogPracticeSummary = {
	slug: "review-scope",
	name: "Keep pull requests focused",
	artifactKind: "scm.pull_request",
	groupSlug: "review-ready-work",
	groupName: "Review-ready work",
	availability: "ADOPTED",
	automatedReviewValidation: mockAuthorDeclaredEvidenceValidation,
};

const issueContext: CatalogPracticeSummary = {
	slug: "issue-context",
	name: "Include enough issue context",
	artifactKind: "scm.issue",
	availability: "SLUG_CONFLICT",
	automatedReviewValidation: mockAuthorDeclaredEvidenceValidation,
};

const practices: CatalogPracticeSummary[] = [describeWhatAndWhy, reviewScope, issueContext];

/**
 * Grouping is not optional and adopted entries are not shown twice, so the list has no display
 * flags: the one caller would have typed both as literals, which is the shape a flag argument takes
 * just before it stops being one.
 */
const meta = {
	component: AvailablePracticeList,
	parameters: { layout: "padded" },
	args: { practices, existingGroupSlugs: new Set(["review-ready-work"]) },
	argTypes: { existingGroupSlugs: { control: false } },
	tags: ["autodocs"],
} satisfies Meta<typeof AvailablePracticeList>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ canvas }) => {
		await expect(canvas.queryByText("Available")).not.toBeInTheDocument();
		await expect(canvas.getByText("Name unavailable")).toBeVisible();
		await expect(canvas.queryByText("Keep pull requests focused")).not.toBeInTheDocument();
		await expect(
			detailParamOf(canvas.getByRole("link", { name: /Describe what changed and why/u })),
		).toBe('["catalog-practice:describe-what-and-why"]');
		await expect(detailParamOf(canvas.getByRole("link", { name: /Review 1 practice/u }))).toBe(
			'["catalog-group:review-ready-work"]',
		);
	},
};

export const DeletedGroupStillHasSomethingToAdd: Story = {
	args: { existingGroupSlugs: new Set() },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("link", { name: /Review group · 1 practice/u })).toBeVisible();
		await expect(
			detailParamOf(canvas.getByRole("link", { name: /Keep pull requests focused/u })),
		).toBe('["practice:review-scope"]');
	},
};

export const DeletedGroupCanOnlyBeRestored: Story = {
	args: { practices: [reviewScope], existingGroupSlugs: new Set() },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("link", { name: /Restore group · 1 practice/u })).toBeVisible();
	},
};

export const EverythingAdded: Story = {
	args: { practices: [reviewScope] },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Everything is already added")).toBeVisible();
	},
};

export const NothingOffered: Story = {
	args: { practices: [] },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Nothing to add")).toBeVisible();
	},
};

export const LongContent: Story = {
	args: {
		practices: [
			{
				...describeWhatAndWhy,
				name: "Explain architectural trade-offs, operational constraints, and the evidence behind the chosen implementation",
				groupName: "Decisions, documentation, and long-lived operational knowledge",
			},
		],
	},
};

export const NarrowViewport: Story = {
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
};

export const DarkMode: Story = {
	globals: { theme: "dark" },
};
