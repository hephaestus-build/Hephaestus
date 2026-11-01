import { useMatches } from "@tanstack/react-router";

import type { FeedbackKind } from "@/components/product-feedback/feedback-copy";
import {
	PAGE_PATH_MAX_LENGTH,
	ProductFeedbackDialog,
	USER_AGENT_MAX_LENGTH,
} from "@/components/product-feedback/ProductFeedbackDialog";
import { ProductFeedbackReceipt } from "@/components/product-feedback/ProductFeedbackReceipt";
import {
	useFollowedProductFeedback,
	useMyProductFeedbackActions,
	useProductFeedbackSharing,
	useSubmitProductFeedback,
} from "@/hooks/use-product-feedback";

/**
 * The page a report is sent from, as its route: `/w/$workspaceSlug/practices`, never `/w/acme/practices`.
 * The route names the screen without the workspace, ids or search that the address carries.
 */
function routeOf(fullPath: string | undefined): string {
	if (fullPath === undefined || fullPath === "/") {
		return "/";
	}
	return fullPath.endsWith("/") ? fullPath.slice(0, -1) : fullPath;
}

interface ProductFeedbackPanelProps {
	open: boolean;
	onOpenChange: (open: boolean) => void;
	kind: FeedbackKind;
	onKindChange: (kind: FeedbackKind) => void;
	workspaceSlug?: string;
}

export default function ProductFeedbackPanel({
	open,
	onOpenChange,
	kind,
	onKindChange,
	workspaceSlug,
}: ProductFeedbackPanelProps) {
	const route = useMatches({ select: (matches) => routeOf(matches.at(-1)?.fullPath) });
	const feedback = useSubmitProductFeedback(workspaceSlug);
	const { sharing, publicRepository } = useProductFeedbackSharing(open);
	const report = useFollowedProductFeedback(feedback.receipt, open);
	const actions = useMyProductFeedbackActions();
	return (
		<ProductFeedbackDialog
			open={open}
			onOpenChange={(next) => {
				if (!next) {
					feedback.reset();
				}
				onOpenChange(next);
			}}
			kind={kind}
			onKindChange={onKindChange}
			context={{
				pagePath: route.slice(0, PAGE_PATH_MAX_LENGTH),
				userAgent: navigator.userAgent.slice(0, USER_AGENT_MAX_LENGTH),
			}}
			sharing={sharing}
			workspaceSlug={workspaceSlug}
			isSubmitting={feedback.isPending}
			error={feedback.error}
			onSubmit={feedback.submit}
			confirmation={
				report === undefined ? undefined : (
					<ProductFeedbackReceipt
						report={report}
						publicRepository={publicRepository}
						pending={actions.pendingFor(report.id)}
						onKeepPrivate={() => actions.keepPrivate(report.id)}
						onEdit={async (draft) => actions.edit(report.id, draft)}
					/>
				)
			}
		/>
	);
}
