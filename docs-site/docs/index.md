# Tatrman

**Point Tatrman at the database you already have. Get back a model your people
own, and a governed door your agents can ask questions through — where every
answer arrives with the trail of how it was made.**

Not a chatbot bolted onto your warehouse. The semantics live in git, the
governance is enforced on the way out, and the provenance is attached to the
answer rather than promised in a slide.

---

## Start where your job is

<div class="grid cards" markdown>

- :material-rocket-launch: **[Get running](get-running/index.md)**

    ---

    *I have a database; show me the promise.* The seven-step quickstart, from
    `helm install` to a governed answer with its provenance — in under an hour.

- :material-shape: **[Model](model/index.md)**

    ---

    *I own the semantics.* TTR-M: the layers, bindings, governance, and why the
    model is the deployment artifact.

- :material-power-plug: **[Connect](connect/index.md)**

    ---

    *I build agents.* The MCP surface: doors, the on-behalf-of identity
    contract, the result envelope, and the conformance suite as your harness.

- :material-server: **[Operate](operate/index.md)**

    ---

    *I run this.* The chart's values contract, OIDC, policy in git, and
    one-question-one-trace observability.

</div>

---

## Where the engineering record lives

These pages are the **product documentation** — what Tatrman does and how to use
it.

They are not the engineering record. Design decisions, architecture rationale,
and phase plans live in the repositories themselves (`docs/features/` and
`docs/ecosystem/` in [Collite/ttr-core](https://github.com/Collite/ttr-core)), and
they stay there. Pages here may *distill* that material into an explanation when
it helps you, but they never mirror it — one concept, one home, and this site is
the home for the concepts you need in order to run the product.
