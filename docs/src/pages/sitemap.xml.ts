import type { APIRoute } from "astro";

import { BASE, SITE_URL } from "@/lib/site";
import { source } from "@/lib/source";

const escape = (value: string): string => value.replaceAll("&", "&amp;").replaceAll("<", "&lt;");

// The HTML pages of the docs, with their last change. A Markdown twin is for assistants, which find it through `llms.txt`.
export const GET: APIRoute = () => {
	const urls = [
		{ loc: `${SITE_URL}${BASE}`, lastmod: undefined },
		...source.getPages().map((page) => ({
			loc: `${SITE_URL}${page.url}`,
			lastmod:
				page.data.lastModified instanceof Date ? page.data.lastModified.toISOString() : undefined,
		})),
	];
	const body = urls
		.map(
			({ loc, lastmod }) =>
				`<url><loc>${escape(loc)}</loc>${lastmod === undefined ? "" : `<lastmod>${lastmod}</lastmod>`}</url>`,
		)
		.join("");
	return new Response(
		`<?xml version="1.0" encoding="UTF-8"?><urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">${body}</urlset>`,
		{
			headers: { "Content-Type": "application/xml" },
		},
	);
};
