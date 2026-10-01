import { PeopleIcon, SearchIcon } from "@primer/octicons-react";
import { useState } from "react";

import { cn } from "cn";
import type { MemberActivity } from "@/api/types.gen";
import { InlineLink } from "@/components/common/InlineLink";
import type { PanelState } from "@/components/common/panel-state";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { DetailStackLink } from "@/components/layout/detail-drawer/DetailStackLink";
import { Button } from "@/components/ui/button";
import { Empty, EmptyDescription, EmptyHeader, EmptyTitle } from "@/components/ui/empty";
import { InputGroup, InputGroupAddon, InputGroupInput } from "@/components/ui/input-group";
import { Skeleton } from "@/components/ui/skeleton";
import {
	Table,
	TableBody,
	TableCell,
	TableHead,
	TableHeader,
	TableRow,
} from "@/components/ui/table";
import type { ProviderType } from "@/lib/provider/provider-terms";

import { ActionChips } from "./ActionChip";
import {
	ACTIVITY_CATEGORIES,
	ACTIVITY_CATEGORY_DEFS,
	hasActivity,
	summaryActions,
} from "./activity-kind-defs";
import { memberLevel } from "./activity-search";
import { STALE } from "./activity-tones";
import { ActivityEmpty } from "./ActivityEmpty";
import { MemberAvatar } from "./MemberAvatar";

/**
 * The members once they are in, and whether they are the previous range's, standing in while the
 * range just chosen loads.
 */
export type MemberActivityState = PanelState<{ members: MemberActivity[]; stale: boolean }>;

export interface MemberActivityTableProps {
	state: MemberActivityState;
	providerType: ProviderType;
}

/** NN/g's default page before "View all": enough to find a name by scrolling, few enough to render at once. */
const FIRST_ROWS = 50;

const SKELETON_ROWS = 6;

/** "Zoë" and "ZOE" find "zoe": the search folds case and accents, as a name typed from memory does. */
const fold = (text: string): string =>
	text
		.normalize("NFD")
		.replaceAll(/\p{Mn}/gu, "")
		.toLowerCase();

/**
 * Who is carrying what, one row per member in the server's order, which is by name, and never by a
 * count: the table cannot be sorted into a ranking, and each cell is that member's own chips, not a
 * bar on a shared scale. A search finds a name among hundreds; a row opens the member's level.
 */
