import { reviewArtifactTypeSlug } from "@/lib/artifact-kind-slugs";

/**
 * Addresses in the Hephaestus web app the extension links to. Every link is navigation only: the
 * page it opens has its own session, consent and confirmations, and opening it changes nothing.
 * Route shapes: `webapp/src/routes/_authenticated/w/$workspaceSlug/**`.
 */
export function workspaceBase(webAppOrigin: string, workspaceSlug: string): string {
	return `${webAppOrigin}/w/${encodeURIComponent(workspaceSlug)}`;
}

/**
 * Where the rest of one work's review is, in the workspace the report answered for: its review
 * activity (every reader's — occasions, practices, history), and for a workspace admin its reviewed
 * work output (every developer's observations, the feedback's delivery, the runs to cancel or retry).
 */
export function workLinks(
	webAppOrigin: string,
	workspaceSlug: string,
	work: { kind: string; id: string },
	admin: boolean,
): { trace: string; reviewDetails?: string } {
	const base = workspaceBase(webAppOrigin, workspaceSlug);
	const trace = `${base}/reviews/${encodeURIComponent(work.kind)}/${encodeURIComponent(work.id)}`;
	const kindSlug = reviewArtifactTypeSlug(work.kind);
	return admin && kindSlug !== undefined
		? {
				trace,
				reviewDetails: `${base}/admin/practices/reviews/targets/${kindSlug}/${encodeURIComponent(work.id)}`,
			}
		: { trace };
}
