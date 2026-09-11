export function EvidenceRevision({ revision }: { revision?: string }) {
	if (!revision) return null;
	return (
		<p className="border-b bg-muted/50 px-3 py-2 text-xs text-muted-foreground">
			Commit <code className="break-all">{revision}</code>
		</p>
	);
}
