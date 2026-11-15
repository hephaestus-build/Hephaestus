import type { APIRoute, GetStaticPaths, InferGetStaticPropsType } from "astro";

import { docsLlms } from "@/lib/llms";
import { source } from "@/lib/source";

export const getStaticPaths = (() =>
	source.getPages().map((page) => ({
		params: { slug: page.slugs.join("/") },
		props: { page },
	}))) satisfies GetStaticPaths;

export const GET: APIRoute<InferGetStaticPropsType<typeof getStaticPaths>> = async ({
	props: { page },
}) =>
	new Response(await docsLlms.page(page), {
		headers: { "Content-Type": "text/markdown; charset=utf-8" },
	});
