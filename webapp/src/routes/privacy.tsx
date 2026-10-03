import { createFileRoute } from "@tanstack/react-router";

import { LegalPage } from "@/components/site/LegalPage";
import { LEGAL_PAGE_TITLES } from "@/lib/legal";
import { pageHead } from "@/lib/page-title";

export const Route = createFileRoute("/privacy")({
	head: pageHead(LEGAL_PAGE_TITLES.privacy),
	component: PrivacyContainer,
});

function PrivacyContainer() {
	return <LegalPage page="privacy" title={LEGAL_PAGE_TITLES.privacy} />;
}
