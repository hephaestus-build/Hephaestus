import { useQuery } from "@tanstack/react-query";
import { createFileRoute } from "@tanstack/react-router";
import { useEffect } from "react";

import { getPublicUsageOptions } from "@/api/@tanstack/react-query.gen";
import {
	PublicUsagePage,
	type PublicUsagePageState,
} from "@/components/public-usage/PublicUsagePage";
import { setPublicUsagePublished } from "@/hooks/use-public-usage-published";
import { pageHead } from "@/lib/page-title";
import { problemStatusOf } from "@/lib/problem-detail";
import { useSearchState } from "@/lib/search-params";

export const Route = createFileRoute("/usage")({
	head: pageHead("Hephaestus in numbers"),
	validateSearch: (search): { period?: string } => ({
		period:
			typeof search.period === "string" && search.period.length > 0 ? search.period : undefined,
	}),
	component: PublicUsageRoute,
});

function PublicUsageRoute() {
	const { period } = Route.useSearch();
	const setSearch = useSearchState();
	// Every visit asks the server again, which checks the switch and the withdrawals; the HTTP cache keeps the
	// five minutes. A failed read is not retried: a 404 means the page is off, and the reader can retry others.
	// An open page keeps what it shows (ADR 0153): no read on focus or reconnect, which could also spend the shared
	// rate limit and replace the figures with a 429.
	const usage = useQuery({
		...getPublicUsageOptions(),
		retry: false,
		refetchOnMount: "always",
		refetchOnWindowFocus: false,
		refetchOnReconnect: false,
	});
	// Only a read made for this visit decides what shows, so neither figures nor a 404 from an earlier visit show
	// while it runs: a withdrawal, a switch-off and a switch-on all show at once.
	const visited = usage.isFetchedAfterMount;
	let state: PublicUsagePageState = { status: "loading" };
	if (visited && usage.isError) {
		// A failed read never falls back to figures read before it: a 404 means the page is off now, and any
		// other failure cannot show what the server holds today.
		state =
			problemStatusOf(usage.error) === 404
				? { status: "unavailable" }
				: {
						status: "error",
						error: usage.error,
						onRetry: () => {
							void usage.refetch();
						},
					};
	} else if (visited && usage.data !== undefined) {
		state = {
			status: "ready",
			periods: usage.data.periods,
			selected: period ?? usage.data.periods[0]?.period ?? "",
			onSelectPeriod: (next) => {
				void setSearch((previous) => ({ ...previous, period: next }), { replace: true });
			},
		};
	}
	// This visit's answer is newer than the page load: the footer link follows it.
	const { status } = state;
	useEffect(() => {
		if (status === "ready" || status === "unavailable") {
			setPublicUsagePublished(status === "ready");
		}
	}, [status]);
	return <PublicUsagePage state={state} />;
}
