import { BotIcon } from "lucide-react";

import { cn } from "cn";

import {
	AI_CONNECTION_PLATFORM_META,
	type AiConnectionPlatform,
} from "@/components/icons/ai-connection-platform-logos";
import { AI_MODEL_BRAND_META, type AiModelBrand } from "@/components/icons/ai-model-brand-logos";

const TILE = {
	sm: "size-5 rounded-sm p-0.5",
	md: "size-8 rounded-md p-1.5",
	lg: "size-11 rounded-lg p-2",
};

const BADGE = {
	sm: "hidden",
	md: "-right-1 -bottom-1 size-4 p-0.5",
	lg: "-right-1.5 -bottom-1.5 size-5 p-0.5",
};

export interface AiMarkProps {
	brand?: AiModelBrand;
	platform?: AiConnectionPlatform;
	size?: keyof typeof TILE;
	className?: string;
}

/**
 * A model maker's mark with the service that receives requests as a corner badge; either alone
 * fills the tile. Decoration: the name beside it says the same in words. The tile is white in both
 * themes because most vendored marks are monochrome black.
 */
export function AiMark({ brand, platform, size = "md", className }: AiMarkProps) {
	const main = brand
		? AI_MODEL_BRAND_META[brand]
		: platform && AI_CONNECTION_PLATFORM_META[platform];
	const badge = brand && platform ? AI_CONNECTION_PLATFORM_META[platform] : undefined;
	return (
		<span
			aria-hidden="true"
			className={cn(
				"relative inline-flex shrink-0 items-center justify-center shadow-xs ring-1 ring-black/10",
				main ? "bg-white" : "bg-muted text-muted-foreground",
				TILE[size],
				className,
			)}
		>
			{main ? (
				<img src={main.src} alt="" className="size-full" />
			) : (
				<BotIcon className="size-full" />
			)}
			{badge && (
				<img
					src={badge.src}
					alt=""
					className={cn(
						"absolute rounded-full bg-white shadow-xs ring-2 ring-background",
						BADGE[size],
					)}
				/>
			)}
		</span>
	);
}
