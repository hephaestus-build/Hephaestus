import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, waitFor } from "storybook/test";

import type { ReviewSummary } from "@/api/types.gen";
import { ReviewDayTable } from "@/components/practice-trace/ReviewDayTable";
import { expectNoPageOverflow } from "@/stories/reflow";
import { levelsOpenedBy } from "@/test/detail-stack";

import { reviewAccounts, reviews } from "./fixtures";
import { REQUEST_BLOCKED } from "./RequestReviewAction";
import { REVIEW_SUMMARY_COLUMNS, ReviewSummaryRow } from "./ReviewSummaryRow";

function review(id: string): ReviewSummary {
	const found = reviews.find((candidate) => candidate.id === id);
	if (!found) {
		throw new Error(`No review ${id} in the fixture`);
	}
	return found;
}

const completed = review("11111111-1111-1111-1111-111111111111");
const conversation = review("33333333-3333-3333-3333-333333333333");
const running = review("aaaaaaaa-8888-8888-8888-888888888888");
const failed = review("bbbbbbbb-8888-8888-8888-888888888888");
const scan = review("dddddddd-9999-9999-9999-999999999999");
const requested: ReviewSummary = {
	...completed,
	id: "11111111-2222-2222-2222-222222222222",
	origin: "REQUEST",
	startedBy: null,
	requestedBy: reviewAccounts.grace,
};
const requestReview = fn();

const meta = {
	component: ReviewSummaryRow,
	parameters: { layout: "padded", chromatic: { viewports: [320, 1440] } },
	tags: ["autodocs"],
	args: { review: completed },
	decorators: [
		(Story, { args }) => (
			<ReviewDayTable
				aria-label="Practice reviews, newest first"
				timeLabel="Created"
				columns={REVIEW_SUMMARY_COLUMNS}
				reviews={[args.review]}
				dateOf={(entry) => entry.createdAt}
				reviewKey={(entry) => entry.id}
				renderRow={() => <Story />}
				empty={null}
			/>
		),
	],
} satisfies Meta<typeof ReviewSummaryRow>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Complete: Story = {
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Complete").parentElement).toHaveAttribute("title", "Complete");
		await expect(canvas.queryByRole("button", { name: "Complete" })).not.toBeInTheDocument();
		await expect(canvas.queryByRole("link", { name: /^#1423/u })).not.toBeInTheDocument();
		canvas.getByText("Event");
		await expect(canvas.queryByText("Failed")).toBeNull();
		await expect(
			levelsOpenedBy(canvas.getByRole("link", { name: /^Open review of #1423,/u })),
		).toEqual([`review:${completed.id}`]);
	},
};

/** A requested review names its requester as the result tables name a developer: face and name. */
export const Requested: Story = {
	args: { review: requested },
	play: async ({ canvas }) => {
		const name = canvas.getByText("Grace Hopper");
		// The face sits in the same cell as the name, as in the Developer column of Results.
		await expect(name.closest("td")?.querySelector('[data-slot="avatar"]')).not.toBeNull();
		await expect(canvas.queryByText("Request")).toBeNull();
	},
};

/** A requester the account does not name keeps the placeholder face and the developer fallback. */
export const RequestedByAnUnnamedAccount: Story = {
	args: {
		review: {
			...requested,
			requestedBy: { ...reviewAccounts.grace, displayName: null, avatarUrl: null, identities: [] },
		},
	},
	play: async ({ canvas }) => {
		const name = canvas.getByText("Unnamed developer");
		await expect(name.closest("td")?.querySelector('[data-slot="avatar"]')).not.toBeNull();
	},
};

/** A scheduled review names its origin, not a person. */
export const Scheduled: Story = {
	args: { review: { ...completed, origin: "SCHEDULE", requestedBy: null } },
	play: async ({ canvas }) => {
		canvas.getByText("Scheduled");
		await expect(canvas.queryByText("Grace Hopper")).toBeNull();
	},
};

export const RequestAnotherReview: Story = {
	args: { request: { work: "pull request #1423", onRequest: requestReview } },
	play: async ({ canvas, userEvent }) => {
		requestReview.mockClear();
		await userEvent.click(
			canvas.getByRole("button", { name: "Request review of pull request #1423" }),
		);
		await expect(requestReview).toHaveBeenCalledOnce();
	},
};

export const RequestBlockedWhileRunning: Story = {
	args: {
		review: running,
		request: { work: "pull request #1436", blocked: REQUEST_BLOCKED.active },
	},
	play: async ({ canvas, userEvent }) => {
		const button = canvas.getByRole("button", { name: /^Request review of /u });
		await expect(button).toHaveAttribute("aria-disabled", "true");
		await userEvent.hover(button);
		await waitFor(async () => expect(screen.getByText(REQUEST_BLOCKED.active)).toBeVisible());
	},
};

export const AConversation: Story = {
	args: { review: conversation },
	play: async ({ canvas }) => {
		canvas.getByRole("link", { name: /^Open review of #engineering,/u });
	},
};

export const StillRunning: Story = {
	args: { review: running },
	play: async ({ canvas }) => {
		canvas.getByText("Running");
	},
};

export const Failed: Story = {
	args: { review: failed },
	play: async ({ canvas }) => {
		await expect(canvas.getAllByText("Failed")).toHaveLength(2);
	},
};

export const PartialScan: Story = {
	args: { review: scan },
	play: async ({ canvas }) => {
		await expect(canvas.getAllByText("Partial")).toHaveLength(2);
		// The badge explains itself to a keyboard as well, its stop reason after its sentence.
		await expect(canvas.getByRole("button", { name: "Partial" })).toHaveAccessibleDescription(
			"Its observations cover only the work it reached. The scan’s budget was used up.",
		);
	},
};

export const Narrow: Story = {
	parameters: { viewport: { defaultViewport: "reflow" } },
	play: async () => {
		await expectNoPageOverflow();
	},
};

export const OpenInThePanel: Story = {
	args: { open: true },
	play: async ({ canvas }) => {
		const row = canvas.getByRole("row", { name: /#1423/u });
		await expect(row).toHaveAttribute("data-state", "open");
	},
};
