import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";
import type { FeedbackCopy } from "@/api/types.gen";
import { expectNoPageOverflow } from "@/stories/reflow";
import { PRIVATE_FEEDBACK_TEXT, reviewFeedbackDetail } from "./fixtures";
import { ReviewPackage } from "./ReviewPackage";

const longPath =
	"server/src/main/java/de/tum/cit/aet/hephaestus/integration/provider/gitlab/WorkspaceMembershipReconciliationService.java";
const copy = (fields: Partial<FeedbackCopy> & Pick<FeedbackCopy, "id" | "kind" | "position">) =>
	({
		body: null,
		conversationEntryId: null,
		endLine: null,
		externalRef: null,
		externalUrl: null,
		lastError: null,
		readback: null,
		path: null,
		postedAt: null,
		side: null,
		startLine: null,
		state: "POSTED",
		stopReason: null,
		writeStartedAt: null,
		...fields,
	}) satisfies FeedbackCopy;

const copies = [
	copy({
		id: "copy-summary",
		kind: "SUMMARY",
		position: 0,
		body: "### Keep the retry boundary explicit\n\nThe review found one error-handling change to make.",
	}),
	copy({
		id: "copy-line-note",
		kind: "LINE_NOTE",
		position: 1,
		body: "Catch only the provider failure that is safe to retry; programming errors must still surface.",
		path: longPath,
		side: "NEW",
		startLine: 148,
		endLine: 152,
		state: "FAILED",
	}),
];
const feedback = { ...reviewFeedbackDetail, destination: "ON_WORK" as const, copies };

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

export const PrivatePlaceNeverShown: Story = {
	args: {
		feedback: {
			...feedback,
			destination: "PRACTICE_PAGE",
			copies: copies.map((each) => ({ ...each, body: PRIVATE_FEEDBACK_TEXT })),
		},
	},
	parameters: { chromatic: { viewports: [1440] } },
	play: async ({ canvas }) => {
		await expect(
			canvas.getByText(/written for the developer’s own practice pages and is withheld/u),
		).toBeVisible();
		await expect(canvas.queryByText(PRIVATE_FEEDBACK_TEXT)).toBeNull();
		await expect(canvas.queryByText(longPath)).toBeNull();
	},
};
