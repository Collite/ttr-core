# dialect-negative/demo-cs

The `demo-cs/1` negative fixtures (ttr-profile `src/test/resources/demo-cs/negative/`) as one dialect tree, served by
`ttr-metadata-cli lint` (MW-P3 S3.5). `tree/` holds the profiles, `ttr.yaml`, `model.ignore` and the sidecar of
`../../dialect/demo-cs/tree`; `unknown-key.yaml` names its record `Zakaznik` so it does not collide with `conflict.yaml`'s
`Customer` once both load in one run.

`unimported.yaml` (S3.7 T0) is the deliberately broken fan-out vector `../../dialect/demo-cs/tree/03-mixed.yaml` was
until then: its binding names `Dodavatel` bare, which the binding document's imports do not reach
(`ttr/unimported-reference`, annotated on the file itself). The positive demo-cs tree and its twin are `lint --strict`
clean.

`expected-github.txt` is `lint --root . --strict --format github` run inside `tree/`: one `::error` per file, on the
authored key or record, with the codes and positions `negative/expect.yaml` pins for each file alone. Exit 1.
