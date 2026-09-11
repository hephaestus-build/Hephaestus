import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, userEvent } from "storybook/test";

import type { Survey } from "@/api/types.gen";
import { STORY_NOW } from "@/components/common/story-clock";
import { DetailDrawerStack } from "@/components/core/detail-drawer/DetailDrawerStack";
import {
	adminSurvey,
	scheduledSurvey,
	surveyResponses,
	surveySummary,
} from "@/components/feedback/product-survey-fixtures";
import { withPageBehind } from "@/stories/decorators";
import { Stateful } from "@/stories/stateful";
import { expectSettledVisible } from "@/test/overlay";

import { surveyLevel } from "./admin-surveys-search";
import { AdminSurveyResults, type AdminSurveyResultsState } from "./AdminSurveyResults";

type ReadyState = Extract<AdminSurveyResultsState, { status: "ready" }>;

function ready(survey: Survey, overrides: Partial<ReadyState> = {}): ReadyState {
	return {
		status: "ready",
		survey,
		summary: surveySummary,
		responses: surveyResponses,
		page: 0,
		totalPages: 1,
		onPageChange: fn(),
		exporting: false,
		onExport: fn(),
		pending: false,
		onToggleActive: fn(),
		onEnd: fn(),
		onDelete: fn(),
		...overrides,
	};
}

/** A level of the surveys page's drawer stack, so the header, body and scroll are the real ones. */
const meta = {
	title: "Instance admin/Product feedback/Survey results",
	component: AdminSurveyResults,
	parameters: { layout: "fullscreen", chromatic: { viewports: [1440] } },
	decorators: [withPageBehind],
	args: { now: STORY_NOW },
	render: (args) => (
		<Stateful initial={[surveyLevel(adminSurvey.id)]}>
			{(stack, setStack) => (
				<DetailDrawerStack stack={stack} onClose={(depth) => setStack(stack.slice(0, depth))}>
					{(_entry, level) => <AdminSurveyResults {...args} nested={level.nested} />}
				</DetailDrawerStack>
			)}
		</Stateful>
	),
	tags: ["autodocs"],
} satisfies Meta<typeof AdminSurveyResults>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	args: { state: ready(adminSurvey) },
	play: async () => {
		await expectSettledVisible(await screen.findByRole("heading", { name: adminSurvey.title }));
		// 17 of 42 invited.
		await expect(screen.getByText("40%")).toBeVisible();
		// The rating question carries its average; the NPS question its score and its buckets.
		await expect(screen.getByText("17 answered · Average 3.6")).toBeVisible();
		await expect(screen.getByText("14 answered · Average 7.9 · NPS 21")).toBeVisible();
		await expect(
			screen.getByText(/5 promoters \(9–10\) · 5 passives \(7–8\) · 4 detractors/),
		).toBeVisible();
		// A free-text question has no distribution to draw.
		await expect(screen.getByText("9 answers — read them in the responses below.")).toBeVisible();
		// Every response reads as a sentence, whoever sent it and whatever they decided.
		await expect(screen.getByText("Deleted account")).toBeVisible();
		await expect(screen.getByText("Declined", { selector: "p" })).toBeVisible();
		await expect(screen.getByText("Shorter feedback on small pull requests.")).toBeVisible();
	},
};

export const NoResponses: Story = {
	args: {
		state: ready(scheduledSurvey, {
			summary: {
				participation: scheduledSurvey.participation,
				questions: surveySummary.questions.map((question) => ({
					...question,
					answered: 0,
					counts: question.counts.map((count) => ({ ...count, count: 0 })),
					average: undefined,
					score: undefined,
				})),
			},
			responses: [],
		}),
	},
	play: async () => {
		await expectSettledVisible(await screen.findByText("No responses yet."));
		// A rate over nobody is not zero.
		await expect(screen.getByText("—")).toBeVisible();
		await expect(screen.getByRole("button", { name: "Export CSV" })).toBeDisabled();
	},
};

export const Exporting: Story = {
	args: { state: ready(adminSurvey, { exporting: true }) },
	play: async () => {
		await expect(await screen.findByRole("button", { name: "Exporting…" })).toBeDisabled();
	},
};

const onToggleActive = fn();

export const Actions: Story = {
	args: { state: ready(adminSurvey, { onToggleActive }) },
	play: async () => {
		await userEvent.click(
			await screen.findByRole("button", { name: `Actions for ${adminSurvey.title}` }),
		);
		await userEvent.click(await screen.findByRole("menuitem", { name: "Pause" }));
		await expect(onToggleActive).toHaveBeenCalledWith(adminSurvey, false);
	},
};

export const Loading: Story = {
	args: { state: { status: "loading" } },
};

export const Error: Story = {
	args: { state: { status: "error", error: new TypeError("Failed to fetch"), onRetry: fn() } },
	play: async () => {
		await expectSettledVisible(await screen.findByText("Survey results couldn't be loaded"));
	},
};
