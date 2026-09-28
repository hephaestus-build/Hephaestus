import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, waitFor, within } from "storybook/test";

import type { ReviewObservationDetail } from "@/api/types.gen";
import { withPageBehind } from "@/stories/decorators";
import { InLevelStack } from "@/stories/level-stack";
import { expectSettledVisible, settledDrawerPanel } from "@/stories/overlay";
import { expectNoPanelOverflow } from "@/stories/reflow";
import { daysBefore, hoursBefore } from "@/stories/story-clock";
import { expectGenuinelyDisabled } from "@/test/controls";
import { levelsOpenedBy } from "@/test/detail-stack";
import { precedes } from "@/test/dom";

import { observationDetail, reviewObservationDetail, workspacePractices } from "./fixtures";
import { ObservationLevel } from "./ObservationLevel";
import { observationLevel } from "./review-levels";

const ready = (observation: ReviewObservationDetail) => ({
	status: "ready" as const,
	observation,
});

/** Marked incorrect two hours ago, after an earlier correction that was taken back. */
const markedIncorrect: ReviewObservationDetail = {
	...reviewObservationDetail,
	invalidations: [
		{
			id: "inv-2",
			reason: "The 404 comes from the router, not the cache.",
			invalidatedAt: hoursBefore(2),
			invalidatedBy: "Ada Admin",
			providerCopy: "INLINE_REMAINS",
		},
		{
			id: "inv-1",
			reason: "Looked like a false positive at first.",
			invalidatedAt: daysBefore(3),
			invalidatedBy: "Ada Admin",
			restorationReason: "The diff does conflate the two after all.",
			restoredAt: daysBefore(2),
			providerCopy: "UPDATED",
		},
	],
};

/**
 * One observation over whichever list it was opened from: what the review saw, why, from which
 * passages, and what feedback it became. Its one decision — mark it incorrect, or restore it — is
 * the footer, behind a popover that will not submit without a reason, because an invalidation
 * without one is not something the developer could read.
 */
const meta = {
	component: ObservationLevel,
	parameters: { layout: "fullscreen" },
	decorators: [withPageBehind],
	tags: ["autodocs"],
	args: {
		path: { behind: [{ label: "Practice reviews", depth: 0 }], onClose: fn() },
		observation: ready(reviewObservationDetail),
		practices: workspacePractices,
		onChangeValidity: fn(async () => undefined),
		isChangingValidity: false,
	},
	argTypes: { path: { control: false } },
	render: (args) => (
		<InLevelStack
			entry={observationLevel(reviewObservationDetail.id)}
			path={args.path}
			size="detailWide"
		>
			{(level) => <ObservationLevel {...args} {...level} />}
		</InLevelStack>
	),
} satisfies Meta<typeof ObservationLevel>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async () => {
		const panel = within(await settledDrawerPanel());
		await expect(
			panel.getByRole("heading", {
				name: "A cache miss and a permission failure come back as the same 404",
				level: 2,
			}),
		).toBeVisible();
		// Provenance opens the review that made this as the next level, not a UUID to read.
		await expect(levelsOpenedBy(panel.getByRole("link", { name: "in a review" }))).toEqual([
			`review:${reviewObservationDetail.agentJobId}`,
		]);
		await expect(
			levelsOpenedBy(panel.getByRole("link", { name: "See everything reviewed on this work" })),
		).toEqual(["work:pull-request:42"]);
		panel.getByRole("heading", { name: "Why this was raised", level: 3 });
		// The evidence is read as passages, never as the stored record it came from.
		await expect(panel.queryByText(/citations/u)).not.toBeInTheDocument();
		// Each piece of feedback it fed opens over this level, with what became of it.
		const feedback = panel.getAllByRole("link", { name: "Feedback about this observation" });
		await expect(feedback).toHaveLength(2);
		await expect(feedback.map(levelsOpenedBy)).toEqual([
			[expect.stringMatching(/^feedback:/u)],
			[expect.stringMatching(/^feedback:/u)],
		]);
		panel.getByRole("button", { name: "Replaced by newer" });
		// Standing, it offers the one decision there is, and not its reverse.
		await expect(panel.getByRole("button", { name: "Mark as incorrect" })).toBeEnabled();
		await expect(
			panel.queryByRole("button", { name: "Restore observation" }),
		).not.toBeInTheDocument();
	},
};

