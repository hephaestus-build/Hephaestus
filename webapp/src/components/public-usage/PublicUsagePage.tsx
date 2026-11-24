import { Link } from "@tanstack/react-router";
import { useEffect, useId, useRef } from "react";

import { cn } from "cn";
import type { PublicUsageFigures, PublicUsagePeriod } from "@/api/types.gen";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import {
	Select,
	SelectContent,
	SelectItem,
	SelectTrigger,
	SelectValue,
} from "@/components/ui/select";
import { Skeleton } from "@/components/ui/skeleton";
import { formatCalendarDate } from "@/lib/dates";
import { formatNumber } from "@/lib/numbers";

export type PublicUsagePageState =
	| { status: "loading" }
	| { status: "error"; error: unknown; onRetry: () => void }
	/** The operator has not turned the page on, or turned it off. */
	| { status: "unavailable" }
	| {
			status: "ready";
			/** The released periods, newest first, as the server sends them. */
			periods: PublicUsagePeriod[];
			/** The key of the period on screen, such as `2026-04`. */
			selected: string;
			onSelectPeriod: (period: string) => void;
	  };

export interface PublicUsagePageProps {
	state: PublicUsagePageState;
}

type FigureKey = keyof PublicUsageFigures;

/** The five figures, in reading order, each with its one-line definition. */
const FIGURES: { key: FigureKey; label: string; definition: string }[] = [
	{
		key: "workspaces",
		label: "Workspaces",
		definition: "Workspaces with at least one practice review created in this half-year.",
	},
	{
		key: "reviewedPeople",
		label: "Developers whose work was reviewed",
		definition:
			"Different people with at least one observation recorded in this half-year, each counted once.",
	},
	{
		key: "reviewedWork",
		label: "Reviewed work",
		definition:
			"Pull requests and merge requests with at least one observation in a review created in this half-year. Each counts once for each workspace in the half-year. An observation reused from an earlier review counts too.",
	},
	{
		key: "practiceReviews",
		label: "Practice reviews",
		definition: "One practice in one review created in this half-year, whether it finished or not.",
	},
	{
		key: "feedbackDelivered",
		label: "Practice feedback delivered",
		definition:
			"Practice feedback delivered in this half-year: on the work, on a practice page, or in a conversation.",
	},
];

const NOT_PUBLISHED = "Not published";

const NOT_ENOUGH_ACTIVITY = "Not enough activity to publish.";

const MONTH = new Intl.DateTimeFormat("en-GB", { month: "long", timeZone: "UTC" });
const SHORT_MONTH = new Intl.DateTimeFormat("en-GB", { month: "short", timeZone: "UTC" });

/**
 * "October 2025 – March 2026" or "April – September 2026". Written by hand: `formatRange` puts thin spaces around
 * the dash in some engines, so the label would differ between browsers.
 */
export function periodLabel(period: PublicUsagePeriod): string {
	const firstYear = period.firstDay.getUTCFullYear();
	const lastYear = period.lastDay.getUTCFullYear();
	const first = MONTH.format(period.firstDay);
	const last = MONTH.format(period.lastDay);
	return firstYear === lastYear
		? `${first} – ${last} ${lastYear}`
		: `${first} ${firstYear} – ${last} ${lastYear}`;
}

/** The short form of a period, for the ends of a chart: "Apr – Sep 2026". */
function shortPeriodLabel(period: PublicUsagePeriod): string {
	const firstYear = period.firstDay.getUTCFullYear();
	const lastYear = period.lastDay.getUTCFullYear();
	const first = SHORT_MONTH.format(period.firstDay);
	const last = SHORT_MONTH.format(period.lastDay);
	return firstYear === lastYear
		? `${first} – ${last} ${lastYear}`
		: `${first} ${firstYear} – ${last} ${lastYear}`;
}

/** A figure as the history list writes it. */
function shown(value: number | undefined): string {
	return value === undefined ? NOT_PUBLISHED : `at least ${formatNumber(value)}`;
}

/** A published figure, or undefined when the period or the figure is not published. */
function figureOf(period: PublicUsagePeriod, key: FigureKey): number | undefined {
	return period.status === "PUBLISHED" ? (period.figures[key] ?? undefined) : undefined;
}

