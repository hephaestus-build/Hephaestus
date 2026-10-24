import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";

import { reviewLevel } from "@/components/admin/practice-reviews/review-levels";
import { InlineLink } from "@/components/common/InlineLink";
import { DetailStackLink } from "@/components/layout/detail-drawer/DetailStackLink";
import { levelsOpenedBy } from "@/test/detail-stack";

import { practiceScriptRuns } from "./fixtures";
import { PracticeScriptTraceLine } from "./PracticeScriptTraceLine";

const REVIEW = "11111111-1111-1111-1111-111111111111";
const DETAILS = "Details of the practice script for Thin controllers";

const meta = {
	component: PracticeScriptTraceLine,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	args: {
		workspaceSlug: "demo",
		practiceSlug: "thin-controllers",
		practiceName: "Thin controllers",
		reviewId: REVIEW,
		run: practiceScriptRuns.returned,
	},
	argTypes: { run: { control: false } },
} satisfies Meta<typeof PracticeScriptTraceLine>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ canvas }) => {
		const details = canvas.getByRole("link", { name: DETAILS });
		await expect(details.getAttribute("href")).toMatch(/^\/w\/demo\/admin\/practices\/reviews\?/u);
		await expect(levelsOpenedBy(details)).toStrictEqual([`review:${REVIEW}`]);
	},
};

export const DetailsInAStack: Story = {
	args: {
		reviewLink: (reviewId, label) => (
			<InlineLink
				className="inline-flex items-center gap-1"
				render={<DetailStackLink entry={reviewLevel(reviewId)} />}
			>
				{label}
			</InlineLink>
		),
	},
	play: async ({ canvas }) => {
		const details = canvas.getByRole("link", { name: DETAILS });
		await expect(details.getAttribute("href")).not.toMatch(/\/admin\/practices\/reviews/u);
		await expect(levelsOpenedBy(details)).toStrictEqual([`review:${REVIEW}`]);
	},
};

export const OnTheReviewSheet: Story = {
	args: { run: practiceScriptRuns.failed, details: false },
	play: async ({ canvas }) => {
		await expect(canvas.queryByRole("link", { name: DETAILS })).not.toBeInTheDocument();
	},
};

/** A built-in review whose results the evidence check refused reads as that, never as a format failure. */
export const EvidenceNotRead: Story = {
	args: { run: practiceScriptRuns.evidenceNotRead, details: false },
	play: async ({ canvas }) => {
		await expect(
			canvas.getByText(
				/^Built-in review stopped without a candidate observation: its candidate observation was refused because it had not read the evidence it needed\./u,
			),
		).toBeVisible();
		await expect(canvas.queryByText(/wrong format/u)).not.toBeInTheDocument();
	},
};
