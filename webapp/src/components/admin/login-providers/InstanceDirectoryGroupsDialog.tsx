import { useId, useState } from "react";
import { useSpinDelay } from "spin-delay";

import type { LoginProviderView } from "@/api/types.gen";
import { Button } from "@/components/ui/button";
import {
	Dialog,
	DialogContent,
	DialogDescription,
	DialogFooter,
	DialogHeader,
	DialogTitle,
} from "@/components/ui/dialog";
import { Field, FieldDescription, FieldLabel } from "@/components/ui/field";
import { Spinner } from "@/components/ui/spinner";
import { Textarea } from "@/components/ui/textarea";

export interface InstanceDirectoryGroupsDialogProps {
	provider: LoginProviderView | null;
	isSaving: boolean;
	onClose: () => void;
	onSave: (groupIds: string[]) => void;
}

/** Instance approval is a separate step from each workspace owner's access policy. */
export function InstanceDirectoryGroupsDialog({
	provider,
	isSaving,
	onClose,
	onSave,
}: InstanceDirectoryGroupsDialogProps) {
	return (
		<Dialog
			open={provider !== null}
			onOpenChange={(open) => {
				if (!open) onClose();
			}}
		>
			<DialogContent>
				<DialogHeader>
					<DialogTitle>Approve directory groups</DialogTitle>
					<DialogDescription>
						Allow workspace owners to select these Keycloak groups for eligibility. This does not
						grant workspace access or change organizational sign-in.
					</DialogDescription>
				</DialogHeader>
				{provider && (
					<DirectoryGroupsForm
						key={provider.registrationId}
						provider={provider}
						isSaving={isSaving}
						onClose={onClose}
						onSave={onSave}
					/>
				)}
			</DialogContent>
		</Dialog>
	);
}

function DirectoryGroupsForm({
	provider,
	isSaving,
	onClose,
	onSave,
}: Omit<InstanceDirectoryGroupsDialogProps, "provider"> & { provider: LoginProviderView }) {
	const id = useId();
	const [text, setText] = useState(provider.directoryGroupIds.join("\n"));
	const groupIds = [
		...new Set(
			text
				.split(/\r?\n/)
				.map((line) => line.trim())
				.filter(Boolean),
		),
	];
	const invalid =
		groupIds.length > 100 ||
		groupIds.some(
			(groupId) =>
				groupId.length > 255 ||
				groupId.includes("/") ||
				groupId.includes("\\") ||
				groupId === "." ||
				groupId === "..",
		);
	const showSaving = useSpinDelay(isSaving, { delay: 1000, minDuration: 500 });
	return (
		<form
			className="space-y-4"
			onSubmit={(event) => {
				event.preventDefault();
				if (!invalid) onSave(groupIds);
			}}
		>
			<p className="break-all text-sm">
				Exact issuer: <code>{provider.baseUrl}</code>
			</p>
			<Field>
				<FieldLabel htmlFor={`${id}-groups`}>Approved group IDs</FieldLabel>
				<FieldDescription id={`${id}-help`}>
					One immutable Keycloak group ID per line, not a group name. Confirm the environment and
					realm with the directory operator.
				</FieldDescription>
				<Textarea
					id={`${id}-groups`}
					value={text}
					onChange={(event) => setText(event.target.value)}
					disabled={isSaving}
					rows={6}
					aria-invalid={invalid}
					aria-describedby={`${id}-help${invalid ? ` ${id}-error` : ""}`}
				/>
				{invalid && (
					<p id={`${id}-error`} className="text-sm text-destructive">
						Use at most 100 IDs of at most 255 characters, without slash or path segments.
					</p>
				)}
			</Field>
			{groupIds.length === 0 && (
				<p className="text-sm text-muted-foreground">
					An empty list removes directory approval. New grants and directory reads stop; existing
					access is not reported as revoked. Workspace owners can end management to remove managed
					access.
				</p>
			)}
			<DialogFooter>
				<Button type="button" variant="outline" onClick={onClose}>
					Cancel
				</Button>
				<Button
					type="submit"
					disabled={isSaving || invalid}
					variant={groupIds.length === 0 ? "destructive" : "default"}
				>
					{showSaving && <Spinner />}
					{showSaving
						? "Saving…"
						: groupIds.length === 0
							? "Remove directory approval"
							: "Approve groups"}
				</Button>
			</DialogFooter>
		</form>
	);
}
