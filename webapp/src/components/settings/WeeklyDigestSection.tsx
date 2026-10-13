import { ChevronsUpDownIcon } from "lucide-react";
import { useId } from "react";
import { useSpinDelay } from "spin-delay";

import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import {
	Combobox,
	ComboboxContent,
	ComboboxEmpty,
	ComboboxIcon,
	ComboboxItem,
	ComboboxItemIndicator,
	ComboboxList,
	ComboboxSearchInput,
	ComboboxTrigger,
	useComboboxFilter,
} from "@/components/ui/combobox";
import {
	Field,
	FieldContent,
	FieldDescription,
	FieldGroup,
	FieldLabel,
} from "@/components/ui/field";
import { Skeleton } from "@/components/ui/skeleton";
import { Spinner } from "@/components/ui/spinner";
import { Switch } from "@/components/ui/switch";

export type WeeklyDigestSectionState =
	| { status: "loading" }
	| { status: "error"; error: unknown; onRetry: () => void }
	| {
			status: "ready";
			enabled: boolean;
			/** Heph can answer in at least one of the account's workspaces. */
			available: boolean;
			/** The account's IANA time zone; absent means UTC. */
			timeZone?: string;
			zones: readonly string[];
			isPending: boolean;
			onToggle: (enabled: boolean) => void;
			onChangeTimeZone: (timeZone: string) => void;
	  };

export interface WeeklyDigestSectionProps {
	state: WeeklyDigestSectionState;
}

/** Whether the settings page offers the section: a digest can be written, or one is on to turn off. */
export function hasWeeklyDigest(state: WeeklyDigestSectionState): boolean {
	return state.status !== "ready" || state.available || state.enabled;
}

/**
 * The weekly digest: off until the developer turns it on, for every workspace where Heph can
 * answer them. The time zone decides when the digest arrives.
 */
export function WeeklyDigestSection({ state }: WeeklyDigestSectionProps) {
	const id = useId();
	const { contains } = useComboboxFilter({ sensitivity: "base" });
	const showSpinner = useSpinDelay(state.status === "ready" && state.isPending, {
		delay: 1000,
		minDuration: 500,
	});
	const busy = state.status === "ready" && (state.isPending || showSpinner);
	return (
		<section className="space-y-4" aria-labelledby={`${id}-heading`}>
			<div className="space-y-1">
				<div className="flex flex-wrap items-center gap-x-3 gap-y-1">
					<h2 id={`${id}-heading`} className="text-xl font-semibold">
						Weekly digest
					</h2>
					<p role="status" className="flex items-center gap-2 text-sm text-muted-foreground">
						{showSpinner && (
							<>
								<Spinner />
								Saving…
							</>
						)}
					</p>
				</div>
				<p className="text-sm text-muted-foreground">
					Each Monday at 09:00 in your time zone, Hephaestus adds a weekly digest to your
					conversation with Heph. It comes in each workspace where something happened. It lists the
					feedback that reached you and what changed on your Practice profile. A short note tells
					you it is ready, without its contents. It comes in Slack, or by email if you have not
					connected Slack there and Hephaestus can email you. Otherwise, the digest waits for you in
					Hephaestus. Quiet weeks send nothing.
				</p>
			</div>
			{state.status === "loading" && (
				<div className="flex items-center gap-4" aria-hidden>
					<div className="flex-1 space-y-2">
						<Skeleton className="h-4 w-32" />
						<Skeleton className="h-10 w-full" />
					</div>
					<Skeleton className="h-5 w-8" />
				</div>
			)}
			{state.status === "error" && (
				<QueryErrorAlert
					title="We could not load your weekly digest choice"
					error={state.error}
					onRetry={state.onRetry}
				/>
			)}
			{state.status === "ready" && (
				<FieldGroup>
					{!state.available && (
						<p className="text-sm text-muted-foreground">
							Heph cannot answer you in any of your workspaces now, so no weekly digest is written.
							{state.enabled && " You can still turn it off."}
						</p>
					)}
					<Field orientation="horizontal">
						<FieldContent>
							<FieldLabel htmlFor={`${id}-switch`}>Weekly digest</FieldLabel>
							<FieldDescription id={`${id}-switch-description`}>
								Off until you turn it on. You can also turn it off from its Slack note or email.
							</FieldDescription>
						</FieldContent>
						<Switch
							id={`${id}-switch`}
							aria-describedby={`${id}-switch-description`}
							checked={state.enabled}
							disabled={busy || (!state.available && !state.enabled)}
							aria-busy={state.isPending}
							onCheckedChange={state.onToggle}
						/>
					</Field>
					<Field orientation="responsive">
						<FieldContent>
							<FieldLabel htmlFor={`${id}-zone`}>Time zone</FieldLabel>
							<FieldDescription id={`${id}-zone-description`}>
								Your weekly digest arrives on Monday at 09:00 in this time zone.
							</FieldDescription>
						</FieldContent>
						<Combobox
							items={state.zones}
							value={state.timeZone ?? "UTC"}
							onValueChange={(zone) => {
								if (zone !== null && zone !== state.timeZone) {
									state.onChangeTimeZone(zone);
								}
							}}
							disabled={busy}
							filter={(zone, query) => contains(zone, query, (value) => value.replaceAll("_", " "))}
						>
							<ComboboxTrigger
								id={`${id}-zone`}
								aria-describedby={`${id}-zone-description`}
								className="w-full justify-between font-normal @md/field-group:w-56"
							>
								<span className="truncate">{state.timeZone ?? "UTC"}</span>
								<ComboboxIcon
									render={<ChevronsUpDownIcon className="size-4 shrink-0 opacity-50" />}
								/>
							</ComboboxTrigger>
							<ComboboxContent align="start" className="min-w-72" aria-label="Time zones">
								<ComboboxSearchInput
									placeholder="Search time zones…"
									aria-label="Search time zones"
								/>
								<ComboboxEmpty>No time zones found.</ComboboxEmpty>
								<ComboboxList aria-label="Time zones">
									{(zone: string) => (
										<ComboboxItem key={zone} value={zone}>
											<span className="truncate">{zone}</span>
											<ComboboxItemIndicator />
										</ComboboxItem>
									)}
								</ComboboxList>
							</ComboboxContent>
						</Combobox>
					</Field>
				</FieldGroup>
			)}
		</section>
	);
}
