import { Check, Search } from "lucide-react";
import { useId, useState } from "react";

import { cn } from "cn";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import { ToggleGroup, ToggleGroupItem } from "@/components/ui/toggle-group";

import {
	COLOR_KEYS,
	DEFAULT_GROUP_COLOR,
	DEFAULT_GROUP_ICON,
	getGroupVisual,
	ICON_NAMES,
	iconComponent,
	iconLabel,
	iconSearchText,
	PILL,
} from "./group-visuals";

export interface GroupVisualPickerProps {
	id?: string;
	describedBy?: string;
	name: string;
	icon?: string | null;
	color?: string | null;
	onChange: (patch: { icon?: string; color?: string }) => void;
	disabled?: boolean;
}

export function GroupVisualPicker({
	id,
	describedBy,
	name,
	icon,
	color,
	onChange,
	disabled,
}: GroupVisualPickerProps) {
	const activeIcon = icon ?? DEFAULT_GROUP_ICON;
	const activeColor = color ?? DEFAULT_GROUP_COLOR;
	const { Icon: EffectiveIcon, pill } = getGroupVisual(icon, color);
	const [query, setQuery] = useState("");
	const [open, setOpen] = useState(false);
	const colorLabelId = useId();
	const iconLabelId = useId();

	const q = query.trim().toLowerCase();
	const filteredIcons = q ? ICON_NAMES.filter((n) => iconSearchText(n).includes(q)) : ICON_NAMES;

	return (
		<Popover open={!disabled && open} onOpenChange={(nextOpen) => !disabled && setOpen(nextOpen)}>
			<PopoverTrigger
				render={
					<Button
						id={id}
						variant="ghost"
						size="icon-sm"
						disabled={disabled}
						aria-describedby={describedBy}
						aria-label={id ? undefined : `Edit the icon and color for ${name || "practice group"}`}
					>
						<span className={cn("flex size-6 items-center justify-center rounded-md", pill)}>
							<EffectiveIcon className="size-4" aria-hidden="true" />
						</span>
					</Button>
				}
			/>
			{!disabled && (
				<PopoverContent className="w-72 space-y-3" aria-label="Icon and color">
					<div className="space-y-1.5">
						<p id={colorLabelId} className="text-xs text-muted-foreground">
							Color
						</p>
						<ToggleGroup
							disabled={disabled}
							value={[activeColor]}
							onValueChange={(value) => value[0] && onChange({ color: value[0] })}
							spacing={1}
							role="toolbar"
							aria-labelledby={colorLabelId}
							className="grid w-full grid-cols-7 gap-1.5"
						>
							{COLOR_KEYS.map((key) => (
								<ToggleGroupItem
									key={key}
									value={key}
									aria-label={key}
									disabled={disabled}
									className={cn(
										"size-7 min-w-0 rounded-full border border-black/10 p-0 transition-transform hover:scale-110 dark:border-white/15",
										PILL[key],
										activeColor === key && "ring-2 ring-ring ring-offset-1",
									)}
								>
									{activeColor === key && <Check className="size-3.5" aria-hidden="true" />}
								</ToggleGroupItem>
							))}
						</ToggleGroup>
					</div>

					<div className="space-y-1.5">
						<p id={iconLabelId} className="text-xs text-muted-foreground">
							Icon
						</p>
						<div className="relative">
							<Search
								className="pointer-events-none absolute left-2 top-1/2 size-3.5 -translate-y-1/2 text-muted-foreground"
								aria-hidden
							/>
							<Input
								value={query}
								onChange={(e) => setQuery(e.target.value)}
								placeholder="Search icons…"
								aria-label="Search icons"
								autoComplete="off"
								disabled={disabled}
								className="h-8 pl-7 text-sm"
							/>
						</div>
						{filteredIcons.length === 0 ? (
							<p className="py-6 text-center text-xs text-muted-foreground">
								No icons match “{query}”.
							</p>
						) : (
							<ToggleGroup
								disabled={disabled}
								value={[activeIcon]}
								onValueChange={(value) => value[0] && onChange({ icon: value[0] })}
								spacing={1}
								role="toolbar"
								aria-labelledby={iconLabelId}
								className="grid max-h-44 w-full grid-cols-7 gap-1 overflow-y-auto pr-1"
							>
								{filteredIcons.map((iconName) => {
									const Icon = iconComponent(iconName);
									return (
										<ToggleGroupItem
											key={iconName}
											value={iconName}
											aria-label={iconLabel(iconName)}
											disabled={disabled}
											className="size-8 min-w-0 p-0 text-muted-foreground aria-pressed:bg-primary aria-pressed:text-primary-foreground aria-pressed:hover:bg-primary aria-pressed:hover:text-primary-foreground"
										>
											<Icon className="size-4" aria-hidden="true" />
										</ToggleGroupItem>
									);
								})}
							</ToggleGroup>
						)}
					</div>
				</PopoverContent>
			)}
		</Popover>
	);
}
