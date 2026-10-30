import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, waitFor, within } from "storybook/test";

import type { AgentBinding } from "@/api/types.gen";
import {
	PracticeTreeSkeleton,
	ReviewSettingsSkeleton,
} from "@/components/admin/practices/PracticeSkeletons";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { expectNoPageOverflow } from "@/stories/reflow";
import { StatefulPatch } from "@/stories/stateful";
import type { ReviewSectionId } from "./review-sections";
import { ReviewPage } from "./ReviewPage";

const sectionBody = (id: ReviewSectionId) => (
	<p className="rounded-md border border-dashed p-6 text-sm text-muted-foreground">
		Section body: {id}
	</p>
);

const sections = {
	"feedback-mode": sectionBody("feedback-mode"),
	"automated-review": sectionBody("automated-review"),
	"past-work": sectionBody("past-work"),
};

const readyBinding = {
	dataHandlingTier: "IN_HOUSE",
	purpose: "PRACTICE_REVIEW",
	enabled: true,
	ready: true,
	servedTiers: ["IN_HOUSE", "CLOUD"],
} satisfies AgentBinding;

const meta = {
	component: ReviewPage,
	parameters: {
		layout: "padded",
		chromatic: { viewports: [320, 1440] },
		viewport: { defaultViewport: "reflow" },
	},
	tags: ["autodocs"],
	args: {
		section: "feedback-mode",
		onSectionChange: fn(),
		readiness: {
			enabled: true,
			model: { status: "ready", binding: readyBinding },
		},
		sections,
	},
	render: (args) => (
		<StatefulPatch initial={{ section: args.section }}>
			{(view, patch) => (
				<ReviewPage
					{...args}
					section={view.section}
					onSectionChange={(section) => {
						args.onSectionChange(section);
						patch({ section });
					}}
				/>
			)}
		</StatefulPatch>
	),
} satisfies Meta<typeof ReviewPage>;

export default meta;
type Story = StoryObj<typeof meta>;

export const FeedbackMode: Story = {
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("tab", { name: "Feedback mode" })).toHaveAttribute(
			"aria-selected",
			"true",
		);
		await expect(canvas.getByText("Section body: feedback-mode")).toBeVisible();
		await expectNoPageOverflow();
	},
};

export const AutomatedReview: Story = {
	args: { section: "automated-review" },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Section body: automated-review")).toBeVisible();
		await expectNoPageOverflow();
	},
};

export const PastWork: Story = {
	args: { section: "past-work" },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Section body: past-work")).toBeVisible();
		await expectNoPageOverflow();
	},
};

/** The bodies below are the ones the route renders while a tab's data loads or fails. */
export const FeedbackModeLoading: Story = {
	args: {
		sections: {
			...sections,
			"feedback-mode": <PracticeTreeSkeleton groups={3} practicesPerGroup={3} />,
		},
	},
	play: async ({ canvas }) => {
		// No sending switch claims a state before the settings arrive.
		await expect(canvas.getByRole("tabpanel")).toBeVisible();
		await expect(canvas.queryByRole("switch")).not.toBeInTheDocument();
	},
};

export const FeedbackModeLoadFailed: Story = {
	args: {
		sections: {
			...sections,
			"feedback-mode": (
				<QueryErrorAlert
					error={{ status: 503 }}
					title="We could not load the feedback modes"
					onRetry={fn()}
				/>
			),
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText("We could not load the feedback modes")).toBeVisible();
		await expect(canvas.getByRole("button", { name: "Retry" })).toBeVisible();
		await expect(canvas.queryByRole("switch")).not.toBeInTheDocument();
	},
};

export const AutomatedReviewLoading: Story = {
	args: {
		section: "automated-review",
		sections: { ...sections, "automated-review": <ReviewSettingsSkeleton /> },
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("tabpanel")).toHaveTextContent("Loading review settings…");
	},
};

export const AutomatedReviewLoadFailed: Story = {
	args: {
		section: "automated-review",
		sections: {
			...sections,
			"automated-review": (
				<QueryErrorAlert
					error={{ status: 503 }}
					title="We could not load the review settings"
					onRetry={fn()}
				/>
			),
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText("We could not load the review settings")).toBeVisible();
		await expect(canvas.getByRole("button", { name: "Retry" })).toBeVisible();
	},
};

export const NoModelIsReady: Story = {
	args: { readiness: { enabled: true, model: { status: "ready" } } },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("note", { name: "Practice review readiness" })).toHaveTextContent(
			"Reviews cannot start",
		);
	},
};

export const StateNotKnownYet: Story = {
	args: { readiness: undefined },
	play: async ({ canvas }) => {
		await expect(canvas.queryByRole("status")).not.toBeInTheDocument();
		await expect(canvas.getByRole("tablist")).toBeVisible();
	},
};

export const SectionsAreRealTabs: Story = {
	parameters: { chromatic: { disableSnapshot: true } },
	play: async ({ args, canvas, userEvent }) => {
		const list = canvas.getByRole("tablist");
		await expect(
			within(list)
				.getAllByRole("tab")
				.map((tab) => tab.textContent),
		).toEqual(["Feedback mode", "Automated review", "Past work"]);
		await expect(canvas.getAllByRole("tabpanel")).toHaveLength(1);
		await expect(canvas.queryByText("Section body: automated-review")).not.toBeInTheDocument();
		await expect(canvas.queryByText("Section body: past-work")).not.toBeInTheDocument();

		await userEvent.click(canvas.getByRole("tab", { name: "Automated review" }));
		await expect(args.onSectionChange).toHaveBeenCalledWith("automated-review");
		await expect(await canvas.findByText("Section body: automated-review")).toBeVisible();
		await waitFor(async () =>
			expect(canvas.queryByText("Section body: feedback-mode")).not.toBeInTheDocument(),
		);
	},
};

/**
 * Every tab sits inside the track, at every width: the active one does not stand out past the
 * track's edge, and a wrapped row grows the track instead of spilling over the section below.
 */
export const TabsSitInsideTheTrack: Story = {
	parameters: { chromatic: { disableSnapshot: true } },
	play: async ({ canvas }) => {
		const track = canvas.getByRole("tablist").getBoundingClientRect();
		for (const tab of canvas.getAllByRole("tab")) {
			const box = tab.getBoundingClientRect();
			await expect(box.top).toBeGreaterThanOrEqual(track.top);
			await expect(box.bottom).toBeLessThanOrEqual(track.bottom);
		}
		await expect(canvas.getByRole("tabpanel").getBoundingClientRect().top).toBeGreaterThanOrEqual(
			track.bottom,
		);
	},
};
