import { apiBasePath } from "@/runtime/api-base-path";

/** A request's path relative to the API, as the generated client and the OpenAPI description name it. */
export function apiPathOf(url: string | URL): string {
	const { pathname } = new URL(url);
	const base = apiBasePath();
	return pathname.startsWith(`${base}/`) ? pathname.slice(base.length) : pathname;
}
