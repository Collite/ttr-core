# Transfer — the write frame

Source: `design/09-grounding-and-frames.md` §5 (the acceptance case for assertions), world `clinic`. A `Transfer`
`assertion.v1` (S3.1's samples) with labelled participants (`patient`, `from`, `to`), a period and `asserted_as_of`.

| case | call | stage |
|---|---|---|
| `transfer` | `assertionLower` — the `transfer` row: `patient_id` / `from_encounter` / `to_encounter` = the members' values through the labelled relation paths, `at` from `period.start`, `transfer_kind` from the quality; `identity.new` → no `transfer_id`; `assertedAsOf` carried beside the rows | S3.5 |
| `transfer-known` | the same with `identity.known` → `transfer_id` set | S3.5 |
| `validate` | `assertionValidate` — no finding | S3.2 |
| `explain-en` · `explain-cs` | `assertionExplain` — the inspector's sentence | S3.6 |

Oracle: the model's relation pairs, read by hand — `transfer_patient` (`transfer.patient_id → patient.patient_id`),
`transfer_from` (`from_encounter → encounter.encounter_id`), `transfer_to` (`to_encounter → encounter.encounter_id`):
each participant's member value lands on its path's first foreign key. Invented names and codes only.

Pending convention, case format and oracles: [`../README.md`](../README.md).
