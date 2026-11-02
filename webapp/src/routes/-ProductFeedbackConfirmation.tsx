import type { FeedbackReceipt } from "@/api/types.gen";
import { ProductFeedbackReceipt } from "@/components/product-feedback/ProductFeedbackReceipt";
import {
	useFollowedProductFeedback,
	useMyProductFeedbackActions,
} from "@/hooks/use-my-product-feedback";

interface ProductFeedbackConfirmationProps {
	receipt: FeedbackReceipt;
	open: boolean;
	publicRepository?: string;
}

/** The answer to Send, followed while the dialog stays open; it loads after the first report. */
export default function ProductFeedbackConfirmation({
	receipt,
	open,
	publicRepository,
}: ProductFeedbackConfirmationProps) {
	const report = useFollowedProductFeedback(receipt, open);
	const actions = useMyProductFeedbackActions();
	if (report === undefined) {
		return null;
	}
	return (
		<ProductFeedbackReceipt
			report={report}
			publicRepository={publicRepository}
			pending={actions.pendingFor(report.id)}
			onKeepPrivate={() => actions.keepPrivate(report.id)}
			onEdit={async (draft) => actions.edit(report.id, draft)}
		/>
	);
}
