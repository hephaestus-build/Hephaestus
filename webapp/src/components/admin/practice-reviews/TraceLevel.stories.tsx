import type { Meta, StoryObj } from "@storybook/react-vite";
import { useSearch } from "@tanstack/react-router";
import { useState } from "react";
import { expect, fn, screen, waitFor, within } from "storybook/test";

import { stackInSearch } from "@/components/layout/detail-drawer/detail-stack";
import { DetailDrawerStack } from "@/components/layout/detail-drawer/DetailDrawerStack";
import { useDetailStack } from "@/components/layout/detail-drawer/use-detail-stack";
import { eventLabel, untouchedItemTrace } from "@/components/practice-trace/fixtures";
import { withPageBehind } from "@/stories/decorators";
import { expectSettledVisible, settledDrawerPanel } from "@/stories/overlay";

import { FeedbackLevel } from "./FeedbackLevel";
import { feedbackDetail, problemDetail, resultDetail, reviewDetail } from "./fixtures";
import { LINEAGE_FEEDBACK, LINEAGE_REVIEW_ID, wholeReview } from "./lineage-fixtures";
import { lineageStateCase, richReviewFixture } from "./lineage-state-fixtures";
import { ProblemLevel } from "./ProblemLevel";
import { ResultLevel } from "./ResultLevel";
import { levelRecord, type RecordRef, recordLevel } from "./review-layers";
import { parsePracticeReviewLevels } from "./review-levels";
import { ReviewItemLink } from "./ReviewItem";
import { ReviewLevel } from "./ReviewLevel";
import { DEFAULT_TRACE_DISPLAY, FULL_TRACE_DISPLAY, type TraceDisplay } from "./trace-display";
import { ALL_STEPS, type TraceFilter } from "./trace-filter";
import { TraceLevel, type TraceLevelProps } from "./TraceLevel";

const [untouchedEvent] = untouchedItemTrace.events;
if (untouchedEvent === undefined) {
	throw new Error("The untouched item carries its event");
}
const PATH = { behind: [{ label: "Practice reviews", depth: 0 }], onClose: fn() };

/**
 * The trace and what opens over it, as the route stacks them: the wide trace, and one record's
 * sheet over it when a step is opened. Records resolve by ID; their links and Escape use the
 * existing URL stack, so a Problem can return to the result it came from.
 */
function TraceStack(props: Omit<TraceLevelProps, "path" | "onOpen">) {
	const stack = useSearch({
		strict: false,
		select: (search) => parsePracticeReviewLevels(stackInSearch(search.detail)),
	});
	const controls = useDetailStack();
	const [display, setDisplay] = useState<TraceDisplay>(props.display);
	const [filter, setFilter] = useState<TraceFilter>(props.filter);
	const open = (record: RecordRef) => controls.open(recordLevel(record));
	return (
		<DetailDrawerStack stack={stack} onClose={controls.close}>
			{(entry, level) => {
				const path = {
					behind: level.depth === 0 ? PATH.behind : [...PATH.behind, { label: "Trace", depth: 1 }],
					onClose: controls.close,
				};
				if (entry.kind === "trace") {
					return (
						<TraceLevel
							{...props}
							nested={level.nested}
							path={path}
							display={display}
							onDisplayChange={setDisplay}
							filter={filter}
							onFilterChange={setFilter}
							onOpen={open}
						/>
					);
				}
				const record = levelRecord(entry);
				if (record?.kind === "feedback") {
					return (
						<FeedbackLevel
							nested={level.nested}
							path={path}
							feedback={{
								status: "ready",
								feedback:
									richReviewFixture.feedbackById.get(record.id) ?? feedbackDetail(record.id),
							}}
							isDeciding={false}
							onApprove={fn()}
							onReject={fn()}
							onChangeWithdrawal={fn(async () => undefined)}
							isChangingWithdrawal={false}
							onRetryWithdrawal={fn()}
							isRetryingWithdrawal={false}
							onCheckDelivery={fn()}
							isCheckingDelivery={false}
						/>
					);
				}
				if (record?.kind === "problem") {
					return (
						<ProblemLevel
							nested={level.nested}
							path={path}
							problem={{
								status: "ready",
								problem: richReviewFixture.problemsById.get(record.id) ?? problemDetail(record.id),
							}}
						/>
					);
				}
				if (record?.kind === "review") {
					return (
						<ReviewLevel
							workspaceSlug="hephaestus"
							nested={level.nested}
							path={path}
							review={{ status: "ready", review: props.review ?? reviewDetail(record.id) }}
							eventLabel={eventLabel}
							onCancel={fn()}
							cancelPending={false}
						/>
					);
				}
				return record?.kind === "result" ? (
					<ResultLevel
						nested={level.nested}
						path={path}
						result={{
							status: "ready",
							result: richReviewFixture.resultsById.get(record.id) ?? resultDetail(record.id),
						}}
						onMark={fn(async () => undefined)}
						onRemoveMark={fn(async () => undefined)}
						isMarking={false}
					/>
				) : null;
			}}
		</DetailDrawerStack>
	);
}

