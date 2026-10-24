import { XIcon } from "lucide-react";
import { useState } from "react";

import { DateRangeFacet, dateRangeLabel } from "@/components/common/DateRangeFacet";
import { FacetMultiSelect, type FacetSource } from "@/components/common/FacetMultiSelect";
import { ReferenceFilterPill } from "@/components/common/ReferenceFilterPill";
import { Button } from "@/components/ui/button";
import { ButtonGroup, ButtonGroupText } from "@/components/ui/button-group";
import { fromDateRange, toDateRange } from "@/lib/date-range-search";
import { itemKindLabel } from "@/lib/item-kinds";
import { nonEmpty } from "@/lib/search-params";

import { AppliedFacetPills, facetPills } from "./AppliedFacetPills";
import { GlobalScopeRow } from "./GlobalScopeRow";
import type { PracticeFacetOption } from "./ResultFilters";
import {
	ORDERED_TABS,
	type ReviewScope,
	type ReviewTab,
	SCOPE_APPLIES,
	type ScopeFacet,
	setScopeFacets,
	type TimeOrder,
} from "./review-scope";
import { type ReviewPeople, ReviewPersonFacet } from "./ReviewPersonFacet";
import { type ReviewSortItem, ReviewSortSelect } from "./ReviewSortSelect";
import { ShowTurnedOffSwitch } from "./ShowTurnedOffSwitch";

export interface ScopeFiltersProps {
	/** The tab the toolbar sits on. It decides which parts of the scope narrow the list. */
	tab: ReviewTab;
	scope: ReviewScope;
	onChange: (patch: Partial<ReviewScope>) => void;
	dateTitle: string;
	groups: FacetSource;
	/** Every practice, with its group. A chosen group narrows what the practice facet offers. */
	practices: FacetSource<PracticeFacetOption>;
	people: ReviewPeople;
	personName?: string;
	/**
	 * The one review, item or result the scope is filtered to, by name — set by a "See
	 * all" link or a link from before. A record picked with a crosshair is the selection bar's instead,
	 * so it is left out here.
	 */
	record?: ScopeRecordFilter;
	/** An ordering this tab adds after the shared time order, as the results list adds "Most actionable first". */
	ownOrder?: OwnOrder;
}

/**
 * An ordering one tab offers after the shared time order. It is no time order, so it stays on its
 * tab: a link to a sibling tab carries the time order alone.
 */
export interface OwnOrder {
	item: ReviewSortItem<string>;
	/** Whether the tab is ordered this way now, in place of the time order. */
	active: boolean;
	onSelect: () => void;
}

export interface ReviewScopeFiltersProps extends ScopeFiltersProps {
	/** Clears every part of the scope, applied or not. */
	onClear: () => void;
	/** What a part that does not narrow this page says about itself. */
	notAppliedNote?: string;
}

/** A filter to one record, as its pill names it. */
export interface ScopeRecordFilter {
	facet: "review" | "item" | "result";
	/** Its name, once read; until then the kind of record stands in. */
	name: string | undefined;
}

const RECORD_TITLES: Record<ScopeRecordFilter["facet"], string> = {
	review: "Review",
	item: "Work",
	result: "Observation",
};

const RECORD_UNNAMED: Record<ScopeRecordFilter["facet"], string> = {
	review: "one review",
	item: "one piece of work",
	result: "one observation",
};

const DEVELOPER_TITLE = "Developer";

const TIME_ORDER_ITEMS: readonly [ReviewSortItem<TimeOrder>, ...ReviewSortItem<TimeOrder>[]] = [
	{ value: "NEWEST", label: "Newest first" },
	{ value: "OLDEST", label: "Oldest first" },
];

interface ScopePill {
	key: string;
	facet: ScopeFacet;
	title: string;
	value: string;
	onClear: () => void;
}

/**
 * The shared time order, after the date it orders by, and the tab's own ordering after it. Choosing a
 * time order leaves the tab's own ordering, as the URL key they share holds one or the other.
 */
function ScopeOrderSelect({
	scope,
	onChange,
	ownOrder,
}: Pick<ScopeFiltersProps, "scope" | "onChange" | "ownOrder">) {
	const items: readonly [ReviewSortItem<string>, ...ReviewSortItem<string>[]] = ownOrder
		? [...TIME_ORDER_ITEMS, ownOrder.item]
		: TIME_ORDER_ITEMS;
	return (
		<ReviewSortSelect
			items={items}
			value={ownOrder?.active === true ? ownOrder.item.value : scope.order}
			onChange={(next) => {
				if (ownOrder !== undefined && next === ownOrder.item.value) {
					ownOrder.onSelect();
				} else {
					onChange({ order: TIME_ORDER_ITEMS.find((item) => item.value === next)?.value });
				}
			}}
		/>
	);
}

/**
 * The scope's own controls, in the order every tab shows them: developer, group, practice, date, and
 * on a list the time order.
 * A part this tab cannot apply has no control; `ScopePills` keeps it visible as a muted pill. The
 * practice facet leaves out the practices turned off today, until the reader asks for them.
 */
