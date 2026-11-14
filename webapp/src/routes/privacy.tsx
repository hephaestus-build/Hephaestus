import { createFileRoute } from "@tanstack/react-router";

import { LegalPage } from "@/components/site/LegalPage";
import { useLegalContent } from "@/hooks/use-legal-content";
import { LEGAL_PAGE_TITLES } from "@/lib/legal";
import { pageHead } from "@/lib/page-title";

export const Route = createFileRoute("/privacy")({
	head: pageHead(LEGAL_PAGE_TITLES.privacy),
	component: PrivacyContainer,
});

function PrivacyContainer() {
	const state = useLegalContent("privacy");
	return <LegalPage title={LEGAL_PAGE_TITLES.privacy} state={state} />;
}
