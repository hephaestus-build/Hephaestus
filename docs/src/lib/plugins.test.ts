// The docs site's Markdown plugins on the compiler that builds the site (Sätteri, ADR 0111). An upstream compiler
// change that alters what a plugin receives fails here, before it changes a page.
import assert from "node:assert/strict";
import { test } from "node:test";

import { remarkMdxMermaid } from "@fumadocs/satteri/remark-mdx-mermaid";
import { createElement, type ElementType, type ReactNode } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { Fragment, jsx, jsxs } from "react/jsx-runtime";
import { evaluate } from "satteri";

import { callouts, rejectUnparsedAdmonitions, restoreUnusedDirectives } from "./plugins.ts";

interface Props {
	children?: ReactNode;
	type?: string;
	chart?: string;
}
const components = {
	CalloutContainer: ({ type, children }: Props) =>
		createElement("aside", { "data-callout": type }, children),
	CalloutTitle: ({ children }: Props) => createElement("strong", null, children),
	CalloutDescription: ({ children }: Props) => createElement("div", null, children),
	Mermaid: ({ chart }: Props) => createElement("pre", { "data-mermaid": "" }, chart),
};

const isRecord = (value: unknown): value is Record<string, unknown> =>
	typeof value === "object" && value !== null;
// React's Fragment is a symbol at run time.
const isElementType = (value: unknown): value is ElementType =>
	typeof value === "string" ||
	typeof value === "function" ||
	typeof value === "symbol" ||
	isRecord(value);
const isContent = (
	value: unknown,
): value is (props: { components: typeof components }) => ReactNode => typeof value === "function";

/** React's JSX runtime, with the argument types that the compiler's output passes. */
function element(create: typeof jsx) {
	return (type: unknown, props: unknown, key?: unknown) => {
		if (!isElementType(type)) {
			throw new Error(`not an element type: ${String(type)}`);
		}
		return create(type, isRecord(props) ? props : {}, typeof key === "string" ? key : undefined);
	};
}

async function render(source: string): Promise<string> {
	const exports = await evaluate(source, {
		Fragment,
		jsx: element(jsx),
		jsxs: element(jsxs),
		features: { directive: true },
		mdastPlugins: [
			rejectUnparsedAdmonitions,
			callouts,
			restoreUnusedDirectives,
			remarkMdxMermaid(),
		],
	});
	const Content = exports.default;
	if (!isContent(Content)) {
		throw new Error("the compiled page has no component");
	}
	return renderToStaticMarkup(createElement(Content, { components }));
}

void test("each callout gets its STE label unless the author wrote a title", async () => {
	const html = await render(
		":::caution\nKeep a backup.\n:::\n\n:::note[Before you start]\nRead this.\n:::\n\n:::tip\nShorter.\n:::\n",
	);
	assert.match(
		html,
		/<aside data-callout="caution"><strong>CAUTION<\/strong><div><p>Keep a backup\.<\/p><\/div><\/aside>/u,
	);
	assert.match(html, /<aside data-callout="note"><strong>Before you start<\/strong>/u);
	assert.match(html, /<aside data-callout="tip"><strong>Tip<\/strong>/u);
});

void test("a container directive that is not a callout stops the build", async () => {
	await assert.rejects(
		render(":::details\nHidden.\n:::\n"),
		/opens ":::details"\. Use :::note, :::tip or :::caution\./u,
	);
});

void test("the MDX 1 callout title stops the build instead of disappearing", async () => {
	await assert.rejects(
		render(":::caution Old title\nBody.\n:::\n"),
		/Write the title in brackets: :::caution\[Title\]\./u,
	);
});

void test("a callout opening that the parser leaves as text stops the build", async () => {
	await assert.rejects(render("Text\n\n:::\n"), /admonition that MDX did not parse/u);
});

void test("text that reads as a directive stays the text the author wrote", async () => {
	const html = await render("Give the role:admin scope, and keep a:b[c] and ::leaf as written.\n");
	assert.match(html, /Give the role:admin scope, and keep a:b\[c\] and ::leaf as written\./u);
});

void test("a mermaid fence becomes the Mermaid component with its source", async () => {
	const html = await render("```mermaid\nflowchart LR\n  A --> B\n```\n");
	assert.match(html, /<pre data-mermaid="">flowchart LR\n {2}A --&gt; B<\/pre>/u);
});