function ScopeFacetControls({
	tab,
	scope,
	onChange,
	dateTitle,
	groups,
	practices,
	people,
	personName,
	ownOrder,
}: ScopeFiltersProps) {
	const applies = SCOPE_APPLIES[tab];
	const [showTurnedOff, setShowTurnedOff] = useState(false);
	const chosen = scope.practiceSlug ?? [];
	const inGroups = practicesInGroups(practices.options, scope);
	// A chosen practice stays offered, so a choice that is turned off can still be seen and cleared.
	const practiceOptions = showTurnedOff
		? inGroups
		: inGroups.filter((option) => option.turnedOff !== true || chosen.includes(option.value));
	return (
		<>
			{applies.includes("developer") && (
				<ReviewPersonFacet
					title={DEVELOPER_TITLE}
					people={people}
					selected={scope.developerId}
					onChange={(developerId) => onChange({ developerId })}
					fallbackName={personName}
				/>
			)}
			{applies.includes("group") && (
				<FacetMultiSelect
					title="Group"
					options={groups.options}
					selected={scope.groupSlug ?? []}
					onChange={(values) => onChange({ groupSlug: nonEmpty(values) })}
					disabled={groups.isLoading}
					emptyLabel={groups.isError ? "We could not load groups" : "No groups available"}
				/>
			)}
			{applies.includes("practice") && (
				<FacetMultiSelect
					title="Practice"
					options={practiceOptions}
					selected={chosen}
					onChange={(values) => onChange({ practiceSlug: nonEmpty(values) })}
					disabled={practices.isLoading}
					emptyLabel={practices.isError ? "We could not load practices" : "No practices available"}
					footer={
						inGroups.some((option) => option.turnedOff === true) ? (
							<ShowTurnedOffSwitch
								checked={showTurnedOff}
								onCheckedChange={setShowTurnedOff}
								hidden={inGroups.length - practiceOptions.length}
							/>
						) : undefined
					}
				/>
			)}
			{applies.includes("dates") && (
				<DateRangeFacet
					title={dateTitle}
					value={toDateRange(scope)}
					onChange={(range) => onChange(fromDateRange(range))}
				/>
			)}
			{ORDERED_TABS.includes(tab) && (
				<ScopeOrderSelect scope={scope} onChange={onChange} ownOrder={ownOrder} />
			)}
		</>
	);
}

/**
 * The values of the group and practice facets as pills, for below `sm`, where the facets fold their
 * values into a count.
 */
function scopeFacetPills({
	tab,
	scope,
	onChange,
	groups,
	practices,
}: Pick<ScopeFiltersProps, "tab" | "scope" | "onChange" | "groups" | "practices">) {
	const applies = SCOPE_APPLIES[tab];
	return [
		...(applies.includes("group")
			? facetPills("Group", groups.options, scope.groupSlug, (values) =>
					onChange({ groupSlug: nonEmpty(values) }),
				)
			: []),
		...(applies.includes("practice")
			? facetPills("Practice", practices.options, scope.practiceSlug, (values) =>
					onChange({ practiceSlug: nonEmpty(values) }),
				)
			: []),
	];
}

/**
 * The scope's pills that no control shows: the one record the scope is filtered to, and every part
 * this tab cannot apply, muted, so it is never dropped without a word.
 */
function ScopePills({
	tab,
	scope,
	onChange,
	groups,
	practices,
	people,
	personName,
	record,
	notAppliedNote = "Not applied on this list",
}: ScopeFiltersProps & { notAppliedNote?: string }) {
	const applies = SCOPE_APPLIES[tab];
	const developerLabel =
		people.options.find((person) => person.accountId === scope.developerId)?.label ??
		personName ??
		(scope.developerId === undefined ? undefined : `#${scope.developerId}`);
	const pills = scopePills(scope, { developerLabel, groups, practices }, onChange);
	const recordPill: ScopePill | undefined = record && {
		key: "record",
		facet: record.facet,
		title: RECORD_TITLES[record.facet],
		value: record.name ?? RECORD_UNNAMED[record.facet],
		onClear: () => onChange({ reviewId: undefined, resultId: undefined, itemId: undefined }),
	};
	const referencePills = [
		...referenceScopePills(scope, onChange),
		...(recordPill ? [recordPill] : []),
	];
	const notApplied = [...pills, ...referencePills].filter((pill) => !applies.includes(pill.facet));
	return (
		<>
			{referencePills
				.filter((pill) => applies.includes(pill.facet))
				.map((pill) => (
					<ReferenceFilterPill
						key={pill.key}
						label={pill.title}
						value={pill.value}
						onClear={pill.onClear}
					/>
				))}
			{notApplied.map((pill) => (
				<NotAppliedScopePill key={pill.key} pill={pill} note={notAppliedNote} />
			))}
		</>
	);
}

/**
 * "Clear scope", while any part of the scope holds a value, applied here or not, or a list is ordered
 * oldest first.
 */
