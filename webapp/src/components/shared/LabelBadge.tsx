import { cn } from "cn";
import { Badge } from "@/components/ui/badge";

interface LabelBadgeProps extends React.ComponentPropsWithoutRef<typeof Badge> {
	label: string;
	color?: string;
}

const HEX_COLOR = /^[0-9a-f]{6}$/i;

function relativeLuminance(hex: string): number {
	const linearise = (offset: number) => {
		const channel = Number.parseInt(hex.slice(offset, offset + 2), 16) / 255;
		return channel <= 0.04045 ? channel / 12.92 : ((channel + 0.055) / 1.055) ** 2.4;
	};
	return linearise(0) * 0.2126 + linearise(2) * 0.7152 + linearise(4) * 0.0722;
}

function labelColors(color?: string): React.CSSProperties | undefined {
	const hex = color?.replace(/^#/, "");
	if (!hex || !HEX_COLOR.test(hex)) return undefined;

	const luminance = relativeLuminance(hex);
	const foreground = (luminance + 0.05) / 0.05 >= 1.05 / (luminance + 0.05) ? "#000" : "#fff";
	const background = `#${hex}`;

	return {
		backgroundColor: background,
		borderColor: background,
		color: foreground,
	};
}

export function LabelBadge({ label, color, className, style, ...props }: LabelBadgeProps) {
	return (
		<Badge
			variant="outline"
			className={cn(
				"h-5 max-w-full rounded-full border-solid px-2 text-xs font-medium no-underline",
				className,
			)}
			style={{ ...labelColors(color), ...style }}
			{...props}
		>
			<span className="truncate">{label}</span>
		</Badge>
	);
}
