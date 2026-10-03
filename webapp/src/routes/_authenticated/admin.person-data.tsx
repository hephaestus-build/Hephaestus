import { useMutation, useQuery } from "@tanstack/react-query";
import { createFileRoute, useNavigate } from "@tanstack/react-router";
import { ShieldCheck } from "lucide-react";
import { useState } from "react";
import { z } from "zod";

import {
	adminListPersonDataProvidersOptions,
	adminGetPersonDataRequestOptions,
	adminPreviewPersonDataMutation,
	adminErasePersonDataMutation,
} from "@/api/@tanstack/react-query.gen";
import { adminExportPersonData } from "@/api/sdk.gen";
import {
	InstancePersonDataPage,
	type InstancePersonDataPageState,
	type PersonSelectionInput,
} from "@/components/admin/privacy/InstancePersonDataPage";
import { ConfirmAccessDialog } from "@/components/auth/ConfirmAccessDialog";
import { PageHeader } from "@/components/layout/PageHeader";
import { PageLayout } from "@/components/layout/PageLayout";
import { useConfirmAccess } from "@/hooks/use-confirm-access";
import { instanceAdminHead } from "@/lib/page-title";
import { type StepUpChallenge, stepUpChallengeOf } from "@/lib/problem-detail";

export const Route = createFileRoute("/_authenticated/admin/person-data")({
	head: instanceAdminHead("Person data"),
	validateSearch: z.object({ requestId: z.uuid().optional().catch(undefined) }),
	component: PersonDataRoute,
});

function PersonDataRoute() {
	const { requestId } = Route.useSearch();
	const navigate = useNavigate({ from: Route.fullPath });
	const [selection, setSelection] = useState<PersonSelectionInput>({
		accountId: "",
		identities: [],
	});
	const [challenge, setChallenge] = useState<StepUpChallenge | undefined>();
	const confirmAccess = useConfirmAccess(challenge !== undefined);
	const onError = (error: unknown) => {
		const stepUp = stepUpChallengeOf(error);
		if (stepUp) {
			setChallenge(stepUp);
		}
	};
	const providers = useQuery(adminListPersonDataProvidersOptions());
	const request = useQuery({
		...adminGetPersonDataRequestOptions({
			path: { id: requestId ?? "00000000-0000-0000-0000-000000000000" },
		}),
		enabled: requestId !== undefined,
		refetchInterval: (query) => (query.state.data?.state === "ERASING" ? 5000 : false),
	});
	const preview = useMutation({
		...adminPreviewPersonDataMutation(),
		onError,
		onSuccess: (data) => {
			void navigate({ search: { requestId: data.id } });
		},
	});
	const erase = useMutation({
		...adminErasePersonDataMutation(),
		onError,
		onSuccess: async () => {
			await request.refetch();
		},
	});
	const download = useMutation({
		mutationFn: async (id: string) => {
			const { data } = await adminExportPersonData({
				path: { id },
				parseAs: "blob",
				throwOnError: true,
			});
			if (!(data instanceof Blob)) {
				throw new Error("The export did not return a JSON file.");
			}
			const url = URL.createObjectURL(data);
			const link = document.createElement("a");
			link.href = url;
			link.download = "person-data.json";
			link.click();
			URL.revokeObjectURL(url);
		},
		onError,
	});
	// The form's native constraints guarantee positive integers and non-empty subjects here.
	const onPreview = () => {
		preview.mutate({
			body: {
				accountId: selection.accountId === "" ? undefined : Number(selection.accountId),
				identities: selection.identities.map((identity) => ({
					providerId: Number(identity.providerId),
					subject: identity.subject,
					...(identity.teamId === "" ? {} : { teamId: identity.teamId }),
				})),
			},
		});
	};
	const retry = () => {
		preview.reset();
		void providers.refetch();
		if (requestId !== undefined) {
			void request.refetch();
		}
	};
	let pendingAction: "export" | "erase" | undefined;
	if (download.isPending) {
		pendingAction = "export";
	} else if (erase.isPending) {
		pendingAction = "erase";
	}
	const currentRequest = request.data;
	const loadError: unknown = providers.error ?? request.error ?? preview.error;
	let state: InstancePersonDataPageState;
	if (loadError !== null) {
		state = { status: "error", error: loadError, onRetry: retry };
	} else if (
		providers.isPending ||
		preview.isPending ||
		(requestId !== undefined && request.isPending)
	) {
		state = { status: "loading" };
	} else if (currentRequest) {
		state = {
			status: "ready",
			request: currentRequest,
			pendingAction,
			actionError: erase.error ?? download.error ?? undefined,
			onExport: () => download.mutate(currentRequest.id),
			onErase: (externalCopiesRemoved) =>
				erase.mutate({ path: { id: currentRequest.id }, body: { externalCopiesRemoved } }),
		};
	} else {
		state = { status: "empty" };
	}
	return (
		<PageLayout>
			<PageHeader
				icon={<ShieldCheck />}
				title="Person data"
				description="Answer access and erasure requests across every workspace."
			/>
			<InstancePersonDataPage
				key={`${requestId ?? "new"}:${request.data?.state ?? "empty"}`}
				providers={providers.data ?? []}
				selection={selection}
				onChange={setSelection}
				onPreview={onPreview}
				state={state}
			/>
			<ConfirmAccessDialog
				open={challenge !== undefined}
				onOpenChange={(open) => {
					if (!open) {
						setChallenge(undefined);
					}
				}}
				maxAgeSeconds={challenge?.maxAgeSeconds}
				providers={confirmAccess.providers}
				loading={confirmAccess.loading}
				error={confirmAccess.error}
				onRetry={confirmAccess.retry}
				onSignIn={confirmAccess.signIn}
			/>
		</PageLayout>
	);
}
