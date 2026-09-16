import { useState } from "react";

import { type FeatureFlagName, useFeatureFlags } from "./hooks";

interface FeatureFlagDevToolsPanelProps {
	/** Feature flags to display. `undefined` means "not authenticated". */
	flags: Record<FeatureFlagName, boolean> | undefined;
	/** Whether flags are currently loading. */
	isLoading: boolean;
}

/**
 * Pure presentational panel for feature flag state.
 * Exported for Storybook — use {@link FeatureFlagDevTools} in application code.
 */
export function FeatureFlagDevToolsPanel({ flags, isLoading }: FeatureFlagDevToolsPanelProps) {
	const [isOpen, setIsOpen] = useState(false);

	return (
		<div className="fixed right-4 bottom-4 z-[9999]">
			{isOpen && (
				<div className="mb-2 w-72 rounded-lg border border-border bg-background shadow-lg">
					<div className="flex items-center justify-between border-b border-border px-3 py-2">
						<span className="text-xs font-semibold text-foreground">Feature Flags</span>
						<button
							type="button"
							onClick={() => setIsOpen(false)}
							className="text-xs text-muted-foreground hover:text-foreground"
						>
							Close
						</button>
					</div>
					<div className="max-h-80 overflow-y-auto p-2">
						{isLoading ? (
							<div className="px-2 py-3 text-center text-xs text-muted-foreground">Loading...</div>
						) : flags ? (
							<div className="space-y-1">
								{Object.entries(flags)
									.sort(([a], [b]) => a.localeCompare(b))
									.map(([name, enabled]) => (
										<div
											key={name}
											className="flex items-center justify-between rounded px-2 py-1 text-xs hover:bg-muted"
										>
											<span className="font-mono text-foreground">{name}</span>
											<span
												className={`rounded-full px-1.5 py-0.5 text-2xs font-medium ${
													enabled
														? "bg-success/10 text-success"
														: "bg-destructive/10 text-destructive"
												}`}
											>
												{enabled ? "ON" : "OFF"}
											</span>
										</div>
									))}
							</div>
						) : (
							<div className="px-2 py-3 text-center text-xs text-muted-foreground">
								Not authenticated
							</div>
						)}
					</div>
				</div>
			)}
			<button
				type="button"
				onClick={() => setIsOpen(!isOpen)}
				className="flex h-8 items-center gap-1.5 rounded-full border border-border bg-background px-3 text-xs font-medium text-muted-foreground shadow-sm transition-colors hover:bg-muted hover:text-foreground"
				title="Feature Flag DevTools"
			>
				<svg
					role="img"
					aria-label="Feature flags"
					xmlns="http://www.w3.org/2000/svg"
					width="14"
					height="14"
					viewBox="0 0 24 24"
					fill="none"
					stroke="currentColor"
					strokeWidth="2"
					strokeLinecap="round"
					strokeLinejoin="round"
				>
					<path d="M4 15s1-1 4-1 5 2 8 2 4-1 4-1V3s-1 1-4 1-5-2-8-2-4 1-4 1z" />
					<line x1="4" x2="4" y1="22" y2="15" />
				</svg>
				Flags
			</button>
		</div>
	);
}

/**
 * Floating dev-only panel showing all feature flag states.
 * Only renders when `import.meta.env.DEV` is true.
 *
 * Toggle with the button in the bottom-right corner.
 */
export function FeatureFlagDevTools() {
	const { flags, isLoading } = useFeatureFlags();

	if (!import.meta.env.DEV) {
		return null;
	}

	return <FeatureFlagDevToolsPanel flags={flags} isLoading={isLoading} />;
}
