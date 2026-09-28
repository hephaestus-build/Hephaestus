"use client";

import {
	type ComponentProps,
	type ComponentType,
	createContext,
	type ReactNode,
	useContext,
	useId,
} from "react";
import * as RechartsPrimitive from "recharts";
import type { TooltipValueType } from "recharts";

import { cn } from "cn";

/**
 * ⚠️ Diverges from the shadcn registry — `shadcn add chart` drops the following; re-apply them.
 *
 * 1. No `ChartStyle`. Upstream injects a `<style>` element that writes a `--color-<key>` per config
 *    entry, which `shadcn/no-inline-styles` and `react/no-danger` refuse. A config's `color` is a
 *    theme token instead — `var(--color-provider-done-foreground)` — which a mark takes as its
 *    `fill` and which already answers to `.dark`, so the per-theme `theme` map goes too.
 * 2. The legend swatch paints through `--color-bg` like the tooltip's indicator, not an inline
 *    `backgroundColor`, and both swatches take the `rounded-xs` step rather than `rounded-[2px]`.
 * 3. The tooltip label is not memoised (the React Compiler does it), and the payload reads are
 *    narrowed rather than asserted, for the type-aware lint.
 */
const INITIAL_DIMENSION = { width: 320, height: 200 } as const;
type TooltipNameType = number | string;

export type ChartConfig = Record<
	string,
	{
		label?: ReactNode;
		icon?: ComponentType;
		/** A theme token such as `var(--color-chart-1)`, never a raw colour. */
		color?: string;
	}
>;

interface ChartContextProps {
	config: ChartConfig;
}

const ChartContext = createContext<ChartContextProps | null>(null);

function useChart() {
	const context = useContext(ChartContext);

	if (!context) {
		throw new Error("useChart must be used within a <ChartContainer />");
	}

	return context;
}

function ChartContainer({
	id,
	className,
	children,
	config,
	initialDimension = INITIAL_DIMENSION,
	...props
}: ComponentProps<"div"> & {
	config: ChartConfig;
	children: ComponentProps<typeof RechartsPrimitive.ResponsiveContainer>["children"];
	initialDimension?: {
		width: number;
		height: number;
	};
}) {
	const uniqueId = useId();
	const chartId = `chart-${id ?? uniqueId.replaceAll(":", "")}`;

	return (
		<ChartContext.Provider value={{ config }}>
			<div
				data-slot="chart"
				data-chart={chartId}
				className={cn(
					"flex aspect-video justify-center text-xs [&_.recharts-cartesian-axis-tick_text]:fill-muted-foreground [&_.recharts-cartesian-grid_line[stroke='#ccc']]:stroke-border/50 [&_.recharts-curve.recharts-tooltip-cursor]:stroke-border [&_.recharts-dot[stroke='#fff']]:stroke-transparent [&_.recharts-layer]:outline-hidden [&_.recharts-polar-grid_[stroke='#ccc']]:stroke-border [&_.recharts-radial-bar-background-sector]:fill-muted [&_.recharts-rectangle.recharts-tooltip-cursor]:fill-muted [&_.recharts-reference-line_[stroke='#ccc']]:stroke-border [&_.recharts-sector]:outline-hidden [&_.recharts-sector[stroke='#fff']]:stroke-transparent [&_.recharts-surface]:outline-hidden",
					className,
				)}
				{...props}
			>
				<RechartsPrimitive.ResponsiveContainer initialDimension={initialDimension}>
					{children}
				</RechartsPrimitive.ResponsiveContainer>
			</div>
		</ChartContext.Provider>
	);
}

const ChartTooltip = RechartsPrimitive.Tooltip;

