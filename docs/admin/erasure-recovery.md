---
title: Reapply erasures after a restore
description: Erase again each disconnect, workspace purge and person that a database restore brought back, before the restored server processes data.
page_type: how-to
---

A database restore brings back the data that Hephaestus erased after the recovery point: disconnected connections, purged workspaces and erased people.
Reapply these erasures before the restored server processes data or opens access.

The server's `erasure-recovery` startup profile turns background work off: no scheduled task, claimer or inbox consumer runs.
Startup stops if a setting turns background work on beside the profile.
The profile then reapplies each erasure in a manifest, in this order: disconnects, workspace purges, then people.
Each step is idempotent, so a restart with the same manifest is safe.
A missing or invalid manifest, a manifest of version 1, an unknown provider instance, or an incomplete erasure stops startup.

## Before you start

- You restored the database and completed its migration with `server.replicas=0`, as steps 1 to 6 of [Recover the live installation](./backup-restore#recover-the-live-installation) describe.
- External egress stays denied, as step 3 of the same procedure describes.
- You have the restricted incident record that lists each erasure after the recovery point.
- You have `kubectl` access to the release namespace, and `jq`.

## Write the manifest

1. From the incident record, write a JSON manifest of each erasure after the recovery point.
2. List each disconnected connection under `disconnects`, with its workspace ID and connection ID.
   Include each disconnect that a provider caused, such as a Slack app uninstall.
3. List each purged workspace under `workspacePurges`, with its workspace ID.
   The server log events `connection.erased` and `workspace.purged` carry these IDs, so they cross-check the record.
4. List each erased person under `people`, including an account that its owner deleted.
   Include the account ID of each person, when you know it, and all known exact native identities.
5. For GitHub and GitLab, use the numeric user ID.
   For Slack, use the user ID and the team ID.
   For Outline, use the user UUID.
   Never use a login, a display name or an email address in place of these IDs.
6. Give the exact provider type and server URL from the restored provider inventory.
7. Set `administratorAccountId` to an administrator who is active and has instance-admin rights in the restored database.
   Only `people` needs the administrator. Another administrator must authorize the erasure of that administrator.
8. Set `externalCopiesRemoved` to `true` only after you confirm that the provider copies are gone, as [Remove feedback from a provider](./remove-feedback-from-a-provider) requires.
   The recovery does not delete provider-hosted copies.

The manifest has this structure:

```json
{
  "version": 2,
  "restorePoint": "2026-11-01T00:00:00Z",
  "administratorAccountId": 10,
  "noLaterErasures": false,
  "disconnects": [
    {"workspaceId": 3, "connectionId": 17}
  ],
  "workspacePurges": [
    {"workspaceId": 5}
  ],
  "people": [
    {
      "accountId": 42,
      "identities": [
        {"providerType": "GITHUB", "serverUrl": "https://github.com", "subject": "123456"},
        {"providerType": "GITLAB", "serverUrl": "https://gitlab.example.test", "subject": "234567"},
        {"providerType": "SLACK", "serverUrl": "https://slack.com", "subject": "U123456", "teamId": "T123456"},
        {"providerType": "OUTLINE", "serverUrl": "https://outline.example.test", "subject": "00000000-0000-4000-8000-000000000042"}
      ],
      "externalCopiesRemoved": true
    }
  ]
}
```

What each entry does:

- A disconnect erases the local data of the connection and releases its credential.
  It also records the removal of what the connection installed at the provider.
  The server tries that removal at once. It fails while provider egress is closed, and the server tries it again after egress opens.
  The erasure does not wait for it.
- A purge deletes the data of the workspace, as the original purge did.
- A connection or workspace that the restored database does not have, or that is erased already, needs nothing more.

If no erasure occurred after the recovery point, declare it:

```json
{
  "version": 2,
  "restorePoint": "2026-11-01T00:00:00Z",
  "noLaterErasures": true,
  "disconnects": [],
  "workspacePurges": [],
  "people": []
}
```

Empty lists without `noLaterErasures: true` stop startup.

Keep the manifest outside the database, and give access only to the recovery operators.
Do not put it in Helm values, a ConfigMap, a command-line argument or shared logs.
Hephaestus does not store or log the manifest.
Keep it for the same period as each backup that can restore erased data, as [Backup retention](./backup-restore#backup-retention) describes.

## Start the server with the recovery profile

The chart starts the server with the `prod` profile and a startup probe.
A large manifest can take longer than the startup probe permits.
Thus, this procedure patches the server Deployment for the recovery.
The patch mounts the manifest from a Secret and adds the `erasure-recovery` profile.
It sets `AGENT_ENABLED=false`, so the server starts no sandbox.
It keeps the readiness probe, removes the startup and liveness probes, and sets the rollout progress deadline to one day.

The Secret holds private identities, so give namespace access only to the recovery operators.
A Secret holds less than 1 MiB.
For a larger manifest, mount a protected read-only volume at the same path.

While the recovery Pod is not ready, `HephaestusServerDown` can fire.
Silence it in your Alertmanager for the time of the recovery.

1. Keep retained server evidence volumes under access control until the recovery is complete.
   Their servers must reconcile their folders against the recovered receipts before you use the volumes again.
   Follow [A server with evidence is offline](./person-data-requests#a-server-with-evidence-is-offline).
2. Set `NAMESPACE`, `SERVER_DEPLOYMENT` and `ERASURE_MANIFEST` to the namespace, the server Deployment and the manifest file.
3. Save the Deployment, create the Secret, and apply the recovery patch:

   ```bash
   umask 077
   server_recovery_dir=$(mktemp -d)
   erasure_secret="${SERVER_DEPLOYMENT}-erasure-recovery"
   kubectl -n "$NAMESPACE" get deployment "$SERVER_DEPLOYMENT" -o json > "$server_recovery_dir/before.json"
   kubectl -n "$NAMESPACE" create secret generic "$erasure_secret" --from-file="manifest.json=$ERASURE_MANIFEST"
   server_container=$(jq -r '.spec.template.spec.containers[0].name' "$server_recovery_dir/before.json")
   jq -n --arg container "$server_container" --arg secret "$erasure_secret" '
     {spec: {replicas: 1, progressDeadlineSeconds: 86400, template: {spec: {
       containers: [{name: $container, startupProbe: null, livenessProbe: null,
         env: [
           {name: "SPRING_PROFILES_ACTIVE", value: "prod,erasure-recovery"},
           {name: "HEPHAESTUS_PRIVACY_RECOVERY_MANIFEST", value: "/var/run/hephaestus/erasure-recovery/manifest.json"},
           {name: "AGENT_ENABLED", value: "false"}],
         volumeMounts: [{name: "erasure-recovery", mountPath: "/var/run/hephaestus/erasure-recovery", readOnly: true}]}],
       volumes: [{name: "erasure-recovery", secret: {secretName: $secret, defaultMode: 288}}]
     }}}}' > "$server_recovery_dir/recovery-patch.json"
   kubectl -n "$NAMESPACE" patch deployment "$SERVER_DEPLOYMENT" --type=strategic --patch-file="$server_recovery_dir/recovery-patch.json"
   ```

4. Wait until the recovery Pod is ready:

   ```bash
   kubectl -n "$NAMESPACE" rollout status deployment "$SERVER_DEPLOYMENT" --timeout=60m
   ```

   A timeout of this command does not stop the Pod.
   If the recovery takes more than one hour, run the command again.
   If the recovery approaches one day, extend the progress deadline before it expires.
   After the deadline, wait for the Pod with `kubectl wait --for=condition=Ready pod <pod-name> --timeout=60m`.

## Return to normal operation

1. Restore the saved Deployment specification. It has zero replicas from the maintenance stop:

   ```bash
   jq '{spec: .spec}' "$server_recovery_dir/before.json" > "$server_recovery_dir/restore-patch.json"
   kubectl -n "$NAMESPACE" patch deployment "$SERVER_DEPLOYMENT" --type=merge --patch-file="$server_recovery_dir/restore-patch.json"
   ```

2. Wait until the recovery Pod stops, then remove the Secret:

   ```bash
   server_selector=$(jq -r '.spec.selector.matchLabels | to_entries | map(.key + "=" + .value) | join(",")' "$server_recovery_dir/before.json")
   kubectl -n "$NAMESPACE" wait --for=delete pod -l "$server_selector" --timeout=5m
   kubectl -n "$NAMESPACE" delete secret "$erasure_secret"
   ```

3. Remove the silence of `HephaestusServerDown`.
4. Continue with step 8 of [Recover the live installation](./backup-restore#recover-the-live-installation).
   That step starts the normal server with Helm.

## Check that it worked

1. The recovery Pod became ready in step 4 of the recovery start.
   Its startup applied every erasure in the manifest.
2. The recovery Pod log has no `person_data.recovery.failed` record.
3. The server Deployment has no `erasure-recovery` profile and no manifest mount.

## Troubleshooting

### The recovery Pod logs `person_data.recovery.failed`

The startup stopped before it applied every erasure.
The record has a closed `person_data.recovery.reason` code.
It does not quote the manifest or the identities.

1. Keep the other Deployments stopped and external egress denied.
2. Correct the cause that the reason names:
   - For `MANIFEST_VERSION`, write the manifest as version 2.
   - For `BACKGROUND_WORK`, remove the setting that turns background work on beside the profile, such as `HEPHAESTUS_BACKGROUND_WORK_ENABLED`.
   - For `ERASURE_INCOMPLETE` on a disconnect, turn on the integration of that connection for the recovery run. Without it, nothing can erase its data.
   - For another reason, correct the manifest, or complete the unfinished erasure.
3. Replace the Secret with the corrected manifest.
4. Delete the recovery Pod, so that the Deployment starts it again with the corrected manifest.
5. Wait for the recovery Pod, as step 4 of the recovery start describes.

Do not remove the recovery profile to continue past the failure.
