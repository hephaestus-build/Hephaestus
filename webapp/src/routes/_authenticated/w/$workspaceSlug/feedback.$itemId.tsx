import { useInfiniteQuery } from "@tanstack/react-query";
import { createFileRoute, Link, notFound } from "@tanstack/react-router";
import { useQueryReadRefusal } from "@/runtime/tanstack-query/use-query-read-refusal";

import { listOwnItemReviewsInfiniteOptions } from "@/api/@tanstack/react-query.gen";
import { ItemFeedbackPage } from "@/components/profile/ItemFeedbackPage";
import { reviewFeedState } from "@/components/profile/reviews";
import { buttonVariants } from "@/components/ui/button";
import { useFeedbackResponseWrite } from "@/hooks/use-feedback-response-write";
import { REVIEW_PAGE_SIZE } from "@/hooks/use-practice-group-detail";
import { useWorkspaceFeatures } from "@/hooks/use-workspace-features";
import { pageHead } from "@/lib/page-title";
import { useAuth } from "@/runtime/auth/AuthContext";
import { springPageParams } from "@/runtime/tanstack-query/spring-page";

const ITEM_ID = /^[1-9]\d{0,18}$/u;

// Provider comments link here by workspace item id; a provider number cannot identify the item.
export const Route = createFileRoute("/_authenticated/w/$workspaceSlug/feedback/$itemId")({
	remountDeps: ({ params }) => params,
	loader: ({ params: { itemId } }) => {
		if (!ITEM_ID.test(itemId)) {
			throw notFound();
		}
		return { itemId };
	},
	head: pageHead("Your feedback on this work"),
	component: ItemFeedbackRoute,
});

function ItemFeedbackRoute() {
	const readOnly = useAuth().userView !== undefined;
	const { workspaceSlug } = Route.useParams();
	const { itemId } = Route.useLoaderData();
	const listOptions = listOwnItemReviewsInfiniteOptions({
		path: { workspaceSlug, itemId },
		query: { size: REVIEW_PAGE_SIZE },
	});
	const reviews = useInfiniteQuery({
		...listOptions,
		...springPageParams,
	});
	useQueryReadRefusal(listOptions.queryKey, reviews.isError, reviews.error);
	const responses = useFeedbackResponseWrite(workspaceSlug);
	const { practicesEnabled } = useWorkspaceFeatures(workspaceSlug);
	return (
		<ItemFeedbackPage
			feed={reviewFeedState(reviews)}
			responses={readOnly ? undefined : responses}
			profileLink={
				practicesEnabled === true ? (
					<Link
						to="/w/$workspaceSlug/practice-profile"
						params={{ workspaceSlug }}
						className={buttonVariants({ variant: "outline", size: "sm" })}
					>
						See all your feedback
					</Link>
				) : undefined
			}
		/>
	);
}
