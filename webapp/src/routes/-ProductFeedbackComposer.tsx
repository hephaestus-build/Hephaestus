import { Link, useNavigate } from "@tanstack/react-router";
import { useEffect, useRef } from "react";

import { InlineLink } from "@/components/common/InlineLink";
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
	/** The screen the report is about, without the ids or search of its address. */
	route: string;
	workspaceSlug?: string;
}

/** The composer loads on the first send action, then stays mounted to preserve a closed draft. */
export default function ProductFeedbackComposer({
	open,
	onOpenChange,
	route,
	workspaceSlug,
}: ProductFeedbackComposerProps) {
	const navigate = useNavigate();
	const openRef = useRef(open);
	useEffect(() => {
		openRef.current = open;
	}, [open]);
	const feedback = useSubmitProductFeedback(workspaceSlug, {
		onAccepted: () => {
			onOpenChange(false);
			toast.success("Feedback saved", {
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
				toast.error("We could not confirm receipt", {
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
			context={{
				pagePath: route.slice(0, PAGE_PATH_MAX_LENGTH),
				userAgent: navigator.userAgent.slice(0, USER_AGENT_MAX_LENGTH),
			}}
			sharing={sharing}
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
