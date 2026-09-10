import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { createFileRoute } from "@tanstack/react-router";
import { toast } from "sonner";
import {
	changeMyGitHubAccessEnrollmentMutation,
	getMyGitHubAccessOptions,
	getMyGitHubAccessQueryKey,
} from "@/api/@tanstack/react-query.gen";
import { GithubAccessPage } from "@/components/github-access/GithubAccessPage";
import { problemDetailOf } from "@/lib/problem-detail";

export const Route = createFileRoute("/_authenticated/github-access")({
	component: GithubAccessContainer,
});
function GithubAccessContainer() {
	const queryClient = useQueryClient();
	const query = useQuery({ ...getMyGitHubAccessOptions(), refetchInterval: 5000 });
	const enrollment = useMutation({
		...changeMyGitHubAccessEnrollmentMutation(),
		onSuccess: () => queryClient.invalidateQueries({ queryKey: getMyGitHubAccessQueryKey() }),
		onError: (error) =>
			toast.error(problemDetailOf(error, "Couldn't update your GitHub access preference.")),
	});
	return (
		<GithubAccessPage
			state={
				query.isPending
					? { status: "loading" }
					: query.isError
						? { status: "error", error: query.error, onRetry: () => void query.refetch() }
						: { status: "ready", offers: query.data }
			}
			changingTargetId={enrollment.isPending ? enrollment.variables.path.targetId : undefined}
			onEnroll={(targetId, enrolled) =>
				enrollment.mutate({ path: { targetId }, body: { enrolled } })
			}
		/>
	);
}
