import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { createFileRoute, notFound } from "@tanstack/react-router";
import { toast } from "sonner";

import {
	getArtifactTraceOptions,
	getArtifactTraceQueryKey,
	requestPracticeReviewMutation,
} from "@/api/@tanstack/react-query.gen";
import { TracePage } from "@/components/practice-trace/TracePage";
import { workspaceMembershipQueryOptions } from "@/integrations/auth/guard";
import { problemDetailOf } from "@/lib/problem-detail";
import { hasMinimumWorkspaceRole } from "@/lib/workspace-roles";

export const Route = createFileRoute(
	"/_authenticated/w/$workspaceSlug/reviews/$artifactKind/$artifactId",
)({
	remountDeps: ({ params }) => params,
	// `artifactKind` stays the wire id (`scm.pull_request`) rather than a short slug: kinds are an
	// open vocabulary, and a slug map would make a kind this build has never heard of unreachable.
	loader: ({ params: { artifactId } }) => {
		const id = Number(artifactId);
		if (!Number.isSafeInteger(id) || id < 1) {
			throw notFound();
		}
		return { artifactId: id };
	},
	component: ReviewActivityDetailRoute,
});

function ReviewActivityDetailRoute() {
	const { workspaceSlug, artifactKind } = Route.useParams();
	const { artifactId } = Route.useLoaderData();
	const queryClient = useQueryClient();
	// The same cache entry the sidebar and the admin guard already read, on the same schedule, so
	// asking here costs no request. It decides whether a refusal may offer its fix: this page is
	// open to every member, and most of them would only be bounced back off the admin guard.
	const membership = useQuery(workspaceMembershipQueryOptions(workspaceSlug));
	const trace = useQuery({
		...getArtifactTraceOptions({ path: { workspaceSlug, artifactKind, artifactId } }),
	});
	const requestReview = useMutation({
		...requestPracticeReviewMutation(),
		onSuccess: (outcome, { path, body }) => {
			if (outcome.status !== "SUBMITTED") return;
			void queryClient.invalidateQueries({
				queryKey: getArtifactTraceQueryKey({
					path: { ...path, artifactKind: body.artifactKind, artifactId: body.artifactId },
				}),
			});
			toast.success("Review started");
		},
		onError: (error) =>
			toast.error("Couldn't ask for a review", {
				description: problemDetailOf(error, "Try again in a moment."),
			}),
	});

	return (
		<TracePage
			workspaceSlug={workspaceSlug}
			canAdminister={hasMinimumWorkspaceRole(membership.data?.role, "ADMIN")}
			trace={trace.data}
			isLoading={trace.isLoading}
			error={trace.isError ? trace.error : undefined}
			onRetry={() => void trace.refetch()}
			onRequestReview={() =>
				requestReview.mutate({ path: { workspaceSlug }, body: { artifactKind, artifactId } })
			}
			requestPending={requestReview.isPending}
			// Read off the mutation rather than mirrored into state: the next accepted ask replaces the
			// result, so the alert clears itself and cannot outlive the refusal it explains.
			refusal={requestReview.data?.status === "REFUSED" ? requestReview.data : undefined}
		/>
	);
}
