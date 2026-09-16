import type { Decorator } from "@storybook/react";
import { ListChecks } from "lucide-react";

import { StandardPageSurface } from "@/components/layout/StandardPageSurface";
import { getProviderSlug, type ProviderType } from "@/lib/provider";

/**
 * Wraps a story in a provider color scope so that
 * `--color-provider-*` CSS custom properties resolve to provider-specific values.
 */
export function withProvider(provider: ProviderType): Decorator {
	return function ProviderDecorator(Story) {
		return (
			<div data-provider={getProviderSlug(provider)}>
				<Story />
			</div>
		);
	};
}

export const withStandardPage: Decorator = (Story) => (
	<StandardPageSurface>
		<Story />
	</StandardPageSurface>
);

export const withWidePage: Decorator = (Story) => (
	<div className="mx-auto w-full max-w-6xl">
		<Story />
	</div>
);

/**
 * A plausible admin page behind an overlay story, so a drawer's scrim, its peek and its dismiss can
 * be judged against something rather than against an empty canvas. Deliberately generic: the point
 * is the surface behind, not any particular screen.
 */
export const withPageBehind: Decorator = (Story) => (
	<>
		<StandardPageSurface>
			<div className="mx-auto w-full max-w-6xl space-y-6">
				<header className="flex items-start gap-3">
					<ListChecks className="size-6 shrink-0 text-muted-foreground" aria-hidden />
					<div className="space-y-1">
						<h1 className="text-2xl font-semibold tracking-tight">Practice setup</h1>
						<p className="max-w-2xl text-sm text-muted-foreground">
							Organize this workspace's practices and add suggestions from the instance catalog.
						</p>
					</div>
				</header>
				<div className="space-y-2">
					{["Review-ready work", "Documentation", "Collaboration"].map((group) => (
						<div key={group} className="rounded-lg border p-4">
							<p className="font-medium">{group}</p>
							<p className="text-sm text-muted-foreground">3 practices</p>
						</div>
					))}
				</div>
			</div>
		</StandardPageSurface>
		<Story />
	</>
);
