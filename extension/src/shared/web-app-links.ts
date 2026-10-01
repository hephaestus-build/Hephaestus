import { reviewArtifactTypeSlug } from "@/lib/artifact-kind-slugs";

/**
 * Addresses in the Hephaestus web app the extension links to. Every link is navigation only: the
 * page it opens has its own session, consent and confirmations, and opening it changes nothing.
 * Route shapes: `webapp/src/routes/_authenticated/w/$workspaceSlug/**`.
 */
export function workspaceBase(webAppOrigin: string, workspaceSlug: string): string {
	return `${webAppOrigin}/w/${encodeURIComponent(workspaceSlug)}`;
}

/** The reader's feedback page, and the admin's existing work drawer when this kind has a route. */
export function workLinks(
	webAppOrigin: string,
	workspaceSlug: string,
	work: { kind: string; id: string },
	admin: boolean,
): { trace: string; reviewDetails?: string } {
	const base = workspaceBase(webAppOrigin, workspaceSlug);
	const trace = `${base}/feedback/${encodeURIComponent(work.kind)}/${encodeURIComponent(work.id)}`;
	const kindSlug = reviewArtifactTypeSlug(work.kind);
	return admin && kindSlug !== undefined
		? {
				trace,
				reviewDetails: `${base}/admin/practices/reviews/work?${new URLSearchParams({ detail: `work:${kindSlug}:${work.id}` })}`,
			}
		: { trace };
}
