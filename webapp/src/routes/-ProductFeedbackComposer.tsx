import { noop } from "@tanstack/react-query";
import { lazy, Suspense, useEffect } from "react";

import type { FeedbackKind } from "@/components/product-feedback/feedback-copy";
import {
	PAGE_PATH_MAX_LENGTH,
	ProductFeedbackDialog,
	USER_AGENT_MAX_LENGTH,
} from "@/components/product-feedback/ProductFeedbackDialog";
import { useProductFeedbackSharing, useSubmitProductFeedback } from "@/hooks/use-product-feedback";

/** The receipt, with its hold, withdrawal and edit, loads once a report has been sent. */
export const loadProductFeedbackConfirmation = async () => import("./-ProductFeedbackConfirmation");
const ProductFeedbackConfirmation = lazy(loadProductFeedbackConfirmation);

interface ProductFeedbackComposerProps {
	open: boolean;
	onOpenChange: (open: boolean) => void;
	kind: FeedbackKind;
	onKindChange: (kind: FeedbackKind) => void;
	/** The screen the report is about, without the ids or search of its address. */
	route: string;
	workspaceSlug?: string;
}

/** The dialog behind the feedback menu; it loads when someone first chooses to send feedback. */
export default function ProductFeedbackComposer({
	open,
	onOpenChange,
	kind,
	onKindChange,
	route,
	workspaceSlug,
}: ProductFeedbackComposerProps) {
	const feedback = useSubmitProductFeedback(workspaceSlug);
	// Fetched while the person writes, so the receipt is there when Send returns. A failed fetch
	// surfaces where the receipt renders.
	useEffect(() => {
		loadProductFeedbackConfirmation().catch(noop);
	}, []);
	const { sharing, publicRepository } = useProductFeedbackSharing(open);
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
				feedback.receipt === undefined ? undefined : (
					<Suspense fallback={null}>
						<ProductFeedbackConfirmation
							receipt={feedback.receipt}
							open={open}
							publicRepository={publicRepository}
						/>
					</Suspense>
				)
			}
		/>
	);
}
