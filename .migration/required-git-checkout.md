#### 🔴 Remove the Git checkout switch

**Who is affected:** Operators whose chart values or retained `site.yaml` contain `config.gitCheckoutEnabled`.

**Action:** Remove `config.gitCheckoutEnabled` from each values file before you upgrade. The chart refuses the retired key, for both `true` and `false`.

**Behavior:** The server and worker always enable Git checkout. Practice reviews need the repository at its reviewed revision. The webhook role receives no checkout setting.
