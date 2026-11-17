import { metaLoader, pageLoader } from "@fumadocs/astro/loaders";
import { z } from "astro/zod";
import { defineCollection } from "astro:content";
import { pageSchema } from "fumadocs-core/source/schema";

/*
 * `pageLoader()` loads every `.mdx` file of a guide, also a partial (`_configuration-reference/`, `erd/_modules.mdx`)
 * that a page includes and that has no title. The schema lets a partial load, and `src/lib/source.ts` leaves partials
 * out of the site. `gate:docs-pages` still requires a title on every page and checks the page type of each guide.
 * The schema is strict: a front-matter key that the site does not read fails the build. The file name is the URL,
 * and `meta.json` is the sidebar.
 */
const schema = pageSchema
	.partial({ title: true })
	.extend({ page_type: z.string().optional() })
	.strict();

// One page and one meta collection for each guide (`GUIDES` in `src/lib/site.ts`).
export const collections = {
	user: defineCollection({ loader: pageLoader("user"), schema }),
	"user-meta": defineCollection({ loader: metaLoader("user") }),
	admin: defineCollection({ loader: pageLoader("admin"), schema }),
	"admin-meta": defineCollection({ loader: metaLoader("admin") }),
	contributor: defineCollection({ loader: pageLoader("contributor"), schema }),
	"contributor-meta": defineCollection({ loader: metaLoader("contributor") }),
};
