import type { APIRoute } from "astro";

import { docsLlms, llmsHeader } from "@/lib/llms";

export const GET: APIRoute = async () =>
	new Response(`${llmsHeader}\n${await docsLlms.full()}`, {
		headers: { "Content-Type": "text/plain; charset=utf-8" },
	});
