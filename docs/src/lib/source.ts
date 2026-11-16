import { toFumadocsSource } from "@fumadocs/astro/source";
import { getCollection } from "astro:content";
import { loader } from "fumadocs-core/source";

import { linksWithBasePath } from "@/lib/page-tree";
import { BASE } from "@/lib/site";

/** A file or folder whose name starts with `_` is a partial that a page includes, not a page. */
const isPage = (entry: { id: string }): boolean =>
	!entry.id.split("/").some((segment) => segment.startsWith("_"));

// The three guides are the whole site (docs/README.md). Each guide is a collection, placed in its folder of the site.
// Every URL carries the base path (`/next/` for the unreleased docs): Astro's client router adds none.
export const source = loader({
	baseUrl: BASE,
	plugins: [linksWithBasePath(BASE)],
	source: {
		user: toFumadocsSource(await getCollection("user", isPage), await getCollection("user-meta"), {
			baseDir: "user",
		}),
		admin: toFumadocsSource(
			await getCollection("admin", isPage),
			await getCollection("admin-meta"),
			{ baseDir: "admin" },
		),
		contributor: toFumadocsSource(
			await getCollection("contributor", isPage),
			await getCollection("contributor-meta"),
			{
				baseDir: "contributor",
			},
		),
	},
});

export type DocsPage = NonNullable<ReturnType<typeof source.getPage>>;

/** The Markdown twin of a page: `/admin/upgrade` → `/admin/upgrade.md`. */
export const markdownUrl = (url: string): string => `${url}.md`;
