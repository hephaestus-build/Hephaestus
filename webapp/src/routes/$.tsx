import { createFileRoute } from "@tanstack/react-router";

import { PageError } from "@/components/common/PageError";
import { pageHead } from "@/lib/page-title";

// Any address no other route matches. A route, rather than the root's `notFoundComponent`, because
// only a route can set the page title.
export const Route = createFileRoute("/$")({
	head: pageHead("Page not found"),
	component: NotFoundRoute,
});

function NotFoundRoute() {
	return <PageError cause="not-found" />;
}
