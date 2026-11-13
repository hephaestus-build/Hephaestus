import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, spyOn, userEvent } from "storybook/test";

import {
	CodeBlock,
	CodeBlockActions,
	CodeBlockContent,
	CodeBlockCopyButton,
	CodeBlockFooter,
	CodeBlockHeader,
	CodeBlockLine,
	CodeBlockTitle,
} from "@/components/ui/code-block";
import { useHighlightedCode } from "@/hooks/use-highlighted-code";
import { expectNoPageOverflow } from "@/stories/reflow";

const source = [
	"export function pageSize(requested: number | undefined): number {",
	"  // Never more than one screen of rows.",
	"  return Math.min(requested ?? 20, 50);",
	"}",
].join("\n");

const failure = [
	"HttpClientErrorException$Forbidden: 403 Forbidden on POST request for",
	'"https://api.github.com/repos/acme/web/pulls/42/reviews": "Resource not accessible by integration"',
	"\tat org.springframework.web.client.HttpClientErrorException.create(HttpClientErrorException.java:109)",
].join("\n");

/** What the copy button puts on the clipboard, read back without a clipboard permission. */
const writeText = fn(async (_text: string) => {
	await Promise.resolve();
});

/**
 * The shadcn code block, as installed. Code and tool output are set in it wherever the app shows
 * them: evidence quotes, practice scripts, stored JSON and provider errors.
 *
 * Highlighting is the `useHighlightedCode` hook's, not the block's. The hook returns plain lines at
 * once and coloured lines when Shiki has loaded, so a block never waits empty for its words.
 */
const meta = {
	component: CodeBlock,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	args: { size: "default", variant: "default" },
	argTypes: {
		size: { control: "inline-radio", options: ["default", "sm"] },
		variant: { control: "inline-radio", options: ["default", "destructive"] },
	},
	beforeEach: () => {
		const clipboard = spyOn(navigator.clipboard, "writeText").mockImplementation(writeText);
		return () => clipboard.mockRestore();
	},
	render: (args) => (
		<CodeBlock {...args}>
			<CodeBlockHeader>
				<CodeBlockTitle>src/page-size.ts</CodeBlockTitle>
				<CodeBlockActions>
					<CodeBlockCopyButton value={source} />
				</CodeBlockActions>
			</CodeBlockHeader>
			<CodeBlockContent lineNumbers>{source}</CodeBlockContent>
		</CodeBlock>
	),
} satisfies Meta<typeof CodeBlock>;

export default meta;
type Story = StoryObj<typeof meta>;

/** The copy button names its action, and says when the copy has landed. */
export const Default: Story = {
	play: async ({ canvas }) => {
		await expect(canvas.getByText("src/page-size.ts")).toBeVisible();
		await userEvent.click(canvas.getByRole("button", { name: "Copy" }));
		await expect(writeText).toHaveBeenLastCalledWith(source);
		await expect(await canvas.findByRole("button", { name: "Copied" })).toBeVisible();
	},
};

function HighlightedSource({ startLine }: { startLine: number }) {
	const lines = useHighlightedCode(source, "ts");
	return (
		<CodeBlock>
			<CodeBlockContent lineNumbers startLine={startLine}>
				{lines.map((line, index) => (
					<CodeBlockLine key={index}>{line}</CodeBlockLine>
				))}
			</CodeBlockContent>
		</CodeBlock>
	);
}

/** A passage from the middle of a file is numbered from the line it starts on. */
export const StartLine: Story = {
	render: () => <HighlightedSource startLine={60} />,
	play: async () => {
		// The code is a tab stop of its own, so a keyboard can scroll it.
		await userEvent.tab();
		await expect(document.activeElement).toHaveTextContent("return Math.min(requested ?? 20, 50);");
	},
};

