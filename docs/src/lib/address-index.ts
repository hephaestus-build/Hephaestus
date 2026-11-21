import { readdirSync, readFileSync } from "node:fs";
import path from "node:path";
import { pathToFileURL } from "node:url";

import { remarkHeading } from "@fumadocs/satteri/remark-heading";
import { remarkInclude } from "@fumadocs/satteri/remark-include";
import { mdxToMdast, type Data, type MdastNode } from "satteri";

import { MARKDOWN_FEATURES } from "./plugins.ts";
import { RELEASED_REDIRECTS } from "./released-redirects.ts";

/** What a path of the site serves: a page with the `id` of each element that an anchor can reach, or a redirect. */
export type SitePage = { ids: ReadonlySet<string> } | { redirect: string };

// The sources that become pages; `astro.config.ts` copies every other file of a guide next to its pages.
const PAGE_SOURCES = new Set([".md", ".mdx", ".mmd", ".json"]);

/** The files below `directory`, without a name that starts with `_`, which marks a partial. */
function filesOf(directory: string): string[] {
	return readdirSync(directory, { withFileTypes: true })
		.filter((entry) => !entry.name.startsWith("_"))
		.flatMap((entry) => {
			const full = path.join(directory, entry.name);
			return entry.isDirectory() ? filesOf(full) : [full];
		});
}

/** The `id` attributes of the MDX elements in a tree, such as `<a id="…">`, which headings do not give. */
function elementIds(node: MdastNode): string[] {
	const own =
		(node.type === "mdxJsxFlowElement" || node.type === "mdxJsxTextElement") && "attributes" in node
			? node.attributes.flatMap((attribute) =>
					attribute.type === "mdxJsxAttribute" &&
					attribute.name === "id" &&
					typeof attribute.value === "string"
						? [attribute.value]
						: [],
				)
			: [];
	const children = "children" in node && Array.isArray(node.children) ? node.children : [];
	return [...own, ...children.flatMap((child: MdastNode) => elementIds(child))];
}

/**
 * The anchors of one guide page. The site's own include and heading plugins give each heading the id that the build
 * gives it, partials included.
 */
async function pageIds(docs: string, file: string): Promise<Set<string>> {
	const data: Data = {};
	const tree = await mdxToMdast(readFileSync(file, "utf8"), {
		features: { ...MARKDOWN_FEATURES, frontmatter: true },
		mdastPlugins: [remarkInclude({ cwd: docs }), remarkHeading()],
		fileURL: pathToFileURL(file),
		data,
	});
	// remarkHeading lists each heading in `toc` with its URL, `#` and the id.
	const toc: unknown[] = Array.isArray(data.toc) ? data.toc : [];
	const headings = toc.flatMap((entry) =>
		typeof entry === "object" && entry !== null && "url" in entry && typeof entry.url === "string"
			? [entry.url.slice(1)]
			: [],
	);
	return new Set([...headings, ...elementIds(tree)]);
}

/**
 * Every path that the docs site serves, without a leading slash, read from the source without a build: the guide pages
 * of `meta.json` with their Markdown twins and linked files, the pages of `src/pages`, the files of `public/`, and the
 * released redirects. `scripts/check-docs-addresses.ts --source` checks the docs addresses in code against it; the docs
 * build checks them against the built site.
 */
export async function sourcePages(docs: string): Promise<Map<string, SitePage>> {
	const pages = new Map<string, SitePage>();
	const none: ReadonlySet<string> = new Set();
	const meta: unknown = JSON.parse(readFileSync(path.join(docs, "meta.json"), "utf8"));
	const guides =
		typeof meta === "object" && meta !== null && "pages" in meta && Array.isArray(meta.pages)
			? meta.pages.map(String)
			: [];
	for (const guide of guides) {
		for (const file of filesOf(path.join(docs, guide))) {
			const relative = path.relative(docs, file).split(path.sep).join("/");
			const extension = path.extname(file);
			// The Fumadocs page loader reads `.mdx` only.
			if (extension === ".mdx") {
				const route = relative.slice(0, -".mdx".length).replace(/(?:^|\/)index$/u, "");
				pages.set(route, { ids: await pageIds(docs, file) });
				pages.set(`${route}.md`, { ids: none });
			} else if (!PAGE_SOURCES.has(extension)) {
				pages.set(relative, { ids: none });
			}
		}
	}
	for (const file of readdirSync(path.join(docs, "src", "pages"))) {
		if (!file.startsWith("[")) {
			pages.set(file.replace(/\.(?:astro|ts)$/u, "").replace(/^index$/u, ""), { ids: none });
		}
	}
	for (const file of filesOf(path.join(docs, "public"))) {
		pages.set(path.relative(path.join(docs, "public"), file).split(path.sep).join("/"), {
			ids: none,
		});
	}
	for (const [from, to] of Object.entries(RELEASED_REDIRECTS)) {
		pages.set(from.replace(/^\//u, ""), { redirect: to.replace(/^\//u, "") });
	}
	return pages;
}
