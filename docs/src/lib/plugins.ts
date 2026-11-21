import { defineMdastPlugin, type Features } from "satteri";

/** The Markdown syntax that the site parses, for the Astro processor and for the address index without a build. */
export const MARKDOWN_FEATURES = { directive: true, smartPunctuation: false } satisfies Features;

/* The STE label of each callout (writing standard § Warnings, cautions, and notes), unless the author wrote a title. */
const CALLOUT_LABELS: Record<string, string> = { note: "Note", tip: "Tip", caution: "CAUTION" };

/**
 * `:::note`, `:::tip` and `:::caution` become callouts with their label. Any other container directive is a typo,
 * and the build stops.
 */
export const callouts = defineMdastPlugin({
	name: "hephaestus-callouts",
	containerDirective(node, ctx) {
		const label = CALLOUT_LABELS[node.name];
		if (label === undefined) {
			throw new Error(
				`${ctx.fileURL?.pathname ?? "a page"}: line ${node.position?.start.line ?? 0} opens ":::${node.name}". Use :::note, :::tip or :::caution.`,
			);
		}
		// Sätteri reads `:::caution Old title`, the MDX 1 title form, as a callout and drops the title without a word.
		const opening = ctx.source.slice(node.position?.start.offset ?? 0).split("\n", 1)[0] ?? "";
		if (!/^:::[\w-]+(?:\[[^\]]*\])?(?:\{[^}]*\})?\s*$/u.test(opening)) {
			throw new Error(
				`${ctx.fileURL?.pathname ?? "a page"}: line ${node.position?.start.line ?? 0} has text after ":::${node.name}". Write the title in brackets: :::${node.name}[Title].`,
			);
		}
		const title = node.children.flatMap((child) =>
			child.type === "paragraph" && child.data?.directiveLabel === true ? child.children : [],
		);
		const body = node.children.filter(
			(child) => !(child.type === "paragraph" && child.data?.directiveLabel === true),
		);
		ctx.replaceNode(node, {
			type: "mdxJsxFlowElement",
			name: "CalloutContainer",
			attributes: [{ type: "mdxJsxAttribute", name: "type", value: node.name }],
			children: [
				{
					type: "mdxJsxFlowElement",
					name: "CalloutTitle",
					attributes: [],
					children: title.length > 0 ? title : [{ type: "text", value: label }],
				},
				{ type: "mdxJsxFlowElement", name: "CalloutDescription", attributes: [], children: body },
			],
		});
	},
});

/*
 * MDX 3 reads an admonition title only in brackets (`:::caution[Title]`). The MDX 1 form (`:::caution Title`) does not
 * fail: it renders as a paragraph that starts with `:::`.
 */
export const rejectUnparsedAdmonitions = defineMdastPlugin({
	name: "hephaestus-reject-unparsed-admonitions",
	paragraph(node, ctx) {
		const unparsed = node.children.some(
			(child, index) =>
				child.type === "text" && (index === 0 ? /(?:^|\n):::/u : /\n:::/u).test(child.value),
		);
		if (unparsed) {
			throw new Error(
				`${ctx.fileURL?.pathname ?? "a page"}: the paragraph at line ${node.position?.start.line ?? 0} has an admonition that MDX did not parse. Write the title in brackets: :::caution[Title].`,
			);
		}
	},
});

/*
 * The directive syntax reads every `:word` in prose, such as `role:admin`, as a directive. A directive that no plugin
 * handles renders nothing, so its text would vanish without an error. Put it back as the text the author wrote.
 * `::include` is the include syntax, which runs before this plugin.
 */
export const restoreUnusedDirectives = defineMdastPlugin({
	name: "hephaestus-restore-unused-directives",
	textDirective(node, ctx) {
		ctx.replaceNode(node, {
			type: "text",
			value: original(ctx.source, node.position, `:${node.name}`, ctx.textContent(node)),
		});
	},
	leafDirective(node, ctx) {
		if (node.name === "include") {
			return;
		}
		ctx.replaceNode(node, {
			type: "paragraph",
			children: [
				{
					type: "text",
					value: original(ctx.source, node.position, `::${node.name}`, ctx.textContent(node)),
				},
			],
		});
	},
});

/** The source of a directive. An included file's nodes carry positions in that file, so the slice is checked. */
function original(
	source: string,
	position: { start: { offset?: number }; end: { offset?: number } } | undefined,
	marker: string,
	label: string,
): string {
	const start = position?.start.offset;
	const end = position?.end.offset;
	const slice = start === undefined || end === undefined ? "" : source.slice(start, end);
	if (slice.startsWith(marker)) {
		return slice;
	}
	return label === "" ? marker : `${marker}[${label}]`;
}
