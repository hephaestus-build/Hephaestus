import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, within } from "storybook/test";

import { WRITTEN_BY_HEPHAESTUS } from "@/components/common/WrittenByHephaestusNote";
import { withPageBehind } from "@/stories/decorators";
import { InLevelStack } from "@/stories/level-stack";
import { settledDrawerPanel } from "@/stories/overlay";
import {
	expectSectionHeadings,
	expectSectionLines,
	expectSectionsFold,
} from "@/stories/section-lines";
import { levelsOpenedBy } from "@/test/detail-stack";

import { evidenceHiddenProblemDetail, reviewProblemDetail } from "./fixtures";
import { forgetLevelFolds } from "./level-folds";
import { ProblemLevel } from "./ProblemLevel";
import { problemLevel } from "./review-levels";

const meta = {
	component: ProblemLevel,
	parameters: { layout: "fullscreen" },
	decorators: [withPageBehind],
	tags: ["autodocs"],
	args: {
		path: { behind: [{ label: "Practice reviews", depth: 0 }], onClose: fn() },
		problem: { status: "ready", problem: reviewProblemDetail },
	},
	argTypes: { path: { control: false } },
	render: (args) => (
		<InLevelStack entry={problemLevel(reviewProblemDetail.id)} path={args.path}>
			{(level) => <ProblemLevel {...args} {...level} />}
		</InLevelStack>
	),
	// A fold is kept in this browser, so every story starts with all sections open.
	beforeEach: () => {
		forgetLevelFolds("problem");
		return () => forgetLevelFolds("problem");
	},
} satisfies Meta<typeof ProblemLevel>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ userEvent }) => {
		const panelElement = await settledDrawerPanel();
		const panel = within(panelElement);
		panel.getByText(reviewProblemDetail.summary);
		await expect(panel.getByText(WRITTEN_BY_HEPHAESTUS)).toBeVisible();
		// The facts open the sheet as its "What it is", as on every other sheet.
		await expect(panel.getAllByRole("heading", { level: 3 })[0]).toHaveTextContent("What it is");
		panel.getByRole("heading", { name: "Evidence", level: 3 });
		// The same section size and lines as every sheet, and every section folds.
		await expectSectionLines(panelElement, 2);
		await expectSectionHeadings(panelElement);
		await expectSectionsFold(panelElement, userEvent);
		// Cited in the repository's code, so the problem has a place in the work.
		panel.getByText("Location");
		await expect(
			levelsOpenedBy(
				panel.getByRole("link", {
					name: reviewProblemDetail.result.summary ?? reviewProblemDetail.result.practiceName,
				}),
			),
		).toEqual([`result:${reviewProblemDetail.resultId}`]);
		await expect(panel.queryByRole("button", { name: /^Mark/u })).not.toBeInTheDocument();
	},
};

/** As the live review recorded it: the work's own title and description, read from its capture. */
const METADATA = "context/metadata.json";
const quotedTitle = '"title" : "Test MR for close/reopen"';
const metadataProblem = {
	...reviewProblemDetail,
	sourceKind: "scm.pull-request.core",
	path: METADATA,
	startLine: 3,
	endLine: 3,
	side: null,
	problem: {
		...reviewProblemDetail.problem,
		citations: [
			{
				sourceKind: "scm.pull-request.core",
				sourcePath: METADATA,
				path: METADATA,
				quote: quotedTitle,
				startLine: 3,
				endLine: 3,
				side: null,
				commit: null,
				verification: null,
			},
		],
	},
};

/** A capture file is not a place in the work: no Location fact, no path, and no placeholder. */
export const CitedOnTheWorkItself: Story = {
	args: { problem: { status: "ready", problem: metadataProblem } },
	play: async () => {
		const panel = within(await settledDrawerPanel());
		await expect(panel.queryByText("Location")).not.toBeInTheDocument();
		await expect(panel.queryByText(METADATA)).not.toBeInTheDocument();
		await expect(panel.queryByText("Location not recorded")).not.toBeInTheDocument();
		panel.getByRole("heading", { name: "The reviewed work itself", level: 4 });
		await expect(panel.getByText(quotedTitle)).toBeVisible();
	},
};

export const EvidenceHidden: Story = {
	args: { problem: { status: "ready", problem: evidenceHiddenProblemDetail } },
	play: async () => {
		const panelElement = await settledDrawerPanel();
		const panel = within(panelElement);
		panel.getByText("The evidence of this problem is hidden.");
		// Its explanation and next step are hidden too, so nothing is labelled.
		await expect(panel.queryByText(WRITTEN_BY_HEPHAESTUS)).toBeNull();
		// Only the facts stand, so no line is drawn.
		await expectSectionLines(panelElement, 0);
		panel.getByRole("heading", { name: "What it is", level: 3 });
		await expect(panel.queryByRole("heading", { name: "Evidence" })).not.toBeInTheDocument();
	},
};

export const Loading: Story = {
	args: { problem: { status: "loading" } },
};

export const LoadFailed: Story = {
	args: { problem: { status: "error", error: { status: 500 }, onRetry: fn() } },
	play: async () => {
		const panel = within(await settledDrawerPanel());
		panel.getByText("We could not load this problem");
	},
};