/** Hephaestus in numbers: the released half-years of this server, as rounded totals. */
export function PublicUsagePage({ state }: PublicUsagePageProps) {
	return (
		<div className="mx-auto flex w-full max-w-5xl flex-col gap-8">
			<header className="flex flex-col gap-2">
				<h1 className="text-3xl font-semibold tracking-tight">Hephaestus in numbers</h1>
				<p className="max-w-prose text-muted-foreground">
					Practice feedback on this server, released once each half-year. Totals only. The page
					names no person and no workspace.
				</p>
			</header>
			{state.status === "loading" && <PublicUsageSkeleton />}
			{state.status === "error" && (
				<QueryErrorAlert
					error={state.error}
					title="We could not load the figures"
					onRetry={state.onRetry}
				/>
			)}
			{state.status === "unavailable" && (
				<p className="text-muted-foreground">This server does not publish its figures.</p>
			)}
			{state.status === "ready" && <ReleasedPeriods {...state} />}
			<footer className="border-t pt-4 text-sm text-muted-foreground">
				A half-year is due 14 days after it ends, and its figures are computed once when it is
				released. An administrator can withdraw a figure, but no figure is ever changed. Figures are
				rounded down, and trials are not counted. A half-year shows figures only if at least 5
				workspaces and 5 developers took part. No single workspace may make up more than a third of
				a figure.{" "}
				<Link
					to="/privacy"
					className="font-medium underline underline-offset-4 hover:text-foreground"
				>
					Read the privacy notice
				</Link>
				.
			</footer>
		</div>
	);
}

function ReleasedPeriods({
	periods,
	selected,
	onSelectPeriod,
}: Extract<PublicUsagePageState, { status: "ready" }>) {
	const period = periods.find((candidate) => candidate.period === selected) ?? periods[0];
	if (period === undefined) {
		return (
			<p className="text-muted-foreground">
				No half-year is released yet. A half-year appears here after it is due and released.
			</p>
		);
	}
	const items = periods.map((candidate) => ({
		value: candidate.period,
		label: periodLabel(candidate),
	}));
	// Oldest first, so time runs left to right in every series.
	const series = [...periods].reverse();
	return (
		<section aria-labelledby="usage-period" className="flex flex-col gap-6">
			<div className="flex flex-col gap-3 sm:flex-row sm:items-end sm:justify-between">
				<div className="flex flex-col gap-1">
					<h2 id="usage-period" className="text-xl font-semibold">
						{periodLabel(period)}
					</h2>
					<p className="text-sm text-muted-foreground">
						Released {formatCalendarDate(period.releasedOn)}
					</p>
				</div>
				{periods.length > 1 && (
					<Select
						items={items}
						value={period.period}
						onValueChange={(value) => {
							if (value !== null && value !== "") {
								onSelectPeriod(value);
							}
						}}
					>
						<SelectTrigger className="w-full sm:w-72" aria-label="Half-year">
							<SelectValue />
						</SelectTrigger>
						<SelectContent aria-label="Half-year">
							{items.map((item) => (
								<SelectItem key={item.value} value={item.value}>
									{item.label}
								</SelectItem>
							))}
						</SelectContent>
					</Select>
				)}
			</div>
			{period.status === "NOT_ENOUGH_ACTIVITY" ? (
				<p className="rounded-lg border p-4 text-muted-foreground">{NOT_ENOUGH_ACTIVITY}</p>
			) : (
				<dl className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
					{FIGURES.map((figure) => (
						<UsageFigure
							key={figure.key}
							label={figure.label}
							definition={figure.definition}
							value={figureOf(period, figure.key)}
							series={series.length > 1 ? series : undefined}
							figureKey={figure.key}
							selected={period.period}
						/>
					))}
				</dl>
			)}
		</section>
	);
}

interface UsageFigureProps {
	label: string;
	definition: string;
	value: number | undefined;
	/** Every released period, oldest first, as the bars and the list show them; only when two or more are released. */
	series: PublicUsagePeriod[] | undefined;
	figureKey: FigureKey;
	selected: string;
}

function UsageFigure({ label, definition, value, series, figureKey, selected }: UsageFigureProps) {
	return (
		<div className="flex flex-col gap-2 rounded-xl border bg-card p-4">
			<dt className="text-sm font-medium">{label}</dt>
			<dd className="flex flex-col gap-2">
				{value === undefined ? (
					<span className="text-2xl font-semibold text-muted-foreground">{NOT_PUBLISHED}</span>
				) : (
					<span className="flex items-baseline gap-1.5">
						<span className="text-sm text-muted-foreground">at least</span>
						<span className="text-3xl font-semibold tabular-nums">{formatNumber(value)}</span>
					</span>
				)}
				<span className="text-sm text-muted-foreground">{definition}</span>
				{series !== undefined && (
					<UsageSeries label={label} series={series} figureKey={figureKey} selected={selected} />
				)}
			</dd>
		</div>
	);
}

