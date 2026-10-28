import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, userEvent, within } from "storybook/test";

import {
	definitionOf,
	mockDefinitionOptions,
	mockScan,
} from "@/components/admin/practice-editor/fixtures";
import {
	realPracticeDefinition,
	realPracticeKind,
	realPracticeSlug,
} from "@/mocks/fixtures/practice-catalog";
import { bundledGuidance, bundledPractice } from "@/stories/practice-guidance-story-mock-data";
import { expectNoOverflowingElement } from "@/stories/reflow";
import { precedes } from "@/test/dom";

import { practiceScript } from "./fixtures";
import { PracticeDefinitionPreview } from "./PracticeDefinitionPreview";

const definition = definitionOf({
	name: "Describe what changed and why",
	criteria: "Confirm the pull request explains both the change and its motivation.",
	script: practiceScript(),
});

const meta = {
	component: PracticeDefinitionPreview,
	parameters: { layout: "padded", chromatic: { viewports: [320, 1440] } },
	// A real bundled practice, not invented copy: the layout claims below are about its size.
	args: {
		slug: realPracticeSlug,
		kind: realPracticeKind,
		definition: realPracticeDefinition,
		options: mockDefinitionOptions,
	},
	tags: ["autodocs"],
} satisfies Meta<typeof PracticeDefinitionPreview>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Complete: Story = {
	args: { kind: "scm.pull_request", definition, version: 4 },
	play: async ({ canvas }) => {
		await expect(canvas.queryByRole("alert")).not.toBeInTheDocument();
		const settings = canvas.getByRole("region", { name: "Review settings" });
		const row = (label: string) => within(settings).getByText(label).nextElementSibling;
		await expect(row("Review on")).toHaveTextContent(/Opened.*Marked ready for review/u);
		await expect(row("Review on")).toHaveTextContent(/Draft status: Ready for review/u);
		await expect(row("About")).toHaveTextContent("Author");
		await expect(row("Reviewed by")).toHaveTextContent("Hephaestus");
		await expect(row("Reads")).toHaveTextContent("Pull request details (required)");
		await expect(row("Script")).toHaveTextContent("Practice script");
		await expect(row("Scan support")).toHaveTextContent("Not included in scans");
		await expect(row("Version")).toHaveTextContent("Version 4");
		await expect(canvas.queryByText(/definePracticeScript/u)).not.toBeInTheDocument();
		await userEvent.click(canvas.getByRole("button", { name: "Practice script" }));
		await expect(canvas.getByText(/definePracticeScript/u)).toBeVisible();
		const decides = canvas.getByRole("button", { name: "How it decides" });
		await expect(precedes(settings, decides)).toBe(true);
	},
};

/** A migrated practice bound to an event of another kind of work: Hephaestus never selects it. */
export const NotRunnable: Story = {
	args: {
		kind: "scm.pull_request",
		definition: definitionOf({
			reviewOn: { events: ["scm.pull_request_review.submitted"], state: {} },
		}),
	},
	play: async ({ canvas }) => {
		const alert = canvas.getByRole("alert");
		await expect(alert).toHaveTextContent("Hephaestus does not run this practice");
		await expect(alert).toHaveTextContent(
			/Pull request review submitted, which this kind of work does not offer/u,
		);
	},
};

export const BuiltInScript: Story = {
	args: { kind: "scm.pull_request", definition: { ...definition, script: null } },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Built-in")).toBeVisible();
		await expect(canvas.queryByRole("button", { name: "Practice script" })).not.toBeInTheDocument();
	},
};

export const IncludedInScans: Story = {
	args: { kind: "scm.pull_request", definition: { ...definition, scan: mockScan } },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Include: **/*.ts, **/*.tsx")).toBeVisible();
		await expect(canvas.getByText("Exclude: **/*.gen.ts")).toBeVisible();
	},
};

export const ScansUnsupported: Story = {
	args: { kind: "scm.issue", definition: definitionOf({ reviewOn: { events: [], state: {} } }) },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Scans do not review this kind of work")).toBeVisible();
		await expect(canvas.getByText("Only when someone asks")).toBeVisible();
	},
};

export const ReviewedByPeople: Story = {
	args: {
		kind: "scm.pull_request",
		definition: definitionOf({
			reviewedBy: {
				by: "PEOPLE",
				humanReason: { code: "DESIGN_JUDGEMENT", description: "Design choices need a person." },
				limits: [],
			},
			reads: { contract: "3.0.0", sources: [] },
		}),
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText("People")).toBeVisible();
		await expect(canvas.getByText("Design choices need a person.")).toBeVisible();
	},
};

export const ReviewedByNobody: Story = {
	args: {
		kind: "scm.pull_request",
		definition: definitionOf({
			reviewedBy: { by: "NOBODY", humanReason: null, limits: [] },
			reads: { contract: "3.0.0", sources: [] },
		}),
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Nobody")).toBeVisible();
		await expect(
			canvas.getByText("This practice offers guidance without automated review."),
		).toBeVisible();
	},
};

export const ScopedMerger: Story = {
	args: {
		kind: "scm.pull_request",
		definition: definitionOf({
			about: "MERGER",
			appliesWhen: {
				otherwise: "The change has no Swift code.",
				anyOf: [{ changedPath: ["**/*.swift"] }],
			},
		}),
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Whoever merged it")).toBeVisible();
		await expect(canvas.getByText("Changes a path matching **/*.swift")).toBeVisible();
		await expect(canvas.getByText("Otherwise: The change has no Swift code.")).toBeVisible();
	},
};

