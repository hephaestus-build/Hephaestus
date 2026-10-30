import { useMutation, useQuery } from "@tanstack/react-query";
import { createFileRoute } from "@tanstack/react-router";
import { FlaskConical } from "lucide-react";
import { useState } from "react";
import { toast } from "@/lib/toast";

import {
	adminListWorkspacesOptions,
	checkResearchRemovalsMutation,
	closeResearchDisclosureMutation,
	getConsentStatusOptions,
	recordResearchRemovalsMutation,
} from "@/api/@tanstack/react-query.gen";
import { releaseResearchDataset } from "@/api/sdk.gen";
import type { InstanceResearchDisclosureListState } from "@/components/admin/research/InstanceResearchDisclosureList";
import {
	InstanceResearchPage,
	type ResearchPasskeyState,
	type ResearchProgramState,
	type ResearchReleaseDraft,
	type ResearchReleaseOutcome,
	type ResearchWorkspacesState,
} from "@/components/admin/research/InstanceResearchPage";
import { ConfirmAccessDialog } from "@/components/auth/ConfirmAccessDialog";
import { PageHeader } from "@/components/layout/PageHeader";
import { PageLayout } from "@/components/layout/PageLayout";
import { usePasskeySettings } from "@/hooks/use-passkey-settings";
import { saveBlobFile } from "@/lib/download";
import { instanceAdminHead } from "@/lib/page-title";
import {
	readReleaseReceipt,
	releaseFilename,
	serializeReleaseBody,
	startOfDayAfter,
	utcDay,
} from "@/lib/research-release";

export const Route = createFileRoute("/_authenticated/admin/research")({
	head: instanceAdminHead("Research datasets"),
	component: ResearchRoute,
});

/**
 * Releases consented research datasets and records what happened to their copies. The server checks
 * every request against the instance's assurance policy, a fresh passkey by default, and owns every
 * fact shown here: the open datasets are read again after each change, and nothing is kept in browser
 * storage.
 */
