import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";

import type { EvidenceCitation } from "@/api/types.gen";
import { mockDescriptionAnswers, mockOpenAnswers } from "@/mocks/fixtures/practice";
import { expectNoOverflowingElement } from "@/stories/reflow";

import { ObservationAnswerList } from "./ObservationAnswerList";

const citations: EvidenceCitation[] = [
	{
		sourceKind: "scm.pull-request.core",
		artifactPath: "context/metadata.json",
		path: "context/metadata.json",
		startLine: 4,
		endLine: 4,
		quote: "Add a cache in front of the review query",
		quoteRedacted: false,
	},
	{
		sourceKind: "scm.pull-request.diff",
		artifactPath: "context/change.json",
		path: "server/src/main/java/ReviewQueryService.java",
		side: "NEW",
		startLine: 41,
		endLine: 44,
		quote: "+ return cache.get(key, this::load);",
		quoteRedacted: false,
	},
];

const meta = {
	component: ObservationAnswerList,
	args: { answers: mockDescriptionAnswers, citations },
	parameters: { layout: "padded" },
	tags: ["autodocs"],
} satisfies Meta<typeof ObservationAnswerList>;

export default meta;
type Story = StoryObj<typeof meta>;

/** The deciding answer comes first, with what was searched for the absence it rests on. */
export const Decided: Story = {
	play: async ({ canvas }) => {
		const [first] = canvas.getAllByRole("listitem");
		await expect(first).toHaveTextContent("Says why the change existsNo");
		await expect(canvas.getByText("Decided the outcome")).toBeVisible();
		await expect(first).toHaveTextContent(
			"Cites: The pull request itself · server/src/main/java/ReviewQueryService.java:41–44",
		);
	},
};

/** An open answer names what would settle it. */
export const Open: Story = {
	args: { answers: mockOpenAnswers },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("The body of issue #412")).toBeVisible();
	},
};

/** Without the passages beside it, an answer does not name lines. */
export const WithoutCitations: Story = {
	args: { citations: undefined },
	play: async ({ canvas }) => {
		await expect(canvas.queryByText("Cites:")).toBeNull();
	},
};

export const Reflow: Story = {
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	play: async ({ canvasElement }) => {
		await expectNoOverflowingElement(canvasElement);
	},
};