export const AboutTheWork: Story = {
	args: { kind: "scm.pull_request", definition: definitionOf({ about: null }) },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("The work")).toBeVisible();
	},
};

export const LongContentInDarkMode: Story = {
	args: {
		kind: "scm.pull_request",
		definition: {
			...definition,
			criteria:
				"Confirm that the pull request explains the behavior change, the operational constraints that shaped it, the alternatives considered, and the evidence used to verify the result. Stay silent when the change is generated automatically and no meaningful author decision exists.",
			guidance: {
				...definition.guidance,
				whatGoodLooksLike:
					"The description connects the implementation to the user-visible outcome, names the rejected alternatives without reproducing the entire design discussion, and gives reviewers concrete verification steps.",
			},
		},
	},
	globals: { theme: "dark" },
	play: async ({ canvas }) => {
		await expect(canvas.getByText(/connects the implementation/u)).toBeVisible();
	},
};

/**
 * Every bundled practice writes `criteria` as markdown, and the editor promises it is supported.
 * Rendered as one paragraph it reached the reader as literal `##` and `-` characters.
 */
export const CriteriaIsMarkdown: Story = {
	args: {
		kind: "scm.pull_request",
		definition: {
			...definition,
			script: null,
			criteria: [
				"## The standard",
				"Judge whether the change is packaged at a size a reviewer can read *end to end*.",
				"",
				"## What to look for",
				"- One self-contained concern",
				"- A `diff` a reviewer can hold in their head",
			].join("\n"),
		},
	},
	play: async ({ canvas }) => {
		await userEvent.click(canvas.getByRole("button", { name: "How it decides" }));
		const standard = await canvas.findByRole("heading", { name: "The standard", level: 4 });
		await expect(standard).toBeVisible();
		await expect(canvas.getByText("end to end").tagName).toBe("EM");
		await expect(canvas.queryByText(/^## /u)).not.toBeInTheDocument();
	},
};

// Adoption needs the rationale first; model instructions belong behind the criteria disclosure.
export const RationaleLeadsTheCriteriaFollow: Story = {
	play: async ({ canvas }) => {
		const rationale = canvas.getByText(realPracticeDefinition.guidance.whyItMatters);
		await expect(rationale).toBeVisible();
		await expect(
			canvas.queryByRole("heading", { name: "The standard", level: 4 }),
		).not.toBeInTheDocument();
		const criteria = canvas.getByRole("button", { name: "How it decides" });
		await expect(precedes(rationale, criteria)).toBe(true);
	},
};

export const RationaleOnly: Story = {
	args: {
		definition: {
			...realPracticeDefinition,
			guidance: { ...realPracticeDefinition.guidance, whatGoodLooksLike: null },
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText(realPracticeDefinition.guidance.whyItMatters)).toBeVisible();
		await expect(
			canvas.queryByRole("heading", { name: "What good looks like" }),
		).not.toBeInTheDocument();
	},
};

export const UnknownItemKind: Story = {
	args: { options: { ...mockDefinitionOptions, itemTypes: [] } },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("docs.document.core (required in full)")).toBeVisible();
	},
};

export const NarrowViewport: Story = {
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	play: async ({ canvasElement }) => {
		await expectNoOverflowingElement(canvasElement);
	},
};

/**
 * With a visual, what good looks like captions the picture instead of standing under a heading,
 * exactly as the developer's practice level shows it. The guide follows in full under its label, so
 * the admin reads all that developers will.
 */
export const WithVisualAndGuide: Story = {
	args: {
		kind: "scm.pull_request",
		definition: {
			...definition,
			guidance: {
				...definition.guidance,
				...bundledPractice,
				visual: bundledGuidance.visual,
				guide: bundledGuidance.guide,
			},
		},
	},
	play: async ({ canvas }) => {
		await expect(
			canvas.getByRole("figure", { name: bundledPractice.whatGoodLooksLike }),
		).toBeVisible();
		await expect(canvas.queryByRole("heading", { name: "What good looks like" })).toBeNull();
		const guide = canvas.getByRole("region", { name: "Guide" });
		await expect(
			within(guide).getByRole("heading", { level: 4, name: "How to do it" }),
		).toBeVisible();
		await expect(precedes(guide, canvas.getByRole("region", { name: "Review settings" }))).toBe(
			true,
		);
	},
};

/**
 * Under a record sheet's `h3` section (the admin's practice sheet), every label and fold steps down
 * one level, so the outline skips none; Practice setup and the catalog keep the levels above.
 */
export const UnderASheetSection: Story = {
	args: {
		...WithVisualAndGuide.args,
		headingLevel: 4,
		definition: {
			...definition,
			script: null,
			criteria: CriteriaIsMarkdown.args?.definition?.criteria ?? "",
			guidance: { ...definition.guidance, ...bundledPractice, guide: bundledGuidance.guide },
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.queryAllByRole("heading", { level: 2 })).toHaveLength(0);
		await expect(canvas.queryAllByRole("heading", { level: 3 })).toHaveLength(0);
		canvas.getByRole("heading", { level: 4, name: "What good looks like" });
		const guide = canvas.getByRole("region", { name: "Guide" });
		within(guide).getByRole("heading", { level: 4, name: "Guide" });
		within(guide).getByRole("heading", { level: 5, name: "How to do it" });
		await userEvent.click(canvas.getByRole("button", { name: "How it decides" }));
		canvas.getByRole("heading", { level: 4, name: "How it decides" });
		await expect(
			await canvas.findByRole("heading", { name: "The standard", level: 5 }),
		).toBeVisible();
	},
};
