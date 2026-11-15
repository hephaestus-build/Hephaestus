#### 🔴 Update the Slack app's descriptions

The Slack app manifest template now describes Hephaestus as an AI system. Slack shows the app's description and agent description before a person writes to the app, so the descriptions in your Slack app settings must say the same.

**Operators:** In your Slack app settings, copy `display_information.description` and `features.agent_view.agent_description` from the current [`slack-app-manifest-template.yml`](https://github.com/hephaestus-build/Hephaestus/blob/main/docs/admin/slack-app-manifest-template.yml). The change needs no new scope and no reinstall. Hephaestus runs the same before and after you make it.
