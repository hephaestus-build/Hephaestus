/**
 * The workspace the backup-restore and point-in-time-recovery drills seed before taking a backup, and what
 * they read back after restoring a clone. It has an enabled Heph binding, so the restore-clone lockdown has
 * one to turn off.
 */
export const seedRestoreProbe = `
INSERT INTO workspace(account_login, account_type, display_name, is_publicly_viewable, slug, status)
VALUES ('restore-probe', 'USER', 'Restore probe', FALSE, 'restore-probe', 'ACTIVE');

INSERT INTO llm_connection(slug, display_name, base_url, api_protocol, created_at)
VALUES ('restore-probe', 'Restore probe', 'https://llm.restore-probe.example/v1', 'openai-completions', now());

INSERT INTO llm_model(connection_id, slug, display_name, upstream_model_id, created_at)
SELECT id, 'restore-probe', 'Restore probe', 'restore-probe', now()
FROM llm_connection
WHERE slug = 'restore-probe';

INSERT INTO workspace_agent_binding(workspace_id, purpose, enabled, instance_model_id)
SELECT workspace.id, 'MENTOR', TRUE, llm_model.id
FROM workspace, llm_model
WHERE workspace.slug = 'restore-probe' AND llm_model.slug = 'restore-probe';
`;

/** A column of a query over `workspace`: whether each of its Heph bindings is enabled, comma-separated. */
export const hephBindingsEnabled = `(
	SELECT string_agg(binding.enabled::text, ',')
	FROM workspace_agent_binding binding
	WHERE binding.workspace_id = workspace.id AND binding.purpose = 'MENTOR'
)`;
