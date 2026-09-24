import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";
import { expectNoPageOverflow } from "@/stories/reflow";
import { reviewFeedbackDetail } from "./fixtures";
import { ReviewPackage } from "./ReviewPackage";

const longPath =
	"server/src/main/java/de/tum/cit/aet/hephaestus/integration/provider/gitlab/WorkspaceMembershipReconciliationService.java";
const feedback = {
	...reviewFeedbackDetail,
	proposedPlacements: [
		{
			type: "SUMMARY" as const,
			body: "### Keep the retry boundary explicit\n\nThe review found one error-handling change to make.",
		},
		{
			type: "INLINE" as const,
			body: "Catch only the provider failure that is safe to retry; programming errors must still surface.",
			path: longPath,
			startLine: 148,
			endLine: 152,
		},
	],
};

const meta = {
	component: ReviewPackage,
	parameters: {
		layout: "padded",
		viewport: { defaultViewport: "reflow" },
		chromatic: { viewports: [320, 1440] },
	},
	tags: ["autodocs"],
	args: { feedback, defaultExpanded: true },
	decorators: [
		(Story) => (
			<div className="mx-auto w-full max-w-4xl">
				<Story />
			</div>
		),
	],
} satisfies Meta<typeof ReviewPackage>;

export default meta;
type Story = StoryObj<typeof meta>;

export const ExactSummaryAndInlinePackage: Story = {
	globals: { theme: "dark" },
	play: async ({ canvas }) => {
		await expect(
			canvas.getByRole("heading", { name: "Keep the retry boundary explicit" }),
		).toBeVisible();
		await expect(canvas.getByText(longPath)).toBeVisible();
		await expect(
			canvas.getByText(
				"Catch only the provider failure that is safe to retry; programming errors must still surface.",
			),
		).toBeVisible();
		await expectNoPageOverflow();
	},
};

export const Unavailable: Story = {
	args: { feedback: { ...feedback, proposedPlacements: [] }, defaultExpanded: false },
	parameters: { chromatic: { viewports: [320] } },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("alert")).toHaveTextContent(
			"This review package is unavailable.",
		);
	},
};
