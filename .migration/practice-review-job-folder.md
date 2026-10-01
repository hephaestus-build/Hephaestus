#### 🔴 Upgrade practice reviews to the frozen workspace folder

Pause new practice reviews and let in-flight reviews finish before upgrading. Deploy the matching
server and review runtime together. The runtime reads the flat version-3 task and `INDEX.json` from
the job folder; the old capped capture and artifact-source manifest are removed.

Apply updated bundled practice definitions through catalogue adoption. Review customized practices
and set their automated-review policy's `sourceContractVersion` to `1.3.0` through practice authoring.
This creates a new practice revision; do not rewrite historical revisions or approvals. A policy
pinned to an older source contract does not authorize the expanded folder and is refused before
model execution. Submit a new review under the updated practice revision rather than reusing an
old attempt. Resume reviews after the matching components and updated practices are ready.

The wider workspace scope remains subject to existing visibility, member choices, processor routing,
consent, withdrawal, tenancy, retention and erasure checks. Maintainer engineering approval is not
controller or DPO approval. Operators must include Slack-thread and person-scoped history in their
applicable privacy-notice review before deployment; the pending TUM review remains a separate release
obligation.
