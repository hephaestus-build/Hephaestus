import type { TrendSupport } from "@/api/types.gen";
import { AppText } from "@/ui/AppText";
import { Section, SectionText } from "@/ui/Section";

import { type TrendDirection, trendProvenance } from "./trend";
import { TREND } from "./vocabulary";

/**
 * Which way a group or practice has been moving, and what that rests on. Without enough reviewed work
 * on both sides it says so, and how much more it needs, instead of drawing a direction.
 */
export function Direction({
	direction = "INSUFFICIENT_EVIDENCE",
	support,
	scope,
}: {
	direction: TrendDirection | undefined;
	support: TrendSupport | undefined;
	scope: "practice" | "group";
}) {
	const def = TREND[direction];
	return (
		<Section title="Direction">
			<SectionText>
				<AppText variant="headline">{def.label}</AppText>
				<AppText variant="subheadline" tone="secondaryLabel">
					{def.description}
				</AppText>
				<AppText variant="footnote" tone="secondaryLabel">
					{support === undefined
						? "No reviewed work is available yet."
						: trendProvenance(support, direction, scope)}
				</AppText>
			</SectionText>
		</Section>
	);
}
