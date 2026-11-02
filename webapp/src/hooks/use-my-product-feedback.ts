import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";

import {
	allowMyProductFeedbackPublicMutation,
	editMyProductFeedbackDraftMutation,
	getMyProductFeedbackOptions,
	getMyProductFeedbackQueryKey,
	keepMyProductFeedbackPrivateMutation,
	listMyProductFeedbackQueryKey,
	makeMyProductFeedbackPrivateMutation,
} from "@/api/@tanstack/react-query.gen";
import type { EditProductFeedbackDraft, FeedbackReceipt, MyProductFeedback } from "@/api/types.gen";
import type { ReceiptReport } from "@/components/product-feedback/ProductFeedbackReceipt";
import { isSettling, type ProductFeedbackAction } from "@/components/product-feedback/sharing-copy";
import { problemDetailOf } from "@/lib/problem-detail";
import { toast } from "@/lib/toast";

/** How often an open confirmation reads a report that can still change on its own. */
const FOLLOW_INTERVAL_MS = 3000;

/** The receipt's fields read from the answer to Send, before the report itself has been read. */
function fromReceipt(receipt: FeedbackReceipt): ReceiptReport {
	return {
		id: receipt.id,
		state: receipt.state,
		route: receipt.route,
		filedAfter: receipt.filedAfter,
		canKeepPrivate: false,
		canEdit: false,
	};
}

/**
 * Follows an accepted report while its confirmation is open: every few seconds while it can still
 * change on its own, and not at all once it settled or went to the admin email.
 */
export function useFollowedProductFeedback(
	receipt: FeedbackReceipt | undefined,
	open: boolean,
): ReceiptReport | undefined {
	const followable = receipt !== undefined && receipt.state !== "SENT_TO_ADMINS";
	const query = useQuery({
		...getMyProductFeedbackOptions({ path: { feedbackId: receipt?.id ?? "" } }),
		enabled: open && followable,
		refetchInterval: (current) => {
			const state = current.state.data?.state ?? receipt?.state;
			return open && state !== undefined && isSettling(state) ? FOLLOW_INTERVAL_MS : false;
		},
	});
	if (receipt === undefined) {
		return;
	}
	const report = query.data;
	if (report === undefined) {
		return fromReceipt(receipt);
	}
	// The report carries its hold only once held; while it drafts, the answer to Send has it.
	const filedAfter =
		report.filedAfter ?? (report.state === "DRAFTING" ? receipt.filedAfter : undefined);
	return { ...report, filedAfter } satisfies ReceiptReport;
}

/** The person's own changes to a report, shared by the confirmation and Your product feedback. */
export function useMyProductFeedbackActions() {
	const queryClient = useQueryClient();
	const settle = (report: MyProductFeedback) => {
		queryClient.setQueryData(
			getMyProductFeedbackQueryKey({ path: { feedbackId: report.id } }),
			report,
		);
		void queryClient.invalidateQueries({ queryKey: listMyProductFeedbackQueryKey() });
	};
	const refused = (fallback: string) => (error: unknown) => {
		toast.error(fallback, { description: problemDetailOf(error) });
		void queryClient.invalidateQueries({ queryKey: listMyProductFeedbackQueryKey() });
	};
	const keepPrivate = useMutation({
		...keepMyProductFeedbackPrivateMutation(),
		onSuccess: settle,
		onError: refused("We could not keep it private"),
	});
	const edit = useMutation({
		...editMyProductFeedbackDraftMutation(),
		onSuccess: settle,
		onError: refused("We could not save the issue"),
	});
	const makePrivate = useMutation({
		...makeMyProductFeedbackPrivateMutation(),
		onSuccess: (report) => {
			settle(report);
			toast.success("The issue is in the private repository now");
		},
		onError: refused("We could not make the issue private"),
	});
	const allowPublic = useMutation({
		...allowMyProductFeedbackPublicMutation(),
		onSuccess: settle,
		onError: refused("We could not save your choice"),
	});
	const pendingFor = (feedbackId: string): ProductFeedbackAction | undefined => {
		if (keepPrivate.isPending && keepPrivate.variables.path.feedbackId === feedbackId) {
			return "keep-private";
		}
		if (edit.isPending && edit.variables.path.feedbackId === feedbackId) {
			return "edit";
		}
		if (makePrivate.isPending && makePrivate.variables.path.feedbackId === feedbackId) {
			return "make-private";
		}
		if (allowPublic.isPending && allowPublic.variables.path.feedbackId === feedbackId) {
			return "allow-public";
		}
		return undefined;
	};
	return {
		pendingFor,
		keepPrivate: (feedbackId: string) => keepPrivate.mutate({ path: { feedbackId } }),
		edit: async (feedbackId: string, body: EditProductFeedbackDraft) => {
			try {
				await edit.mutateAsync({ path: { feedbackId }, body });
				return true;
			} catch {
				return false;
			}
		},
		makePrivate: (feedbackId: string) => makePrivate.mutate({ path: { feedbackId } }),
		allowPublic: (feedbackId: string, allow: boolean) =>
			allowPublic.mutate({ path: { feedbackId }, body: { allowPublic: allow } }),
	};
}
