import { CircleAlert } from "lucide-react";

import type { GroupDefinition, PracticeDefinition } from "@/api/types.gen";
import {
	definitionDifferences,
	PRACTICE_DEFINITION_FIELDS,
} from "@/components/admin/practices/practice-definition-fields";
import { PracticeDefinitionFieldValue } from "@/components/admin/practices/PracticeDefinitionFieldValue";
import type { PanelState } from "@/components/common/panel-state";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import {
	AlertDialog,
	AlertDialogAction,
	AlertDialogCancel,
	AlertDialogContent,
	AlertDialogDescription,
	AlertDialogFooter,
	AlertDialogHeader,
	AlertDialogTitle,
} from "@/components/ui/alert-dialog";
import { Skeleton } from "@/components/ui/skeleton";
import { Spinner } from "@/components/ui/spinner";
import {
	Table,
	TableBody,
	TableCell,
	TableHead,
	TableHeader,
	TableRow,
} from "@/components/ui/table";

export type ResetComparison =
	| { kind: "practice"; current: PracticeDefinition; offered: PracticeDefinition }
	| { kind: "group"; current: GroupDefinition; offered: GroupDefinition };

export type ResetToCatalogState = PanelState<{ comparison: ResetComparison }>;

export interface ResetToCatalogDialogProps {
	open: boolean;
	onOpenChange: (open: boolean) => void;
	name: string;
	state: ResetToCatalogState;
	pending: boolean;
	stale: boolean;
	onConfirm: () => void;
}

const GROUP_FIELDS = [
	{ key: "name", label: "Name" },
	{ key: "description", label: "Description" },
	{ key: "icon", label: "Icon" },
	{ key: "color", label: "Colour" },
] as const satisfies readonly { key: keyof GroupDefinition; label: string }[];

interface Difference {
	label: string;
	current: React.ReactNode;
	offered: React.ReactNode;
}

function differences(comparison: ResetComparison): Difference[] {
	if (comparison.kind === "practice") {
		const { current, offered } = comparison;
		return definitionDifferences(current, offered).map((field) => ({
			label: PRACTICE_DEFINITION_FIELDS[field].label,
			current: <PracticeDefinitionFieldValue definition={current} field={field} />,
			offered: <PracticeDefinitionFieldValue definition={offered} field={field} />,
		}));
	}
	const { current, offered } = comparison;
	return GROUP_FIELDS.filter(({ key }) => current[key] !== offered[key]).map(({ key, label }) => ({
		label,
		current: <span className="text-xs">{current[key] ?? "Not set"}</span>,
		offered: <span className="text-xs">{offered[key] ?? "Not set"}</span>,
	}));
}

export function ResetToCatalogDialog({
	open,
	onOpenChange,
	name,
	state,
	pending,
	stale,
	onConfirm,
}: ResetToCatalogDialogProps) {
	const ready = state.status === "ready" ? state : undefined;
	const rows = ready ? differences(ready.comparison) : [];
	const practice = ready?.comparison.kind !== "group";
	return (
		<AlertDialog open={open} onOpenChange={onOpenChange}>
			<AlertDialogContent className="data-[size=default]:sm:max-w-3xl">
				<AlertDialogHeader>
					<AlertDialogTitle>Reset “{name}” to the catalog?</AlertDialogTitle>
					<AlertDialogDescription>
						{practice
							? "The catalog’s version is saved as a new version of this practice. Earlier versions and their observations stay."
							: "The catalog’s details replace this group’s. Its practices stay where they are."}
					</AlertDialogDescription>
				</AlertDialogHeader>

				<div className="max-h-[60vh] min-w-0 space-y-3 overflow-auto">
					{stale && (
						<Alert variant="warning" role="alert">
							<CircleAlert />
							<AlertTitle>The catalog or this workspace changed</AlertTitle>
							<AlertDescription>
								This is the current comparison. Nothing was reset.
							</AlertDescription>
						</Alert>
					)}
					{state.status === "loading" && (
						<div className="space-y-2" aria-hidden>
							<Skeleton className="h-8 w-full" />
							<Skeleton className="h-16 w-full" />
							<Skeleton className="h-16 w-full" />
						</div>
					)}
					{state.status === "error" && (
						<QueryErrorAlert
							error={state.error}
							title="We could not load the catalog’s version"
							onRetry={state.onRetry}
						/>
					)}
					{ready &&
						(rows.length === 0 ? (
							<p className="text-sm text-muted-foreground">Nothing differs from the catalog.</p>
						) : (
							<Table bordered className="table-fixed">
								<caption className="sr-only">What the reset changes</caption>
								<TableHeader>
									<TableRow variant="static">
										<TableHead scope="col" className="w-32">
											Field
										</TableHead>
										<TableHead scope="col">Yours</TableHead>
										<TableHead scope="col">Catalog</TableHead>
									</TableRow>
								</TableHeader>
								<TableBody>
									{rows.map((row) => (
										<TableRow key={row.label} variant="static">
											<TableHead scope="row" variant="body">
												{row.label}
											</TableHead>
											<TableCell className="align-top whitespace-normal">{row.current}</TableCell>
											<TableCell className="align-top whitespace-normal">{row.offered}</TableCell>
										</TableRow>
									))}
								</TableBody>
							</Table>
						))}
				</div>

				<AlertDialogFooter>
					<AlertDialogCancel disabled={pending}>Keep yours</AlertDialogCancel>
					<AlertDialogAction
						variant="destructive"
						disabled={!ready || rows.length === 0 || pending}
						onClick={onConfirm}
					>
						{pending && <Spinner />}
						{pending ? "Resetting…" : "Reset to catalog"}
					</AlertDialogAction>
				</AlertDialogFooter>
			</AlertDialogContent>
		</AlertDialog>
	);
}