const meta = {
	component: TraceLevel,
	parameters: {
		layout: "fullscreen",
		router: { initialUrl: `/?detail=trace:review:${LINEAGE_REVIEW_ID}` },
	},
	decorators: [withPageBehind],
	tags: ["autodocs"],
	args: {
		path: PATH,
		title: "Review of #1423",
		chain: { status: "ready", lineage: wholeReview },
		here: { kind: "review" },
		onOpen: fn(),
		display: DEFAULT_TRACE_DISPLAY,
		onDisplayChange: fn(),
		filter: ALL_STEPS,
		onFilterChange: fn(),
		// The review as its sheet reads it, so the trace and the sheet tell the same times and model.
		review: reviewDetail(LINEAGE_REVIEW_ID),
	},
	argTypes: { path: { control: false }, chain: { control: false }, review: { control: false } },
	render: (args) => <TraceStack {...args} />,
} satisfies Meta<typeof TraceLevel>;

export default meta;
type Story = StoryObj<typeof meta>;

function showToggle(panel: HTMLElement, name: string): HTMLElement {
	return within(within(panel).getByRole("group", { name: "Show" })).getByRole("button", { name });
}

/**
 * As a reader first opens it: the verdict on top, the four stages, the review "You started here" with
 * its times. Times are on; system info, siblings and connections are off, and nothing is unfolded.
 */
export const Default: Story = {
	play: async () => {
		const element = await settledDrawerPanel();
		const panel = within(element);
		panel.getByText("You started here");
		await expect(showToggle(element, "Times")).toHaveAttribute("aria-pressed", "true");
		for (const name of ["System info", "Siblings", "Connections"]) {
			await expect(showToggle(element, name)).toHaveAttribute("aria-pressed", "false");
		}
		const story = panel.getByRole("group", { name: "How this came about" });
		await expect(story.querySelector("[data-trace-verdict]")).not.toBeNull();
		await expect(story.querySelector("[data-step-facts]")).toBeNull();
		await expect(story.querySelectorAll("time").length).toBeGreaterThan(0);
	},
};

export const AllOn: Story = {
	args: { display: FULL_TRACE_DISPLAY },
	play: async () => {
		const panel = await settledDrawerPanel();
		for (const name of ["Times", "System info", "Siblings", "Connections"]) {
			await expect(showToggle(panel, name)).toHaveAttribute("aria-pressed", "true");
		}
		await expect(panel.querySelector("[data-step-facts]")).toHaveTextContent(/Asked for/u);
		await expect(panel.querySelector("[data-step-facts]")).toHaveTextContent(
			/openai\/gpt-oss-120b/u,
		);
		await waitFor(async () => {
			await expect(panel.querySelectorAll("path[data-connector-line]").length).toBeGreaterThan(0);
		});
	},
};

export const AllOff: Story = {
	args: {
		display: { times: false, system: false, siblings: false, connections: false },
	},
	play: async () => {
		const panel = await settledDrawerPanel();
		const steps = within(panel).getByRole("list", { name: "Steps, oldest first" });
		await expect(steps.querySelectorAll("time")).toHaveLength(0);
		await expect(panel.querySelector("[data-step-facts]")).toBeNull();
		await expect(panel.querySelectorAll("path[data-connector-line]")).toHaveLength(0);
	},
};

