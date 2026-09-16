import type { Meta, StoryObj } from "@storybook/react-vite";

import { GithubIcon, GitlabIcon, OutlineIcon, SlackIcon } from "@/components/icons/brand";

const ICONS = [
	{ label: "GitHub", Icon: GithubIcon },
	{ label: "GitLab", Icon: GitlabIcon },
	{ label: "Slack", Icon: SlackIcon },
	{ label: "Outline", Icon: OutlineIcon },
];

const meta = {
	tags: ["autodocs"],
} satisfies Meta;

export default meta;

/**
 * Every provider Hephaestus connects to is represented by its own mark, drawn from the vendor's
 * artwork and tinted with the current text colour. A provider rendered with a stand-in glyph reads
 * as a second-class integration, so the set is kept complete.
 */
export const AllMarks: StoryObj = {
	render: () => (
		<div className="flex flex-wrap gap-6">
			{ICONS.map(({ label, Icon }) => (
				<div key={label} className="flex w-24 flex-col items-center gap-2">
					<Icon className="size-8" />
					<span className="text-xs text-muted-foreground">{label}</span>
				</div>
			))}
		</div>
	),
};

/** The sizes the marks actually ship at: inline in a heading, in an Item media slot, in a button. */
export const Sizes: StoryObj = {
	render: () => (
		<div className="flex items-end gap-6">
			{["size-3.5", "size-4", "size-5", "size-8"].map((size) => (
				<div key={size} className="flex flex-col items-center gap-2">
					<OutlineIcon className={size} />
					<span className="text-xs text-muted-foreground">{size}</span>
				</div>
			))}
		</div>
	),
};
