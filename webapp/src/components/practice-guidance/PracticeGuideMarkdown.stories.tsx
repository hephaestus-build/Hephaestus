import type { Meta, StoryObj } from "@storybook/react-vite";

import { bundledGuidance } from "@/stories/practice-guidance-story-mock-data";

import { PracticeGuideMarkdown } from "./PracticeGuideMarkdown";

/**
 * A practice's "Read more" guide. Its headings sit under the level's own, so each renders as an
 * `h4`; its one image form is the guide's own figure, drawn as a themed picture.
 */
const meta = {
	component: PracticeGuideMarkdown,
	parameters: { layout: "padded" },
	args: { guide: bundledGuidance.guide },
	argTypes: {
		// Markdown and its figures as one record: a text box per figure would let them disagree.
		guide: { control: false },
	},
	tags: ["autodocs"],
} satisfies Meta<typeof PracticeGuideMarkdown>;

export default meta;
type Story = StoryObj<typeof meta>;

/** The bundled guide, with its figure drawn where the Markdown names it. */
export const Default: Story = {};

export const Dark: Story = { globals: { theme: "dark" } };
