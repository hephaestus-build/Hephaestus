import type { KnownArtifactKind } from "@/lib/artifact-kinds";

/**
 * URL-facing spelling of a kind: the wire id carries a dot, which reads badly in a path segment, so
 * the admin routes keep their own short slug and this map is where the two meet. Kept free of the
 * kind registry's icons so the browser extension links to the same routes from the same map.
 */
const ARTIFACT_KIND_SLUGS = {
	"scm.pull_request": "pull-request",
	"scm.issue": "issue",
	"chat.conversation_thread": "conversation",
	"docs.document": "document",
} as const satisfies Record<KnownArtifactKind, string>;

export type ReviewArtifactTypeSlug = (typeof ARTIFACT_KIND_SLUGS)[KnownArtifactKind];

function isSluggedKind(kind: string): kind is KnownArtifactKind {
	return Object.hasOwn(ARTIFACT_KIND_SLUGS, kind);
}

export function reviewArtifactTypeSlug(kind: string): ReviewArtifactTypeSlug | undefined {
	return isSluggedKind(kind) ? ARTIFACT_KIND_SLUGS[kind] : undefined;
}

export function reviewArtifactTypeFromSlug(slug: string): KnownArtifactKind | undefined {
	return Object.keys(ARTIFACT_KIND_SLUGS)
		.filter(isSluggedKind)
		.find((kind) => ARTIFACT_KIND_SLUGS[kind] === slug);
}