export const ExpandAll: Story = {
	play: async ({ userEvent }) => {
		const element = await settledDrawerPanel();
		const panel = within(element);
		await userEvent.click(panel.getByRole("button", { name: "Expand all" }));
		const steps = panel.getByRole("list", { name: "Steps, oldest first" });
		await expect(steps.querySelector('[data-step="before"]')).toBeNull();
		await expect(element.querySelectorAll("[data-step-facts]").length).toBeGreaterThan(3);
		await userEvent.click(panel.getByRole("button", { name: "Collapse all" }));
		await expect(element.querySelector("[data-step-facts]")).toBeNull();
	},
};

export const StartedOnFeedback: Story = {
	args: {
		here: { kind: "feedback", id: LINEAGE_FEEDBACK.praise },
		display: { ...DEFAULT_TRACE_DISPLAY, system: true },
	},
	play: async () => {
		const panel = await settledDrawerPanel();
		await expect(panel.querySelector("[data-step-facts]")).toHaveTextContent(/Destination/u);
	},
};

/** Only what was lost on the way: the filter lists every step it keeps, so Siblings is held. */
export const OnlyLosses: Story = {
	args: { filter: { kind: "losses" } },
	play: async () => {
		const panel = await settledDrawerPanel();
		await expect(showToggle(panel, "Siblings")).toBeDisabled();
	},
};

export const RecordOverTheTrace: Story = {
	play: async ({ userEvent }) => {
		const panel = within(await settledDrawerPanel());
		await userEvent.click(
			panel.getByRole("button", {
				name: /A cache miss and a permission failure come back as the same 404/u,
			}),
		);
		const over = await screen.findByRole("dialog", {
			name: "A cache miss and a permission failure come back as the same 404",
		});
		await expect(within(over).queryByRole("group", { name: "How this came about" })).toBeNull();
		await expect(over.querySelector("[data-trace-verdict]")).toBeNull();
		await userEvent.keyboard("{Escape}");
		await waitFor(async () => {
			await expect(screen.getAllByRole("dialog")).toHaveLength(1);
		});
	},
};

export const ScanWithManyJobs: Story = {
	args: {
		chain: { status: "ready", lineage: lineageStateCase("scan-many-jobs").lineage },
		review: lineageStateCase("scan-many-jobs").review,
	},
	play: async () => {
		const panel = await settledDrawerPanel();
		await expect(panel.querySelector('[data-step="end"]')).not.toBeNull();
	},
};

export const EveryDeliveryState: Story = {
	args: {
		chain: { status: "ready", lineage: lineageStateCase("delivery-states").lineage },
		display: FULL_TRACE_DISPLAY,
		review: undefined,
	},
	play: async () => {
		const panel = await settledDrawerPanel();
		await expect(panel.querySelector('[data-step="end"]')).not.toBeNull();
	},
};

export const NoReviewStarted: Story = {
	args: {
		title: "Define the practice-binding contract",
		chain: {
			status: "unreviewed",
			item: untouchedItemTrace.item,
			event: untouchedEvent,
			eventLabel: eventLabel(untouchedEvent.name),
		},
		here: { kind: "work" },
		workRecord: { kind: "item", id: untouchedItemTrace.item.id },
	},
	play: async () => {
		const panel = await settledDrawerPanel();
		await expect(panel.querySelector("[data-trace-verdict]")).toHaveTextContent(
			/^No review started on this work/u,
		);
	},
};

/** While the chain loads, the sheet holds its shape. */
export const Loading: Story = {
	args: { chain: { status: "loading" } },
	play: async () => {
		const panel = within(await settledDrawerPanel());
		await expect(panel.queryByRole("group", { name: "How this came about" })).toBeNull();
	},
};

export const Empty: Story = {
	args: { chain: { status: "none" } },
	play: async () => {
		const panel = within(await settledDrawerPanel());
		panel.getByText("Nothing was recorded about this record, so there is no trace to show.");
	},
};

export const LoadError: Story = {
	args: { chain: { status: "error", error: { status: 500 }, onRetry: fn() } },
	play: async () => {
		const panel = within(await settledDrawerPanel());
		await expect(panel.getByRole("button", { name: "Retry" })).toBeVisible();
	},
};

