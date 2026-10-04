#### 🔴 Check your research obligations before upgrading

The research question in setup and in User settings now asks for consent to an area of research, not to one research project.
The area is how developers work and learn, and how AI systems can review and support that work, including building and running benchmarks and evaluation datasets for such AI systems.
The wording version changed, so every account answers setup once more.
An earlier "yes" does not carry over: `participatesInResearch` reports false, and research survey invitations stop, until the account answers.

If `HEPHAESTUS_RESEARCH_ORGANIZATION` is unset, setup is the terms alone and nothing about research changes for you.

If you set it, do these steps before you upgrade:

1. Read "What operators must do" in the [Legal Pages guide](https://docs.hephaestus.build/admin/legal-pages#the-optional-research-question).
2. Meet each obligation, or unset the variable.
3. Update your privacy notice with the retention, recipients and withdrawal limits of your research. The TUM notice shows the expected shape.
4. Choose a research organization name that reads correctly in the sentence "If you say yes, the organization may use your data for research."

Nothing is dropped from the database. Earlier decisions stay in the ledger as history.
