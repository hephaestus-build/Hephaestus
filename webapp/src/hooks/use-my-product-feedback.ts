import { useMutation, useQueryClient } from "@tanstack/react-query";

import {
	allowMyProductFeedbackPublicMutation,
	editMyProductFeedbackDraftMutation,
	getMyProductFeedbackQueryKey,
	keepMyProductFeedbackPrivateMutation,
	listMyProductFeedbackQueryKey,
	makeMyProductFeedbackPrivateMutation,
} from "@/api/@tanstack/react-query.gen";
import type { EditProductFeedbackDraft, MyProductFeedback } from "@/api/types.gen";
import type { ProductFeedbackAction } from "@/components/product-feedback/sharing-copy";
import { problemDetailOf } from "@/lib/problem-detail";
import { toast } from "@/lib/toast";

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
		onSuccess: (report) => {
			settle(report);
			toast.success("Report kept private");
		},
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