function ClearScopeButton({
	tab,
	scope,
	onClear,
}: {
	tab: ReviewTab;
	scope: ReviewScope;
	onClear: () => void;
}) {
	const reordered = ORDERED_TABS.includes(tab) && scope.order === "OLDEST";
	if (setScopeFacets(scope).length === 0 && !reordered) {
		return null;
	}
	return (
		<Button variant="ghost" size="sm" className="h-8" onClick={onClear}>
			Clear scope
			<XIcon aria-hidden data-icon="inline-end" />
		</Button>
	);
}

/**
 * The narrowing that every tab keeps, above the tabs (`GlobalScopeRow`): the scope's facets that this
 * tab applies, "Clear scope", and the scope's pills. The overview applies none of the scope but keeps
 * it for the next tab, so every part shows there as a muted pill.
 */
export function ReviewScopeFilters(props: ReviewScopeFiltersProps) {
	const { tab, scope, onClear, notAppliedNote } = props;
	return (
		<GlobalScopeRow
			empty={SCOPE_APPLIES[tab].length === 0 && setScopeFacets(scope).length === 0}
			controls={
				<>
					<ScopeFacetControls {...props} />
					<ClearScopeButton tab={tab} scope={scope} onClear={onClear} />
				</>
			}
			pills={
				<>
					<AppliedFacetPills pills={scopeFacetPills(props)} />
					<ScopePills {...props} notAppliedNote={notAppliedNote} />
				</>
			}
		/>
	);
}

/**
 * The practices in the chosen groups, and the practices already chosen, so that a choice the groups
 * exclude can still be seen and cleared.
 */
function practicesInGroups(
	options: PracticeFacetOption[],
	scope: ReviewScope,
): PracticeFacetOption[] {
	const groups = scope.groupSlug ?? [];
	if (groups.length === 0) {
		return options;
	}
	const chosen = scope.practiceSlug ?? [];
	return options.filter(
		(option) =>
			(option.groupSlug !== undefined && groups.includes(option.groupSlug)) ||
			chosen.includes(option.value),
	);
}

function scopePills(
	scope: ReviewScope,
	names: {
		developerLabel: string | undefined;
		groups: FacetSource;
		practices: FacetSource;
	},
	onChange: (patch: Partial<ReviewScope>) => void,
): ScopePill[] {
	const pills: ScopePill[] = [];
	if (names.developerLabel !== undefined) {
		pills.push({
			key: "developer",
			facet: "developer",
			title: DEVELOPER_TITLE,
			value: names.developerLabel,
			onClear: () => onChange({ developerId: undefined }),
		});
	}
	for (const pill of facetPills("Group", names.groups.options, scope.groupSlug, (values) =>
		onChange({ groupSlug: nonEmpty(values) }),
	)) {
		pills.push({ ...pill, facet: "group", value: pill.label });
	}
	for (const pill of facetPills("Practice", names.practices.options, scope.practiceSlug, (values) =>
		onChange({ practiceSlug: nonEmpty(values) }),
	)) {
		pills.push({ ...pill, facet: "practice", value: pill.label });
	}
	const range = toDateRange(scope);
	const rangeText = range === undefined ? undefined : dateRangeLabel(range);
	if (rangeText !== undefined) {
		pills.push({
			key: "dates",
			facet: "dates",
			title: "Dates",
			value: rangeText,
			onClear: () => onChange({ from: undefined, to: undefined }),
		});
	}
	return pills;
}

function referenceScopePills(
	scope: ReviewScope,
	onChange: (patch: Partial<ReviewScope>) => void,
): ScopePill[] {
	const pills: ScopePill[] = [];
	if (scope.itemKind !== undefined) {
		pills.push({
			key: "kind",
			facet: "kind",
			title: "Kind",
			value: itemKindLabel(scope.itemKind, 2),
			onClear: () => onChange({ itemKind: undefined }),
		});
	}
	if (scope.problemId !== undefined) {
		pills.push({
			key: "problem",
			facet: "problem",
			title: "Problem",
			value: "one problem",
			onClear: () => onChange({ problemId: undefined }),
		});
	}
	return pills;
}

/** A part of the scope that this list cannot filter by, kept for the next tab. */
function NotAppliedScopePill({
	pill: { title, value, onClear },
	note,
}: {
	pill: ScopePill;
	note: string;
}) {
	const shown = `${title}: ${value}`;
	return (
		<ButtonGroup>
			<ButtonGroupText className="h-8 max-w-[70vw] min-w-0 text-muted-foreground sm:max-w-sm">
				<span className="truncate" title={shown}>
					{shown}
				</span>
				<span className="shrink-0 text-xs">{note}</span>
			</ButtonGroupText>
			<Button
				variant="outline"
				size="sm"
				className="h-8"
				aria-label={`Clear ${title.toLowerCase()} from the scope (${value})`}
				onClick={onClear}
			>
				<XIcon aria-hidden />
			</Button>
		</ButtonGroup>
	);
}
