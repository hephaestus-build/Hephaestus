import { useQuery } from "@tanstack/react-query";
import { createFileRoute, retainSearchParams } from "@tanstack/react-router";
import { MessageSquarePlus } from "lucide-react";

import { listMyProductFeedbackOptions } from "@/api/@tanstack/react-query.gen";
import { PageHeader } from "@/components/layout/PageHeader";
import { PageLayout } from "@/components/layout/PageLayout";
import {
	MyProductFeedbackList,
	type MyProductFeedbackListState,
} from "@/components/product-feedback/MyProductFeedbackList";
import { isSettling, type ProductFeedbackAction } from "@/components/product-feedback/sharing-copy";
import { useMyProductFeedbackActions } from "@/hooks/use-product-feedback";
import { pageHead } from "@/lib/page-title";
import type { Route as RootRoute } from "@/routes/__root";

const SETTLING_REFRESH_MS = 10_000;

export const Route = createFileRoute("/_authenticated/product-feedback")({
	search: {
		middlewares: [retainSearchParams<typeof RootRoute.types.fullSearchSchema>(["chromeWorkspace"])],
	},
	head: pageHead("Your product feedback"),
	component: YourProductFeedbackPage,
});

function YourProductFeedbackPage() {
	// A report that can still change on its own is read again, so a hold ends on screen too.
	const reportsQuery = useQuery({
		...listMyProductFeedbackOptions(),
		refetchInterval: (query) =>
			query.state.data?.some((report) => isSettling(report.state)) === true
				? SETTLING_REFRESH_MS
				: false,
	});
	const actions = useMyProductFeedbackActions();

	let state: MyProductFeedbackListState;
	if (reportsQuery.isPending) {
		state = { status: "loading" };
	} else if (reportsQuery.isError) {
		state = {
			status: "error",
			error: reportsQuery.error,
			onRetry: () => {
				void reportsQuery.refetch();
			},
		};
	} else {
		state = { status: "ready", reports: reportsQuery.data };
	}
	const pending: Record<string, ProductFeedbackAction> = {};
	for (const report of reportsQuery.data ?? []) {
		const action = actions.pendingFor(report.id);
		if (action !== undefined) {
			pending[report.id] = action;
		}
	}

	return (
		<PageLayout>
			<PageHeader
				icon={<MessageSquarePlus />}
				title="Your product feedback"
				description="The ideas, bugs and feedback you sent, and where each one is now."
			/>
			<MyProductFeedbackList
				state={state}
				pending={pending}
				onKeepPrivate={(report) => actions.keepPrivate(report.id)}
				onEdit={async (report, draft) => actions.edit(report.id, draft)}
				onMakePrivate={(report) => actions.makePrivate(report.id)}
				onAllowPublicChange={(report, allowPublic) => actions.allowPublic(report.id, allowPublic)}
			/>
		</PageLayout>
	);
}