const change = [
	{ variant: "default", text: "export function pageSize(requested: number | undefined): number {" },
	{ variant: "removed", text: "  return requested ?? 25;" },
	{ variant: "added", text: "  return Math.min(requested ?? 20, 50);" },
	{ variant: "default", text: "}" },
] as const;

/** A removed line and an added one carry a sign as well as a wash. */
export const Diff: Story = {
	render: (args) => (
		<CodeBlock {...args}>
			<CodeBlockHeader>
				<CodeBlockTitle>src/page-size.ts</CodeBlockTitle>
			</CodeBlockHeader>
			<CodeBlockContent>
				{change.map(({ variant, text }) => (
					<CodeBlockLine key={text} variant={variant}>
						{text}
					</CodeBlockLine>
				))}
			</CodeBlockContent>
		</CodeBlock>
	),
	play: async ({ canvasElement }) => {
		const lines = [...canvasElement.querySelectorAll<HTMLElement>("[data-slot=code-block-line]")];
		await expect(lines.map((line) => line.dataset.variant)).toEqual([
			"default",
			"removed",
			"added",
			"default",
		]);
	},
};

/** Error output: the destructive variant, small, with long lines wrapped. */
export const ErrorOutput: Story = {
	args: { size: "sm", variant: "destructive" },
	render: (args) => (
		<CodeBlock {...args}>
			<CodeBlockHeader>
				<CodeBlockTitle>Last error</CodeBlockTitle>
				<CodeBlockActions>
					<CodeBlockCopyButton value={failure} aria-label="Copy last error" />
				</CodeBlockActions>
			</CodeBlockHeader>
			<CodeBlockContent wrap>{failure}</CodeBlockContent>
		</CodeBlock>
	),
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Last error")).toBeVisible();
		await expect(canvas.getByRole("button", { name: "Copy last error" })).toBeEnabled();
	},
};

/** At 320px a wrapped block breaks its long lines, and a scrolling one scrolls inside itself. */
export const LongLinesAtReflow: Story = {
	args: { size: "sm" },
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	render: (args) => (
		<div className="flex flex-col gap-3">
			<CodeBlock {...args}>
				<CodeBlockContent wrap>{failure}</CodeBlockContent>
			</CodeBlock>
			<CodeBlock {...args}>
				<CodeBlockContent>{failure}</CodeBlockContent>
			</CodeBlock>
		</div>
	),
	play: async () => {
		await expectNoPageOverflow();
	},
};

/**
 * A block that names a place and quotes nothing is a header alone, or a header and a footer. Each
 * pads its open edge, which upstream does not (see the divergence note in `code-block.tsx`).
 */
export const HeaderAndFooterOnly: Story = {
	args: { size: "sm" },
	render: (args) => (
		<div className="flex flex-col gap-3">
			<CodeBlock {...args}>
				<CodeBlockHeader>
					<CodeBlockTitle>docs/contributor/practice-catalogue.md</CodeBlockTitle>
					<span>lines 31 to 44</span>
				</CodeBlockHeader>
			</CodeBlock>
			<CodeBlock {...args}>
				<CodeBlockHeader>
					<CodeBlockTitle>src/service.ts</CodeBlockTitle>
				</CodeBlockHeader>
				<CodeBlockFooter>Not quoted. The passage was withheld.</CodeBlockFooter>
			</CodeBlock>
		</div>
	),
	play: async ({ canvas }) => {
		await expect(canvas.getByText("lines 31 to 44")).toBeVisible();
		await expect(canvas.getByText("Not quoted. The passage was withheld.")).toBeVisible();
	},
};

/** What a tool printed when it printed nothing. */
const nothing = "";

/** An empty block keeps the height of one line. */
export const Empty: Story = {
	render: (args) => (
		<CodeBlock {...args}>
			<CodeBlockContent>{nothing}</CodeBlockContent>
		</CodeBlock>
	),
	play: async ({ canvasElement }) => {
		await expect(canvasElement.textContent).toBe("");
	},
};
