import { Link, useNavigate } from "@tanstack/react-router";
import { useEffect, useRef } from "react";

import { InlineLink } from "@/components/common/InlineLink";
import {
	PAGE_PATH_MAX_LENGTH,
	ProductFeedbackDialog,
} from "@/components/product-feedback/ProductFeedbackDialog";
import { receiptLine } from "@/components/product-feedback/sharing-copy";
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
	const { sharing } = useProductFeedbackSharing(open);
	const feedback = useSubmitProductFeedback(workspaceSlug, {
		onAccepted: (receipt) => {
			onOpenChange(false);
			toast.success("Report sent", {
				description: receiptLine(receipt.route, sharing.status === "public"),
				duration: 10_000,
				action: {
					label: "View",
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
	return (
		<ProductFeedbackDialog
			open={open}
			onOpenChange={(next) => {
				if (!next && !feedback.isPending) {
					feedback.reset();
				}
				onOpenChange(next);
			}}
			pagePath={route.slice(0, PAGE_PATH_MAX_LENGTH)}
			sharing={sharing}
			isSubmitting={feedback.isPending}
			error={feedback.error}
			onSubmit={feedback.submit}
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
