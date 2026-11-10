#### 🔴 Use the canonical webhook receiver origin

**Who is affected:** Operators with `WEBHOOK_EXTERNAL_URL` or `hephaestus.webhook.external-url` set, including installations that registered GitLab or Outline hooks at a different origin.

**Action:** Remove both settings. GitLab and Outline registration uses the configured API issuer origin, which is the chart host. The receiver paths begin with `/webhooks/`; they do not include `/api` or another API proxy prefix.

If the previous receiver origin differs from the canonical origin:

1. Confirm that the canonical public origin routes `/webhooks/` to this installation's webhook receiver.
2. Stop the application roles before changing provider registrations. Use the deployment runbook to drain work and record the stopped state.
3. Record and remove this installation's GitLab group hooks and Outline subscriptions that use the retired origin. Keep their workspace and connection associations. Do not remove another installation's registration.
4. Remove the retired settings and upgrade. Start the roles, then run normal full GitLab workspace synchronization and Outline collection synchronization for each affected connection. These flows recreate missing registrations and update their stored IDs and routes.
5. Verify provider delivery at the canonical receiver and confirm that this installation has no remaining registrations at the retired origin before resuming normal use. If you cannot verify the new registrations or delivery, refuse the cutover: roll back to the previous release and recreate the registrations that you recorded in step 3.

If the existing addresses already use the canonical origin, remove the settings and upgrade without changing their route paths. Recreated Outline subscriptions can have new route IDs and signing credentials; routing keys and connection identities remain in use. The application does not read the old override or register a second receiver address.

GitHub App webhook registration still uses `/webhooks/github`. Slack keeps its existing Events API and separately routed interactivity addresses.