function ChartTooltipContent({
	active,
	payload,
	className,
	indicator = "dot",
	hideLabel = false,
	hideIndicator = false,
	label,
	labelFormatter,
	labelClassName,
	formatter,
	color,
	nameKey,
	labelKey,
}: ComponentProps<typeof RechartsPrimitive.Tooltip> &
	ComponentProps<"div"> & {
		hideLabel?: boolean;
		hideIndicator?: boolean;
		indicator?: "line" | "dot" | "dashed";
		nameKey?: string;
		labelKey?: string;
	} & Omit<
		RechartsPrimitive.DefaultTooltipContentProps<TooltipValueType, TooltipNameType>,
		"accessibilityLayer"
	>) {
	const { config } = useChart();

	if (active !== true || payload === undefined || payload.length === 0) {
		return null;
	}

	const tooltipLabel = ((): ReactNode => {
		if (hideLabel) {
			return null;
		}
		const [item] = payload;
		const key = labelKey ?? keyOf(item?.dataKey) ?? keyOf(item?.name) ?? "value";
		const itemConfig = getPayloadConfigFromPayload(config, item, key);
		const value =
			labelKey === undefined && typeof label === "string"
				? (config[label]?.label ?? label)
				: itemConfig?.label;

		if (labelFormatter) {
			return (
				<div className={cn("font-medium", labelClassName)}>{labelFormatter(value, payload)}</div>
			);
		}

		if (value === undefined || value === null || value === "") {
			return null;
		}

		return <div className={cn("font-medium", labelClassName)}>{value}</div>;
	})();

	const nestLabel = payload.length === 1 && indicator !== "dot";

	return (
		<div
			className={cn(
				"grid min-w-32 items-start gap-1.5 rounded-lg border border-border/50 bg-background px-2.5 py-1.5 text-xs shadow-xl",
				className,
			)}
		>
			{nestLabel ? null : tooltipLabel}
			<div className="grid gap-1.5">
				{payload
					.filter((item) => item.type !== "none")
					.map((item, index) => {
						const key = nameKey ?? keyOf(item.name) ?? keyOf(item.dataKey) ?? "value";
						const itemConfig = getPayloadConfigFromPayload(config, item, key);
						const indicatorColor = color ?? payloadFill(item) ?? item.color;

						return (
							<div
								key={index}
								className={cn(
									"flex w-full flex-wrap items-stretch gap-2 [&>svg]:h-2.5 [&>svg]:w-2.5 [&>svg]:text-muted-foreground",
									indicator === "dot" && "items-center",
								)}
							>
								{formatter && item.value !== undefined && item.name !== undefined ? (
									formatter(item.value, item.name, item, index, payload)
								) : (
									<>
										{itemConfig?.icon ? (
											<itemConfig.icon />
										) : (
											!hideIndicator && (
												<div
													className={cn(
														"shrink-0 rounded-xs border-(--color-border) bg-(--color-bg)",
														{
															"h-2.5 w-2.5": indicator === "dot",
															"w-1": indicator === "line",
															"w-0 border border-dashed bg-transparent": indicator === "dashed",
															"my-0.5": nestLabel && indicator === "dashed",
														},
													)}
													style={{
														"--color-bg": indicatorColor,
														"--color-border": indicatorColor,
													}}
												/>
											)
										)}
										<div
											className={cn(
												"flex flex-1 justify-between leading-none",
												nestLabel ? "items-end" : "items-center",
											)}
										>
											<div className="grid gap-1.5">
												{nestLabel ? tooltipLabel : null}
												<span className="text-muted-foreground">
													{itemConfig?.label ?? item.name}
												</span>
											</div>
											{item.value !== undefined && (
												<span className="font-mono font-medium text-foreground tabular-nums">
													{typeof item.value === "number"
														? item.value.toLocaleString()
														: String(item.value)}
												</span>
											)}
										</div>
									</>
								)}
							</div>
						);
					})}
			</div>
		</div>
	);
}

const ChartLegend = RechartsPrimitive.Legend;

function ChartLegendContent({
	className,
	hideIcon = false,
	payload,
	verticalAlign = "bottom",
	nameKey,
}: ComponentProps<"div"> & {
	hideIcon?: boolean;
	nameKey?: string;
} & RechartsPrimitive.DefaultLegendContentProps) {
	const { config } = useChart();

	if (payload === undefined || payload.length === 0) {
		return null;
	}

	return (
		<div
			className={cn(
				"flex items-center justify-center gap-4",
				verticalAlign === "top" ? "pb-3" : "pt-3",
				className,
			)}
		>
			{payload
				.filter((item) => item.type !== "none")
				.map((item, index) => {
					const key = nameKey ?? keyOf(item.dataKey) ?? "value";
					const itemConfig = getPayloadConfigFromPayload(config, item, key);

					return (
						<div
							key={index}
							className="flex items-center gap-1.5 [&>svg]:h-3 [&>svg]:w-3 [&>svg]:text-muted-foreground"
						>
							{itemConfig?.icon && !hideIcon ? (
								<itemConfig.icon />
							) : (
								<div
									className="h-2 w-2 shrink-0 rounded-xs bg-(--color-bg)"
									style={{ "--color-bg": item.color }}
								/>
							)}
							{itemConfig?.label}
						</div>
					);
				})}
		</div>
	);
}

/** A data key or series name as a config key; a function key names nothing a config can hold. */
function keyOf(value: unknown): string | undefined {
	return typeof value === "string" || typeof value === "number" ? String(value) : undefined;
}

/** The fill a bar or sector drew with, which the payload carries on its datum. */
function payloadFill(item: { payload?: unknown }): string | undefined {
	const datum: unknown = item.payload;
	if (typeof datum === "object" && datum !== null && "fill" in datum) {
		return typeof datum.fill === "string" ? datum.fill : undefined;
	}
	return undefined;
}

function stringField(value: unknown, key: string): string | undefined {
	if (typeof value !== "object" || value === null || !(key in value)) {
		return undefined;
	}
	const field: unknown = Reflect.get(value, key);
	return typeof field === "string" ? field : undefined;
}

function getPayloadConfigFromPayload(config: ChartConfig, payload: unknown, key: string) {
	if (typeof payload !== "object" || payload === null) {
		return;
	}

	const payloadPayload: unknown = Reflect.get(payload, "payload");
	const configLabelKey = stringField(payload, key) ?? stringField(payloadPayload, key) ?? key;

	return configLabelKey in config ? config[configLabelKey] : config[key];
}

export { ChartContainer, ChartLegend, ChartLegendContent, ChartTooltip, ChartTooltipContent };
