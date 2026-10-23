# cn: promote phase IschemicPatient

Promotes cn candidate `9a4c7e21-3f58-4b06-a1d7-e2b5c80f6d19` — phase IschemicPatient (`cn.IschemicPatient`).

- **producer:** `clinic:evidence`
- **source:** `doc://clinic/du-48213#s4`
- **confidence:** 0.82 (`clinic:evidence` · `code-set-frequency`)
- **proposed:** 2026-10-23T09:31:07Z

### Evidence — 14 question(s) · 9 document(s)

- question `question://clinic/q-2031`: `kolik pacientů s ischemickou chorobou srdeční bylo loni přeloženo?`
- document `doc://clinic/du-48213#s4`: `Dg.: X21.9 akutní infarkt myokardu, st.p. X25.2`
- processor_output `doc://clinic/du-50077#c118-164`: `ischemická choroba srdeční, chronická forma`

### er rendering

The phase as SQL over entities (`definition.render`, er):

```sql
SELECT patient.patient_id
FROM patient
WHERE EXISTS (
  SELECT 1
  FROM "condition"
  WHERE "condition".patient_id = patient.patient_id AND "condition".code IN (
    SELECT code_closure.descendant_code
    FROM code_closure
    WHERE code_closure.ancestor_code = 'X20-X25'
  )
)
```