export const Reflow: Story = {
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	play: async () => {
		await expectNoPanelOverflow(await settledDrawerPanel());
	},
};

export const SupportsAnotherObservation: Story = {
	args: { observation: ready(observationDetail("77777777-7777-7777-7777-777777777777")) },
	play: async () => {
		const panel = within(await settledDrawerPanel());
		panel.getByRole("link", { name: "Feedback this observation supports" });
		await expect(
			panel.queryByRole("link", { name: "Feedback about this observation" }),
		).not.toBeInTheDocument();
	},
};

/** Historical feedback stays visible, but the level does not present it as a current claim. */
export const FeedbackWasWithheld: Story = {
	args: { observation: ready(observationDetail("bbbbbbbb-2222-2222-2222-222222222222")) },
	play: async () => {
		const panel = within(await settledDrawerPanel());
		panel.getByRole("link", { name: "Feedback about this observation" });
		panel.getByText("Found while reviewing past work, which is measured but never sent.");
		await expect(panel.getByText("This observation is no longer current")).toBeVisible();
	},
};

export const NoFeedbackComposed: Story = {
	args: { observation: ready(observationDetail("cccccccc-2222-2222-2222-222222222222")) },
	play: async () => {
		const panel = within(await settledDrawerPanel());
		await expect(panel.getByText("Nothing was said to anybody about this")).toBeVisible();
	},
};

/** The reason is required: an invalidation without one is not something the developer could read. */
export const MarkAsIncorrect: Story = {
	parameters: { chromatic: { disableSnapshot: true } },
	play: async ({ args, userEvent }) => {
		const panel = within(await settledDrawerPanel());
		await userEvent.click(panel.getByRole("button", { name: "Mark as incorrect" }));
		const popover = await screen.findByRole("dialog", {
			name: "Mark this observation as incorrect",
		});
		const reason = within(popover).getByRole("textbox", { name: "Reason" });
		await expectSettledVisible(reason);
		const submit = within(popover).getByRole("button", { name: "Mark as incorrect" });
		await expectGenuinelyDisabled(submit);
		await userEvent.type(reason, "  The 404 comes from the router, not the cache.  ");
		await userEvent.click(submit);
		await expect(args.onChangeValidity).toHaveBeenCalledWith(
			false,
			"The 404 comes from the router, not the cache.",
		);
		await waitFor(async () => expect(screen.queryByRole("textbox", { name: "Reason" })).toBeNull());
	},
};

/** A refused change keeps the form open with the reason the admin wrote, ready for another try. */
export const MarkAsIncorrectFails: Story = {
	args: {
		onChangeValidity: fn(async () => {
			throw new Error("409");
		}),
	},
	parameters: { chromatic: { disableSnapshot: true } },
	play: async ({ args, userEvent }) => {
		const panel = within(await settledDrawerPanel());
		await userEvent.click(panel.getByRole("button", { name: "Mark as incorrect" }));
		const popover = await screen.findByRole("dialog", {
			name: "Mark this observation as incorrect",
		});
		const reason = within(popover).getByRole("textbox", { name: "Reason" });
		await expectSettledVisible(reason);
		await userEvent.type(reason, "The 404 comes from the router.");
		await userEvent.click(within(popover).getByRole("button", { name: "Mark as incorrect" }));
		await expect(args.onChangeValidity).toHaveBeenCalledOnce();
		await expect(within(popover).getByRole("textbox", { name: "Reason" })).toHaveValue(
			"The 404 comes from the router.",
		);
	},
};

/**
 * A comment already on the work could not be changed, so the level says so rather than implying the
 * correction reached it. The footer offers the reverse instead — restore — and nothing else.
 */
