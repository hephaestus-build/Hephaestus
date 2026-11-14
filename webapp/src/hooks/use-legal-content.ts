import { useQuery } from "@tanstack/react-query";

import type { LegalContentState } from "@/components/site/LegalPage";
import type { LegalPageId } from "@/lib/legal";
import { resolveLegalContent } from "@/runtime/resolve-legal-content";

/** Once per page and session: a remount reads the cache and never repeats the warning. */
const warnedDisclaimer = new Set<LegalPageId>();

/** The page's markdown. It is static for the life of a deployment, so it loads once. */
export function useLegalContent(page: LegalPageId): LegalContentState {
	const query = useQuery({
		// oxlint-disable-next-line hephaestus/no-manual-query-key -- Static markdown from the SPA's own origin has no generated API operation.
		queryKey: ["legal-content", page],
		queryFn: async ({ signal }) => {
			const content = await resolveLegalContent(page, { signal });
			if (content.source === "disclaimer" && !warnedDisclaimer.has(page)) {
				warnedDisclaimer.add(page);
				// oxlint-disable-next-line no-console -- Remediation addressed to whoever deployed the instance, once per page; the reader's half of this is the disclaimer banner.
				console.warn(
					`[legal] Disclaimer fallback served for page=${page}. Mount /legal-overrides/. See docs/admin/legal-pages.`,
				);
			}
			return content;
		},
		staleTime: Infinity,
		retry: false,
	});
	if (query.isError) {
		return { status: "error" };
	}
	return query.isPending ? { status: "loading" } : { status: "ready", content: query.data };
}
