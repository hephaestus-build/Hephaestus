import { createFileRoute } from "@tanstack/react-router";

import { LegalPage } from "@/components/site/LegalPage";
import { useLegalContent } from "@/hooks/use-legal-content";
import { LEGAL_PAGE_TITLES } from "@/lib/legal";
import { pageHead } from "@/lib/page-title";

export const Route = createFileRoute("/imprint")({
	head: pageHead(LEGAL_PAGE_TITLES.imprint),
	component: ImprintContainer,
});

function ImprintContainer() {
	const state = useLegalContent("imprint");
	return <LegalPage title={LEGAL_PAGE_TITLES.imprint} state={state} />;
}
