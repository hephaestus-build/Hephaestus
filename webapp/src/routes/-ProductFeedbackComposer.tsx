import { Link, useNavigate } from "@tanstack/react-router";
import { useEffect, useRef } from "react";

import { InlineLink } from "@/components/common/InlineLink";
import {
	PAGE_PATH_MAX_LENGTH,
	type ProductFeedbackComposerView,
	ProductFeedbackDialog,
} from "@/components/product-feedback/ProductFeedbackDialog";
import { receiptLine } from "@/components/product-feedback/sharing-copy";
import { useProductFeedbackSharing, useSubmitProductFeedback } from "@/hooks/use-product-feedback";
import { toast } from "@/lib/toast";

interface ProductFeedbackComposerProps {
	view: ProductFeedbackComposerView;
	onViewChange: (view: ProductFeedbackComposerView) => void;
	/** The screen the report is about, without the ids or search of its address. */
	route: string;
	workspaceSlug?: string;
}

/** The composer loads on the first send action, then stays mounted to preserve a closed draft. */
export default function ProductFeedbackComposer({
	view,
	onViewChange,
	route,
	workspaceSlug,
}: ProductFeedbackComposerProps) {
	const navigate = useNavigate();
	// A refusal the person can read in the panel needs no toast; a minimized or closed panel does.
	const openRef = useRef(view === "open");
	useEffect(() => {
		openRef.current = view === "open";
	}, [view]);
	const { sharing } = useProductFeedbackSharing(view === "open");
	const feedback = useSubmitProductFeedback(workspaceSlug, {
		onAccepted: (receipt) => {
			onViewChange("closed");
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
					action: { label: "Open draft", onClick: () => onViewChange("open") },
				});
			}
		},
	});
	return (
		<ProductFeedbackDialog
			view={view}
			onViewChange={(next) => {
				if (next !== "open" && !feedback.isPending) {
					feedback.reset();
				}
				onViewChange(next);
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
							onClick={() => onViewChange("closed")}
						/>
					}
				>
					Your reports
				</InlineLink>
			}
		/>
	);
}