export function MemberActivityTable({ state, providerType }: MemberActivityTableProps) {
	const [query, setQuery] = useState("");
	const [showAll, setShowAll] = useState(false);
	if (state.status === "error") {
		return (
			<QueryErrorAlert error={state.error} title="Couldn't load members" onRetry={state.onRetry} />
		);
	}
	if (state.status === "ready" && state.members.length === 0) {
		return <ActivityEmpty icon={<PeopleIcon />} title="No members here" />;
	}
	const members = state.status === "ready" ? state.members : [];
	const needle = fold(query.trim());
	const found =
		needle === ""
			? members
			: members.filter(({ user }) =>
					[user.name, user.login].some((text) => fold(text).includes(needle)),
				);
	const stale = state.status === "ready" && state.stale;
	const shown = showAll ? found : found.slice(0, FIRST_ROWS);
	const active = members.filter((member) => hasActivity(member.summary)).length;
	return (
		<div className="space-y-3">
			<div className="flex flex-wrap items-center gap-x-4 gap-y-2">
				<InputGroup className="w-full sm:w-72">
					<InputGroupAddon>
						<SearchIcon />
					</InputGroupAddon>
					<InputGroupInput
						type="search"
						placeholder="Search members"
						aria-label="Search members"
						value={query}
						onChange={(event) => setQuery(event.target.value)}
					/>
				</InputGroup>
				{state.status === "ready" && (
					<p aria-live="polite" className="text-sm text-muted-foreground tabular-nums">
						{needle === ""
							? `${members.length} ${members.length === 1 ? "member" : "members"} · ${active} active`
							: `${found.length} of ${members.length} members`}
					</p>
				)}
			</div>
			<Table
				bordered
				aria-label="Members"
				aria-busy={state.status === "loading" || stale || undefined}
				className={cn("min-w-160", stale && STALE)}
			>
				<TableHeader>
					<TableRow variant="static">
						<TableHead className="w-64 px-3">Member</TableHead>
						{ACTIVITY_CATEGORIES.map((category) => {
							const def = ACTIVITY_CATEGORY_DEFS[category];
							const Icon = def.icon(providerType);
							return (
								<TableHead key={category} className="px-3">
									<span className="inline-flex items-center gap-1.5">
										<Icon size={16} className="shrink-0 text-muted-foreground" />
										{def.label(providerType)}
									</span>
								</TableHead>
							);
						})}
					</TableRow>
				</TableHeader>
				<TableBody>
					{state.status === "loading" &&
						Array.from({ length: SKELETON_ROWS }, (_, index) => (
							<TableRow key={index} variant="static" aria-hidden>
								<TableCell className="px-3">
									<div className="flex items-center gap-3">
										<Skeleton className="size-8 rounded-full" />
										<div className="space-y-1.5">
											<Skeleton className="h-4 w-28" />
											<Skeleton className="h-3 w-16" />
										</div>
									</div>
								</TableCell>
								{ACTIVITY_CATEGORIES.map((category) => (
									<TableCell key={category} className="px-3">
										<Skeleton className="h-4 w-16" />
									</TableCell>
								))}
							</TableRow>
						))}
					{state.status === "ready" && found.length === 0 && (
						<TableRow variant="static">
							<TableCell colSpan={ACTIVITY_CATEGORIES.length + 1} className="p-4 whitespace-normal">
								<Empty>
									<EmptyHeader>
										<EmptyTitle>No results found</EmptyTitle>
										<EmptyDescription>Edit your search and try again.</EmptyDescription>
									</EmptyHeader>
								</Empty>
							</TableCell>
						</TableRow>
					)}
					{shown.map((member) => (
						<MemberRow key={member.user.id} member={member} providerType={providerType} />
					))}
				</TableBody>
			</Table>
			{!showAll && found.length > FIRST_ROWS && (
				<Button variant="outline" size="sm" onClick={() => setShowAll(true)}>
					Show all {found.length}
				</Button>
			)}
		</div>
	);
}

/**
 * The member's name is the row's link and its keyboard stop, stretched over the row so the whole
 * row is the pointer target; the chips sit above the stretch so their tooltips still answer.
 */
function MemberRow({
	member,
	providerType,
}: {
	member: MemberActivity;
	providerType: ProviderType;
}) {
	const { user, summary } = member;
	return (
		<TableRow className="relative">
			<TableCell className="px-3">
				<div className="flex min-w-0 items-center gap-3">
					<MemberAvatar user={user} />
					<div className="min-w-0">
						<InlineLink
							render={<DetailStackLink entry={memberLevel(user.login)} />}
							className="block truncate font-medium after:absolute after:inset-0"
						>
							{user.name}
						</InlineLink>
						<p className="truncate text-xs text-muted-foreground">{user.login}</p>
					</div>
				</div>
			</TableCell>
			{ACTIVITY_CATEGORIES.map((category) => {
				const actions = summaryActions(summary, ACTIVITY_CATEGORY_DEFS[category].kinds);
				return (
					<TableCell key={category} className="px-3">
						{actions.length > 0 ? (
							<ActionChips
								actions={actions}
								providerType={providerType}
								display="count"
								className="relative z-10 w-fit flex-nowrap"
							/>
						) : (
							<>
								<span aria-hidden className="text-muted-foreground">
									—
								</span>
								<span className="sr-only">None</span>
							</>
						)}
					</TableCell>
				);
			})}
		</TableRow>
	);
}