function ResearchRoute() {
	// The same verification ceremony as Settings; it proves nothing here, the server checks each request.
	const passkeys = usePasskeySettings();
	const consent = useQuery(getConsentStatusOptions({}));
	const workspacesQuery = useQuery(adminListWorkspacesOptions());
	const check = useMutation(checkResearchRemovalsMutation());
	const refreshOpenDatasets = () => check.mutate({});
	const [outcome, setOutcome] = useState<ResearchReleaseOutcome | undefined>();
	const [completed, setCompleted] = useState(0);

	const release = useMutation({
		// Never retried: each accepted request records a disclosure.
		retry: false,
		mutationFn: async (draft: ResearchReleaseDraft) => {
			const { data } = await releaseResearchDataset({
				body: {
					workspaceId: draft.workspaceId,
					firstDay: utcDay(draft.firstDay),
					lastDay: utcDay(draft.lastDay),
					useUntil: startOfDayAfter(draft.usePermittedThrough),
				},
				// For this request only: the two calendar days go out as `yyyy-MM-dd`, as the contract says.
				bodySerializer: serializeReleaseBody,
				// The file goes to disk as the server sent it; only its header is read here.
				parseAs: "blob",
				throwOnError: true,
			});
			if (!(data instanceof Blob)) {
				throw new Error("The server did not return a dataset file.");
			}
			const receipt = await readReleaseReceipt(data);
			saveBlobFile(data, releaseFilename(receipt?.datasetId));
			return receipt;
		},
		onMutate: () => {
			setOutcome(undefined);
		},
		onSuccess: (receipt) => {
			setOutcome(receipt === undefined ? { kind: "downloaded" } : { kind: "receipt", receipt });
			setCompleted((count) => count + 1);
			refreshOpenDatasets();
		},
	});
	const removal = useMutation({
		...recordResearchRemovalsMutation(),
		onSuccess: (data) => {
			toast.success(
				data.recorded === 1
					? "Recorded the removal of 1 participant"
					: `Recorded the removal of ${data.recorded} participants`,
			);
			refreshOpenDatasets();
		},
		// A lost response can follow a recorded attestation: read the current state, never send it again.
		onError: refreshOpenDatasets,
	});
	const closure = useMutation({
		...closeResearchDisclosureMutation(),
		onSuccess: () => {
			toast.success("Dataset closed. Its participant keys are no longer linked to accounts.");
			refreshOpenDatasets();
		},
		onError: refreshOpenDatasets,
	});

	let program: ResearchProgramState;
	if (consent.data) {
		program = {
			status: "ready",
			organization: consent.data.researchOrganization,
			noticeVersion: consent.data.noticeVersion,
		};
	} else if (consent.isError) {
		program = {
			status: "error",
			error: consent.error,
			onRetry: () => {
				void consent.refetch();
			},
		};
	} else {
		program = { status: "loading" };
	}

	const { section } = passkeys;
	let passkey: ResearchPasskeyState;
	if (section.status && !passkeys.statusError) {
		passkey = section.status.assuranceEnforced
			? {
					status: "required",
					available: section.supported && section.status.credentials.length > 0,
					pending: section.pending && section.pendingAction === "verify",
					error: section.error,
					onConfirm: section.onVerify,
				}
			: { status: "notRequired" };
	} else if (section.loading) {
		passkey = { status: "loading" };
	} else {
		// The status read failed. Export stays offered: the server still checks each request.
		passkey = { status: "error", error: passkeys.statusError, onRetry: section.onRetry };
	}

	let workspaces: ResearchWorkspacesState;
	if (workspacesQuery.data) {
		workspaces = {
			status: "ready",
			options: workspacesQuery.data
				.filter((workspace) => workspace.status === "ACTIVE")
				.map((workspace) => ({
					id: String(workspace.id),
					label: `${workspace.displayName} (${workspace.workspaceSlug})`,
				})),
		};
	} else if (workspacesQuery.isError) {
		workspaces = {
			status: "error",
			error: workspacesQuery.error,
			onRetry: () => {
				void workspacesQuery.refetch();
			},
		};
	} else {
		workspaces = { status: "loading" };
	}
	const workspaceNames = new Map(
		(workspacesQuery.data ?? []).map((workspace) => [String(workspace.id), workspace.displayName]),
	);

	let openDatasets: InstanceResearchDisclosureListState;
	if (check.isPending) {
		openDatasets = { status: "loading" };
	} else if (check.isError) {
		openDatasets = { status: "error", error: check.error };
	} else if (check.data) {
		openDatasets = {
			status: "ready",
			// Each check is one server answer; a dialog belongs to the answer it was opened from.
			revision: check.submittedAt,
			datasets: check.data.map((dataset) => ({
				...dataset,
				workspaceLabel:
					workspaceNames.get(dataset.workspaceId) ?? `Workspace ${dataset.workspaceId}`,
			})),
		};
	} else {
		openDatasets = { status: "unchecked" };
	}

	let pendingDatasetId: string | undefined;
	if (removal.isPending) {
		pendingDatasetId = removal.variables.path.datasetId;
	} else if (closure.isPending) {
		pendingDatasetId = closure.variables.path.datasetId;
	}

	return (
		<PageLayout>
			<PageHeader
				icon={<FlaskConical />}
				title="Research datasets"
				description="Release consented, pseudonymous research data and record what happened to every copy."
			/>
			<InstanceResearchPage
				program={program}
				workspaces={workspaces}
				passkey={passkey}
				release={{
					pending: release.isPending,
					error: release.error ?? undefined,
					outcome,
					completed,
					onRelease: (draft) => release.mutate(draft),
				}}
				disclosures={{
					state: openDatasets,
					actionError: removal.error ?? closure.error ?? undefined,
					pendingDatasetId,
					onCheck: refreshOpenDatasets,
					onRecordRemoved: (datasetId, participantKeys) => {
						closure.reset();
						removal.mutate({ path: { datasetId }, body: { participantKeys } });
					},
					onClose: (datasetId, closureKind) => {
						removal.reset();
						closure.mutate({ path: { datasetId }, body: { closure: closureKind } });
					},
				}}
			/>
			<ConfirmAccessDialog {...passkeys.confirmation} />
		</PageLayout>
	);
}
