import { RotateCcw } from "lucide-react";
import { useId } from "react";

import { cn } from "cn";
import { Button } from "@/components/ui/button";
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from "@/components/ui/collapsible";

import {
	type CatalogEntryStatus,
	canKeepCurrentDefinition,
	canUseHephaestusVersion,
	catalogEntryCopy,
} from "./catalog-entry-state";
import type { VersionField } from "./hephaestus-version-fields";

export interface HephaestusVersionPanelProps {
	status: CatalogEntryStatus;
	kind: "practice" | "group";
	shipped?: readonly VersionField[];
	isResetPending: boolean;
	isKeepPending?: boolean;
	disabled: boolean;
	onUseHephaestusVersion?: () => void;
	onKeepCurrentDefinition?: () => void;
}

export function HephaestusVersionPanel({
	status,
	kind,
	shipped,
	isResetPending,
	isKeepPending = false,
	disabled,
	onUseHephaestusVersion,
	onKeepCurrentDefinition,
}: HephaestusVersionPanelProps) {
	const headingId = useId();
	const copy = catalogEntryCopy(status, kind);
	const canReset = canUseHephaestusVersion(status) && onUseHephaestusVersion !== undefined;
	const canKeep = canKeepCurrentDefinition(status) && onKeepCurrentDefinition !== undefined;
	const busy = isResetPending || isKeepPending || disabled;
	const updateAvailable = status.state === "UPDATE_WAITING";
	const viewLabel = updateAvailable ? "Review Hephaestus update" : "View Hephaestus default";
	const useLabel = updateAvailable ? "Apply Hephaestus update" : "Restore Hephaestus default";
	const usePendingLabel = updateAvailable ? "Applying update…" : "Restoring default…";
	const keepLabel =
		status.state === "NO_LONGER_SHIPPED" ? "Keep saved version as custom" : "Keep saved version";

	return (
		<section
			aria-labelledby={headingId}
			className={cn(
				"rounded-lg border p-4 text-sm",
				copy.tone === "attention" ? "border-warning/50 bg-warning/5" : "bg-card",
			)}
		>
			<div className="flex items-start gap-3">
				<RotateCcw className="mt-0.5 size-4 shrink-0 text-muted-foreground" aria-hidden />
				<div className="min-w-0 flex-1">
					<h2 id={headingId} className="font-medium">
						{copy.label}
					</h2>
					<p className="mt-1 text-muted-foreground">{copy.detail}</p>

					{shipped && (
						<Collapsible className="mt-3 w-full">
							<CollapsibleTrigger
								render={
									<Button type="button" variant="outline" size="sm">
										{viewLabel}
									</Button>
								}
							/>
							<CollapsibleContent
								render={<dl />}
								className="mt-2 space-y-3 rounded-md border bg-muted/40 p-3"
							>
								{shipped.map(({ label, value, mono }) => (
									<div key={label} className="space-y-1">
										<dt className="text-xs font-medium">{label}</dt>
										<dd
											className={cn(
												"text-xs break-words whitespace-pre-wrap text-muted-foreground",
												mono === true && "font-mono",
											)}
										>
											{value}
										</dd>
									</div>
								))}
							</CollapsibleContent>
						</Collapsible>
					)}

					{(canReset || canKeep) && (
						<div className="mt-3 flex flex-wrap items-center gap-2">
							{canReset && (
								<Button
									pending={isResetPending}
									type="button"
									variant="outline"
									size="sm"
									disabled={busy}
									onClick={onUseHephaestusVersion}
								>
									{isResetPending ? usePendingLabel : useLabel}
								</Button>
							)}
							{canKeep && (
								<Button
									pending={isKeepPending}
									type="button"
									variant="ghost"
									size="sm"
									disabled={busy}
									onClick={onKeepCurrentDefinition}
								>
									{isKeepPending ? "Keeping saved version…" : keepLabel}
								</Button>
							)}
						</div>
					)}
				</div>
			</div>
		</section>
	);
}
