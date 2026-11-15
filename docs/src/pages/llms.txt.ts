import type { APIRoute } from "astro";

import { docsLlms, llmsHeader } from "@/lib/llms";

export const GET: APIRoute = () =>
	new Response(`${llmsHeader}\n${docsLlms.index()}`, {
		headers: { "Content-Type": "text/plain; charset=utf-8" },
	});