interface UsageSeriesProps {
	label: string;
	series: PublicUsagePeriod[];
	figureKey: FigureKey;
	selected: string;
}

/**
 * One small bar for each released half-year. A half-year without this figure is a hatched gap at full
 * height, never a bar of zero.
 */
function UsageSeries({ label, series, figureKey, selected }: UsageSeriesProps) {
	const values = series.map((period) => figureOf(period, figureKey));
	const largest = Math.max(0, ...values.map((value) => value ?? 0));
	const first = series.at(0);
	const last = series.at(-1);
	return (
		<>
			{/* The bars repeat the list below as a picture, so assistive technology reads the list. */}
			<div aria-hidden className="mt-1 flex h-16 items-end gap-1">
				{series.map((period, index) => {
					const value = values[index];
					return (
						<div key={period.period} className="flex h-full flex-1 items-end">
							{value === undefined || largest === 0 ? (
								<div className="relative h-full w-full overflow-hidden rounded-sm border border-dashed border-muted-foreground text-muted-foreground">
									<Hatch />
								</div>
							) : (
								<div
									className={cn(
										"h-(--bar) w-full rounded-sm",
										period.period === selected ? "bg-foreground" : "bg-muted-foreground",
									)}
									style={{ "--bar": `${Math.max(4, (value / largest) * 100)}%` }}
								/>
							)}
						</div>
					);
				})}
			</div>
			{/* The selected half-year is marked below its column, so a hidden figure and a pale bar show it too. */}
			<div aria-hidden className="flex gap-1">
				{series.map((period) => (
					<div
						key={period.period}
						className={cn("h-1 flex-1 rounded-full", period.period === selected && "bg-foreground")}
					/>
				))}
			</div>
			{first !== undefined && last !== undefined && (
				<div aria-hidden className="flex justify-between gap-4 text-xs text-muted-foreground">
					<span>{shortPeriodLabel(first)}</span>
					<span>{shortPeriodLabel(last)}</span>
				</div>
			)}
			<details className="text-sm">
				<summary className="cursor-pointer text-muted-foreground hover:text-foreground">
					Each half-year<span className="sr-only">: {label}</span>
				</summary>
				{/* Native markers keep the list a list in WebKit; the row layout lives inside each item. */}
				<ul aria-label={`${label} by half-year`} className="mt-2 list-disc space-y-1 pl-5">
					{series.map((period) => (
						<li
							key={period.period}
							aria-current={period.period === selected ? "true" : undefined}
							className={cn(period.period === selected && "font-medium")}
						>
							<span className="flex justify-between gap-4">
								<span>{periodLabel(period)}</span>
								<span className="tabular-nums">
									{period.status === "NOT_ENOUGH_ACTIVITY"
										? NOT_ENOUGH_ACTIVITY
										: shown(figureOf(period, figureKey))}
								</span>
							</span>
						</li>
					))}
				</ul>
			</details>
		</>
	);
}

/**
 * Diagonal stripes in the border colour, filling a half-year that does not publish the figure, so the gap never reads
 * as a bar of zero.
 */
function Hatch() {
	const id = useId();
	return (
		<svg aria-hidden className="absolute inset-0 size-full" preserveAspectRatio="none">
			<defs>
				<pattern
					id={id}
					width="6"
					height="6"
					patternUnits="userSpaceOnUse"
					patternTransform="rotate(45)"
				>
					<line x1="0" y1="0" x2="0" y2="6" stroke="currentColor" strokeWidth="1" />
				</pattern>
			</defs>
			<rect width="100%" height="100%" fill={`url(#${id})`} />
		</svg>
	);
}

/** The shape of a released period while it loads: a heading and five figures. */
function PublicUsageSkeleton() {
	const status = useRef<HTMLParagraphElement>(null);
	// Written one frame after the region mounts, as in PageSkeleton: text already inside a new live region is no
	// change, so it would not be announced.
	useEffect(() => {
		const frame = requestAnimationFrame(() => {
			if (status.current) {
				status.current.textContent = "Loading the figures…";
			}
		});
		return () => cancelAnimationFrame(frame);
	}, []);
	return (
		<div className="flex flex-col gap-6">
			<p ref={status} role="status" className="sr-only" />
			<div className="flex flex-col gap-2" aria-hidden>
				<Skeleton className="h-7 w-64 max-w-full" />
				<Skeleton className="h-4 w-40" />
			</div>
			<div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3" aria-hidden>
				{FIGURES.map((figure) => (
					<Skeleton key={figure.key} className="h-36 rounded-xl" />
				))}
			</div>
		</div>
	);
}
