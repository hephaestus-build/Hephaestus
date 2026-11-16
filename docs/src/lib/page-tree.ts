import type { LoaderPlugin } from "fumadocs-core/source";

/**
 * A sidebar link in a `meta.json` file, such as `[Accessibility](/user/accessibility)`, keeps its URL as written, and
 * Fumadocs adds no base path to it. This adds the base path (`/next/` for the unreleased docs) to a root-relative link.
 */
export function linksWithBasePath(base: string): LoaderPlugin {
	return {
		name: "hephaestus:links-with-base-path",
		transformPageTree: {
			file(node) {
				return node.url.startsWith("/") && !node.url.startsWith(base)
					? { ...node, url: `${base}${node.url.slice(1)}` }
					: node;
			},
		},
	};
}
