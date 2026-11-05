import { Link, useNavigate } from "@tanstack/react-router";
import { useEffect, useRef } from "react";

import { InlineLink } from "@/components/common/InlineLink";
import type { FeedbackKind } from "@/components/product-feedback/feedback-copy";
import {
	PAGE_PATH_MAX_LENGTH,
	ProductFeedbackDialog,
	USER_AGENT_MAX_LENGTH,
} from "@/components/product-feedback/ProductFeedbackDialog";
import { useProductFeedbackSharing, useSubmitProductFeedback } from "@/hooks/use-product-feedback";
import { toast } from "@/lib/toast";

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
	const navigate = useNavigate();
	const openRef = useRef(open);
	useEffect(() => {
		openRef.current = open;
	}, [open]);
	const feedback = useSubmitProductFeedback(workspaceSlug, {
		onAccepted: (receipt) => {
			onOpenChange(false);
			const descriptions = {
				EMAIL: "Saved for your instance admins.",
				PUBLIC:
					"Saved. Checks run before it can become a public GitHub issue. You can still keep it private.",
				PRIVATE: "Saved. We’ll file it privately on GitHub.",
			};
			const description = descriptions[receipt.route];
			toast.success("Report received", {
				description,
				duration: 10_000,
				action: {
					label: "Your reports",
					onClick: () => {
						void navigate({ to: "/product-feedback", search: { chromeWorkspace: workspaceSlug } });
					},
				},
			});
		},
		onRejected: (message) => {
			if (!openRef.current) {
				toast.error("Receipt not confirmed", {
					description: message,
					action: { label: "Open draft", onClick: () => onOpenChange(true) },
				});
			}
		},
	});
	const { sharing, retry } = useProductFeedbackSharing(open);
	return (
		<ProductFeedbackDialog
			open={open}
			onOpenChange={(next) => {
				if (!next && !feedback.isPending) {
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
			onRetrySharing={retry}
			reportsLink={
				<InlineLink
					render={
						<Link
							to="/product-feedback"
							search={{ chromeWorkspace: workspaceSlug }}
							onClick={() => onOpenChange(false)}
						/>
					}
				>
					Your reports
				</InlineLink>
			}
		/>
	);
}
