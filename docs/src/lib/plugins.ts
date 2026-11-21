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
	// It reads `node.position`, which Sätteri fills only for a plugin that asks for it.
	options: { position: true },
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
	// It reads `node.position`, which Sätteri fills only for a plugin that asks for it.
	options: { position: true },
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

// GitLab's alt text limit; a longer description belongs in the text around the image.
const MAX_ALT = 155;

/**
 * Each `<SystemDiagram>` renders a committed source, states its view and scope, describes the image in a literal `alt`,
 * and holds a table of every connection, because the SVG draws its text as paths. MDX is not type-checked, so the
 * component's required props hold only here. `hasSource` says whether `docs/diagrams/<name>.hyl` exists.
 */
export function systemDiagrams(hasSource: (name: string) => boolean) {
	return defineMdastPlugin({
		name: "hephaestus-system-diagrams",
		// It reads `node.position`, which Sätteri fills only for a plugin that asks for it.
		options: { position: true },
		mdxJsxFlowElement(node, ctx) {
			if (node.name !== "SystemDiagram") {
				return;
			}
			const attribute = (name: string) => {
				const value = node.attributes.find(
					(entry) => entry.type === "mdxJsxAttribute" && entry.name === name,
				)?.value;
				return typeof value === "string" ? value : undefined;
			};
			const problems: string[] = [];
			// The component puts the name into the image addresses as it is, so it is not trimmed.
			const name = attribute("name");
			if (name === undefined || name === "") {
				problems.push('Give it a literal name, such as name="containers".');
			} else if (/\s/u.test(name)) {
				problems.push(`Remove the spaces from name="${name}": it is part of the image addresses.`);
			} else if (!hasSource(name)) {
				problems.push(`docs/diagrams/${name}.hyl does not exist. Name the source of the view.`);
			}
			if (!/^[^:]+: \S/u.test(attribute("title")?.trim() ?? "")) {
				problems.push(
					'Give it a literal title "<view>: <scope>", such as "Container view: Hephaestus".',
				);
			}
			const alt = attribute("alt")?.trim() ?? "";
			if (alt === "") {
				problems.push("Give it a literal alt text that says what the diagram shows.");
			} else if (alt.length > MAX_ALT) {
				problems.push(`Shorten the alt text to ${MAX_ALT} characters (${alt.length}).`);
			}
			if (!node.children.some((child) => child.type === "table")) {
				problems.push(
					"Put a table of every connection of the view between <SystemDiagram> and </SystemDiagram>.",
				);
			}
			if (problems.length > 0) {
				throw new Error(
					`${ctx.fileURL?.pathname ?? "a page"}: the <SystemDiagram> at line ${node.position?.start.line ?? 0}: ${problems.join(" ")}`,
				);
			}
		},
	});
}