export const MarkedIncorrect: Story = {
	args: { observation: ready(markedIncorrect) },
	play: async () => {
		const panelElement = await settledDrawerPanel();
		const panel = within(panelElement);
		await expect(panel.getByText("Marked as incorrect")).toBeVisible();
		// The header says it under the title, where its standing goes.
		const [chip] = panel.getAllByText("Marked incorrect");
		if (!chip) {
			throw new Error("The level does not say it was marked incorrect");
		}
		await expect(precedes(panel.getByRole("heading", { level: 2 }), chip)).toBe(true);
		await expect(
			panel.getByText(
				/Inline comments Hephaestus posted about it are still on the work unchanged/u,
			),
		).toBeVisible();
		await expect(
			panel.getByText("The correction notice was removed from posted comments."),
		).toBeVisible();
		await expect(panel.getByRole("button", { name: "Restore observation" })).toBeEnabled();
		await expect(
			panel.queryByRole("button", { name: "Mark as incorrect" }),
		).not.toBeInTheDocument();
		// Newest first: the correction in force, then the restore that ended the earlier one, then it.
		const corrections = within(panel.getByRole("region", { name: "Corrections" }));
		await expect(corrections.getAllByRole("listitem").map((entry) => entry.textContent)).toEqual([
			expect.stringMatching(/^Marked incorrect by Ada Admin/u),
			expect.stringMatching(/^Restored by an account that no longer exists/u),
			expect.stringMatching(/^Marked incorrect by Ada Admin/u),
		]);
	},
};

/** Restoring is the same popover the other way round, and reports `true` with its reason. */
export const RestoreAnObservation: Story = {
	args: { observation: ready(markedIncorrect) },
	parameters: { chromatic: { disableSnapshot: true } },
	play: async ({ args, userEvent }) => {
		const panel = within(await settledDrawerPanel());
		await userEvent.click(panel.getByRole("button", { name: "Restore observation" }));
		const popover = await screen.findByRole("dialog", { name: "Restore this observation" });
		await expectSettledVisible(
			within(popover).getByText(/Feedback that was stopped stays stopped, and nothing is re-sent/u),
		);
		const reason = within(popover).getByRole("textbox", { name: "Reason" });
		await userEvent.type(reason, "The router does return the cache's 404.");
		await userEvent.click(within(popover).getByRole("button", { name: "Restore observation" }));
		await expect(args.onChangeValidity).toHaveBeenCalledWith(
			true,
			"The router does return the cache's 404.",
		);
	},
};

/** While the server decides, the footer's control shows it is working and takes no second press. */
export const ChangingValidity: Story = {
	args: { isChangingValidity: true },
	play: async () => {
		const panel = within(await settledDrawerPanel());
		await expectGenuinelyDisabled(panel.getByRole("button", { name: "Mark as incorrect" }));
	},
};

/**
 * The drawer is named by its title, so the heading stands while the record loads; the path stays
 * usable, and there is no decision to offer about a record not yet read.
 */
export const Loading: Story = {
	args: { observation: { status: "loading" } },
	play: async () => {
		const panel = within(await settledDrawerPanel());
		await expect(panel.getByRole("heading", { level: 2 })).toHaveAccessibleName(
			"Loading observation",
		);
		panel.getByRole("button", { name: "Practice reviews" });
		await expect(panel.queryByText("Couldn't load this observation")).not.toBeInTheDocument();
		await expect(
			panel.queryByRole("button", { name: "Mark as incorrect" }),
		).not.toBeInTheDocument();
	},
};

/**
 * The error arrives as a prop, so nothing here depends on a request failing at the right moment. A
 * status-less error is the one that reads "check your connection" — see `QueryErrorAlert`.
 */
export const LoadFailed: Story = {
	args: {
		observation: {
			status: "error",
			error: { status: 500, detail: "Something went wrong." },
			onRetry: fn(),
		},
	},
	play: async ({ args, userEvent }) => {
		const panel = within(await settledDrawerPanel());
		await expect(panel.getByText("Couldn't load this observation")).toBeVisible();
		// With no record to name it, the level is named for what it is.
		await expect(screen.getByRole("dialog")).toHaveAccessibleName("Observation");
		await expect(
			panel.queryByRole("button", { name: "Mark as incorrect" }),
		).not.toBeInTheDocument();
		await userEvent.click(panel.getByRole("button", { name: "Retry" }));
		if (args.observation.status !== "error") {
			throw new Error("This story is the error branch");
		}
		await expect(args.observation.onRetry).toHaveBeenCalledOnce();
	},
};
