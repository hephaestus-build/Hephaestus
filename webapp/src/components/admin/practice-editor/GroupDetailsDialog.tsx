import { useId, useState } from "react";

import type { PracticeGroup } from "@/api/types.gen";
import { CatalogOriginNote } from "@/components/admin/practices/CatalogOriginBadge";
import { Button } from "@/components/ui/button";
import {
	Dialog,
	DialogBody,
	DialogClose,
	DialogContent,
	DialogFooter,
	DialogForm,
	DialogHeader,
	DialogTitle,
} from "@/components/ui/dialog";
import { Field, FieldDescription, FieldGroup, FieldLabel } from "@/components/ui/field";
import { Input } from "@/components/ui/input";
import { ItemGroup } from "@/components/ui/item";
import { Spinner } from "@/components/ui/spinner";

import { GroupVisualPicker } from "./GroupVisualPicker";

export interface GroupDetails {
	name: string;
	icon: string | null;
	color: string | null;
}

export interface GroupDetailsDialogProps {
	group: PracticeGroup | null;
	open: boolean;
	pending: boolean;
	onOpenChange: (open: boolean) => void;
	onSubmit: (details: GroupDetails) => Promise<boolean>;
}

export function GroupDetailsDialog({
	group,
	open,
	pending,
	onOpenChange,
	onSubmit,
}: GroupDetailsDialogProps) {
	return (
		<Dialog open={open} onOpenChange={onOpenChange}>
			<DialogContent className="sm:max-w-sm">
				<GroupDetailsForm
					key={group?.slug ?? "new"}
					group={group}
					pending={pending}
					onOpenChange={onOpenChange}
					onSubmit={onSubmit}
				/>
			</DialogContent>
		</Dialog>
	);
}

function GroupDetailsForm({
	group,
	pending,
	onOpenChange,
	onSubmit,
}: Omit<GroupDetailsDialogProps, "open">) {
	const fieldId = useId();
	const helpId = useId();
	const editing = group !== null;
	const [name, setName] = useState(group?.name ?? "");
	const [icon, setIcon] = useState<string | null>(group?.icon ?? null);
	const [color, setColor] = useState<string | null>(group?.color ?? null);
	const trimmed = name.trim();
	const unchanged =
		trimmed === group?.name && icon === (group.icon ?? null) && color === (group.color ?? null);

	const saveDetails = async () => {
		if (unchanged) {
			onOpenChange(false);
			return;
		}
		if (await onSubmit({ name: trimmed, icon, color })) {
			onOpenChange(false);
		}
	};

	let submitLabel = editing ? "Save" : "Create";
	if (pending) {
		submitLabel = editing ? "Saving…" : "Creating…";
	}

	return (
		<DialogForm
			onSubmit={(event) => {
				event.preventDefault();
				void saveDetails();
			}}
		>
			<DialogHeader>
				<DialogTitle>{editing ? "Edit group" : "Create group"}</DialogTitle>
			</DialogHeader>
			<DialogBody className="space-y-4 py-1">
				{group?.catalogOrigin != null && (
					// The group row carries only the badge's label; what it means is written here, where
					// the group is edited.
					<ItemGroup>
						<CatalogOriginNote origin={group.catalogOrigin} kind="group" />
					</ItemGroup>
				)}
				<FieldGroup>
					<Field>
						<FieldLabel htmlFor={fieldId}>Name</FieldLabel>
						<div className="flex items-center gap-2">
							<Input
								id={fieldId}
								value={name}
								onChange={(event) => setName(event.target.value)}
								placeholder={editing ? undefined : "New group name…"}
								autoComplete="off"
								disabled={pending}
								aria-describedby={helpId}
							/>
							<GroupVisualPicker
								describedBy={helpId}
								name={trimmed}
								icon={icon}
								color={color}
								onChange={(patch) => {
									if (patch.icon !== undefined) {
										setIcon(patch.icon);
									}
									if (patch.color !== undefined) {
										setColor(patch.color);
									}
								}}
								disabled={pending}
							/>
						</div>
						<FieldDescription id={helpId}>
							The icon and color appear on this group’s chip. Groups use a neutral appearance until
							you choose one.
						</FieldDescription>
					</Field>
				</FieldGroup>
			</DialogBody>
			<DialogFooter>
				<DialogClose render={<Button type="button" variant="outline" disabled={pending} />}>
					Cancel
				</DialogClose>
				<Button type="submit" className="min-w-20" disabled={pending || trimmed.length === 0}>
					{pending && <Spinner className="size-4" aria-hidden />}
					{submitLabel}
				</Button>
			</DialogFooter>
		</DialogForm>
	);
}