const richReviewArgs = {
	title: `Review of ${richReviewFixture.review.item.label}`,
	description: <ReviewItemLink item={richReviewFixture.review.item} />,
	chain: { status: "ready", lineage: richReviewFixture.lineage },
	review: richReviewFixture.review,
	display: DEFAULT_TRACE_DISPLAY,
} satisfies Partial<TraceLevelProps>;

export const RichReviewOverview: Story = {
	args: richReviewArgs,
	parameters: {
		docs: {
			description: {
				story:
					"Deterministic UI example with 24 observations and 12 Problems. These records and the imported usage display are synthetic story data, not a live model review.",
			},
		},
	},
};

export const RichReview: Story = {
	args: richReviewArgs,
	play: async ({ userEvent }) => {
		let panel = within(await settledDrawerPanel());
		let results = panel.getByRole("list", { name: "Observations of this review" });
		await expect(results.children).toHaveLength(24);
		for (const [outcome, count] of [
			["Met", 10],
			["Not met", 6],
			["Does not apply", 4],
			["Could not determine", 4],
		] as const) {
			await expect(within(results).getAllByText(outcome, { exact: true })).toHaveLength(count);
		}
		for (const disclosure of within(results).getAllByRole("button", {
			name: /^\d+ problems?: .*of observation/u,
		})) {
			await userEvent.click(disclosure);
		}
		await expect(
			within(results).getAllByRole("button", { name: /^Problem \d+ of observation/u }),
		).toHaveLength(12);

		await userEvent.click(
			within(results).getByRole("button", {
				name: /Keep side effects at the boundary: reviewed boundary 17/u,
			}),
		);
		let record = await screen.findByRole("dialog", {
			name: "Keep side effects at the boundary: reviewed boundary 17",
		});
		await expectSettledVisible(record);
		await expect(
			within(record).getByText("Keep side effects at the boundary", { exact: true }),
		).toBeVisible();
		await userEvent.keyboard("{Escape}");
		await waitFor(async () => {
			await expect(screen.getAllByRole("dialog")).toHaveLength(1);
		});

		panel = within(await settledDrawerPanel());
		results = panel.getByRole("list", { name: "Observations of this review" });
		await expect(results.children).toHaveLength(24);
		await userEvent.click(
			within(results).getByRole("button", {
				name: /Errors carry context.*Failures lose their context/u,
			}),
		);
		record = await screen.findByRole("dialog", { name: "Failures lose their context" });
		await expectSettledVisible(record);
		await expect(within(record).getByText("Errors carry context", { exact: true })).toBeVisible();
		await userEvent.click(
			within(record).getByRole("link", { name: "Recovery step 3 is missing for boundary 2" }),
		);
		const problem = await screen.findByRole("dialog", {
			name: "Recovery step 3 is missing for boundary 2",
		});
		await expectSettledVisible(problem);
		await expect(
			within(problem).getByText(
				"Name the failed operation and add the recovery step for boundary 2, checkpoint 3.",
			),
		).toBeVisible();
		await userEvent.click(
			within(problem).getByRole("link", { name: "Failures lose their context" }),
		);
		record = await screen.findByRole("dialog", { name: "Failures lose their context" });
		await expectSettledVisible(record);
		await expect(within(record).getByText("Errors carry context", { exact: true })).toBeVisible();
		await userEvent.keyboard("{Escape}");
		await waitFor(async () => {
			await expect(screen.getAllByRole("dialog")).toHaveLength(1);
		});

		panel = within(await settledDrawerPanel());
		results = panel.getByRole("list", { name: "Observations of this review" });
		await userEvent.click(
			within(results).getByRole("button", {
				name: /Distinguish failure causes.*Failures lose their context/u,
			}),
		);
		record = await screen.findByRole("dialog", { name: "Failures lose their context" });
		await expectSettledVisible(record);
		await expect(
			within(record).getByText("Distinguish failure causes", { exact: true }),
		).toBeVisible();
		await expect(
			within(record).getByText("Recovery step 3 is missing for boundary 7"),
		).toBeVisible();
		await userEvent.keyboard("{Escape}");
		await waitFor(async () => {
			await expect(screen.getAllByRole("dialog")).toHaveLength(1);
		});

		panel = within(await settledDrawerPanel());
		await userEvent.click(panel.getByRole("button", { name: /^Feedback A\s/u }));
		await screen.findByRole("heading", { name: "What it says" });
		let feedbackPanel = screen.getByRole("dialog");
		await expectSettledVisible(feedbackPanel);
		await expect(
			within(feedbackPanel).getAllByText(
				"Name the failed operation at boundary 2, then explain its recovery step.",
				{ exact: true },
			)[0],
		).toBeVisible();
		await expect(
			within(feedbackPanel).getAllByRole("link", { name: "Failures lose their context" }),
		).toHaveLength(2);
		await expect(
			within(feedbackPanel).getAllByRole("link", {
				name: /^(?:One|Another) problem of this observation:/u,
			}),
		).toHaveLength(6);
		for (const [index, practice] of [
			"Errors carry context",
			"Distinguish failure causes",
		].entries()) {
			const link = within(feedbackPanel)
				.getAllByRole("link", { name: "Failures lose their context" })
				.at(index);
			if (link === undefined) {
				throw new globalThis.Error("Each result basis must remain independently navigable");
			}
			await userEvent.click(link);
			const resultPanel = await screen.findByRole("dialog", {
				name: "Failures lose their context",
			});
			await expectSettledVisible(resultPanel);
			await expect(within(resultPanel).getByText(practice, { exact: true })).toBeVisible();
			const related = within(resultPanel).getByRole("list", {
				name: "Feedback written from this observation",
			});
			await userEvent.click(within(related).getByRole("link", { name: "On the work, Delivered" }));
			await screen.findByRole("heading", { name: "What it says" });
			feedbackPanel = screen.getByRole("dialog");
			await expectSettledVisible(feedbackPanel);
			await expect(
				within(feedbackPanel).getAllByText(
					"Name the failed operation at boundary 2, then explain its recovery step.",
					{ exact: true },
				)[0],
			).toBeVisible();
		}
		await userEvent.keyboard("{Escape}");
		await screen.findByRole("group", { name: "How this came about" });
		panel = within(await settledDrawerPanel());
		await userEvent.click(panel.getByRole("combobox", { name: "Steps" }));
		await userEvent.click(screen.getByRole("option", { name: "Only met (10)" }));
		results = panel.getByRole("list", { name: "Observations of this review" });
		await expect(results.children).toHaveLength(10);
		const mixedFeedback = panel.getByRole("button", { name: /^Feedback F\s/u });
		await expect(within(mixedFeedback).getByText("21", { exact: true })).toBeVisible();
		await expect(within(mixedFeedback).getByText("24", { exact: true })).toBeVisible();
		await userEvent.click(mixedFeedback);
		await screen.findByRole("heading", { name: "What it says" });
		feedbackPanel = screen.getByRole("dialog");
		await expectSettledVisible(feedbackPanel);
		await expect(
			within(feedbackPanel).getByRole("link", {
				name: "Handle missing configuration: reviewed boundary 21",
			}),
		).toBeVisible();
		await expect(
			within(feedbackPanel).getByRole("link", { name: "Explain the change: reviewed boundary 24" }),
		).toBeVisible();
	},
};

const richResultFocus = richReviewFixture.results.find((result) => result.outcome === "NOT_MET");
if (richResultFocus === undefined) {
	throw new globalThis.Error("The rich review must include a Not met result");
}

export const RichResultPath: Story = {
	args: {
		...richReviewArgs,
		here: { kind: "result", id: richResultFocus.id },
	},
	play: async ({ userEvent }) => {
		const panel = within(await settledDrawerPanel());
		await expect(
			panel.getByRole("list", { name: "Observations on this path" }).children,
		).toHaveLength(1);
		await userEvent.click(
			panel.getByRole("button", { name: "Show all 24 observations in the table" }),
		);
		const listed = panel.getByRole("list", { name: "Observations of this review" });
		await expect(listed.children).toHaveLength(12);
		await userEvent.click(panel.getByRole("button", { name: "Show 12 more observations" }));
		await expect(listed.children).toHaveLength(24);
		await userEvent.click(
			panel.getByRole("button", { name: "Show only the observations on this path" }),
		);
		await expect(
			panel.getByRole("list", { name: "Observations on this path" }).children,
		).toHaveLength(1);
	},
};
