import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";

import type { Problem, ProblemSummary } from "@/api/types.gen";
import { reviewResultDetail } from "@/components/admin/practice-reviews/fixtures";
import { ResultEvidence } from "@/components/admin/practice-reviews/ResultEvidence";
import { SEVERITY_DEFS } from "@/components/practice-vocabulary/severity-defs";
import { expectNoPageOverflow } from "@/stories/reflow";

import { ProblemList } from "./ProblemList";

const { problems } = reviewResultDetail;
const [first] = problems;
if (first === undefined) {
	throw new Error("The result fixture must record a problem");
}

const summaries: ProblemSummary[] = problems.map(({ id, severity, summary }) => ({
	id,
	severity,
	summary,
}));

/** As the server sends a problem whose sources the reader may not see. */
const hidden: Problem[] = problems.map((problem) => ({
	...problem,
	summary: "Evidence hidden",
	explanation: null,
	citations: [],
	basis: null,
	nextStep: null,
}));

/** The list's own items: an evidence view nests lists of its own. */
function problemItems(canvas: {
	getByRole: (role: "list", options: { name: string }) => HTMLElement;
}) {
	return [...canvas.getByRole("list", { name: "Problems" }).children];
}

const meta = {
	component: ProblemList,
	parameters: {
		layout: "padded",
		chromatic: { viewports: [320, 768, 1440] },
	},
	tags: ["autodocs"],
	args: {
		problems,
		evidence: (problem: Problem) => <ResultEvidence citations={problem.citations} />,
	},
} satisfies Meta<typeof ProblemList>;

export default meta;
type Story = StoryObj<typeof meta>;

/** The operator's result sheet: each problem in full, in recorded order, with its evidence. */
export const Full: Story = {
	play: async ({ canvas }) => {
		const items = problemItems(canvas);
		await expect(items).toHaveLength(problems.length);
		await expect(items[0]).toHaveTextContent(SEVERITY_DEFS[first.severity].label);
		await expect(items[0]).toHaveTextContent(first.summary.trim());
		await expectNoPageOverflow();
	},
};

/** A list read sends severity and summary only, so the card has no evidence and no next step. */
export const SummariesOnly: Story = {
	args: { problems: summaries },
	play: async ({ canvas }) => {
		const items = problemItems(canvas);
		await expect(items).toHaveLength(summaries.length);
		await expect(canvas.queryByText("Next step")).toBeNull();
	},
};

/** Hidden evidence keeps each problem and its severity, and says that the rest is hidden. */
export const EvidenceHidden: Story = {
	args: {
		problems: hidden,
		evidence: (problem: Problem) => <ResultEvidence citations={problem.citations} hidden />,
	},
	play: async ({ canvas }) => {
		const items = problemItems(canvas);
		await expect(items).toHaveLength(hidden.length);
		await expect(canvas.getAllByText("Evidence hidden")).toHaveLength(hidden.length);
	},
};

/** Twenty problems stay one readable column at every width. */
export const Many: Story = {
	args: {
		problems: Array.from({ length: 20 }, (_, index) => ({
			...first,
			id: `${first.id}-${String(index)}`,
			position: index,
		})),
	},
	play: async ({ canvas }) => {
		await expect(problemItems(canvas)).toHaveLength(20);
		await expectNoPageOverflow();
	},
};
