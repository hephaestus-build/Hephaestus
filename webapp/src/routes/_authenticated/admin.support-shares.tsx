import { useQuery } from "@tanstack/react-query";
import { createFileRoute } from "@tanstack/react-router";
import { LifeBuoy } from "lucide-react";
import { z } from "zod";

import {
	listReceivedSupportSharesOptions,
	readSupportShareOptions,
} from "@/api/@tanstack/react-query.gen";
import {
	type ReadSupportShareState,
	type ReceivedSupportSharesState,
	ReceivedSupportSharesPage,
} from "@/components/admin/support-shares/ReceivedSupportSharesPage";
import { PageHeader } from "@/components/layout/PageHeader";
import { PageLayout } from "@/components/layout/PageLayout";
import { instanceAdminHead } from "@/lib/page-title";
import { useSearchState } from "@/lib/search-params";

export const Route = createFileRoute("/_authenticated/admin/support-shares")({
	head: instanceAdminHead("Support shares"),
	validateSearch: z.object({ shareId: z.uuid().optional().catch(undefined) }),
	component: SupportSharesRoute,
});

function SupportSharesRoute() {
	const { shareId } = Route.useSearch();
	const setSearch = useSearchState();
	const list = useQuery(listReceivedSupportSharesOptions());
	const read = useQuery({
		...readSupportShareOptions({
			path: { shareId: shareId ?? "00000000-0000-0000-0000-000000000000" },
		}),
		enabled: shareId !== undefined,
		// Each read counts, and the person sees the count: read once per opening, never in the background. Stale at
		// once, so each opening is a new read, which the server checks for revocation.
		staleTime: 0,
		gcTime: 0,
		refetchOnWindowFocus: false,
		refetchOnReconnect: false,
		retry: false,
	});
	let listState: ReceivedSupportSharesState;
	if (list.isPending) {
		listState = { status: "loading" };
	} else if (list.isError) {
		listState = {
			status: "error",
			error: list.error,
			onRetry: () => {
				void list.refetch();
			},
		};
	} else {
		listState = { status: "ready", shares: list.data };
	}
	let reading: ReadSupportShareState = { status: "closed" };
	if (shareId !== undefined) {
		// A copy that an earlier opening cached never shows: the page waits for this opening's read.
		if (read.isPending || !read.isFetchedAfterMount) {
			reading = { status: "loading" };
		} else if (read.isError) {
			reading = {
				status: "error",
				error: read.error,
				onRetry: () => {
					void read.refetch();
				},
			};
		} else {
			reading = { status: "ready", share: read.data };
		}
	}
	return (
		<PageLayout>
			<PageHeader
				icon={<LifeBuoy />}
				title="Support shares"
				description="Questions that people shared with you, while their shares run."
			/>
			<ReceivedSupportSharesPage
				list={listState}
				reading={reading}
				onRead={(id) => {
					void setSearch((previous) => ({ ...previous, shareId: id }));
				}}
				onClose={() => {
					void setSearch((previous) => ({ ...previous, shareId: undefined }));
				}}
			/>
		</PageLayout>
	);
}
