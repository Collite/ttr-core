-- CN-P3 S3.4 T7 — invented rows for the live run of the clinic er goldens (`ConceptLiveSpec`): the tables are created
-- from the world's db tier, these rows go in after (invented patients and X-codes; nothing from a client). The live
-- suite binds `as_of` to 2026-03-15, `window_lo` to 2025-03-15 and `as_of_hi` to 2026-03-16.

INSERT INTO patient (patient_id, birth_year) VALUES (1, 1950), (2, 1972), (3, 1990);

INSERT INTO encounter (encounter_id, patient_id, department_id, admitted_at, discharged_at) VALUES
    (11, 1, 7, TIMESTAMP '2026-03-01 08:00:00', TIMESTAMP '2026-03-04 12:00:00'),
    (12, 1, 7, TIMESTAMP '2026-03-10 09:30:00', NULL),               -- patient 1: two encounters in the window
    (13, 2, 8, TIMESTAMP '2025-11-02 10:00:00', TIMESTAMP '2025-11-05 10:00:00');

INSERT INTO transfer (transfer_id, patient_id, from_encounter, to_encounter, at, transfer_kind) VALUES
    (21, 1, 11, 12, TIMESTAMP '2026-03-04 12:00:00', 'internal');

INSERT INTO diagnosis_code (code, title) VALUES
    ('X20-X25', 'Ischemic block'), ('X21', 'Acute infarction'), ('X21.1', 'Acute infarction, anterior'), ('X24', 'Chronic ischemia');

INSERT INTO code_closure (ancestor_code, descendant_code) VALUES
    ('X20-X25', 'X20-X25'), ('X20-X25', 'X21'), ('X20-X25', 'X21.1'), ('X20-X25', 'X24'),
    ('X21', 'X21'), ('X21', 'X21.1'), ('X21.1', 'X21.1'), ('X24', 'X24');

INSERT INTO "condition" (condition_id, patient_id, encounter_id, code, certainty, experiencer, onset, onset_hi, onset_granularity, abatement) VALUES
    (31, 1, 11, 'X21.1', 'confirmed', 'patient', DATE '2026-03-01', DATE '2026-04-01', 'month', NULL),  -- March: possibly, not certainly, by 03-15
    (32, 2, 13, 'X24',   'confirmed', 'patient', DATE '2025-11-02', DATE '2025-11-03', 'day',   NULL),
    (33, 3, 13, 'X21',   'suspected', 'family',  DATE '2025-06-01', DATE '2025-07-01', 'month', NULL);  -- neither confirmed nor the patient's

INSERT INTO fracture_episode (episode_id, patient_id, period_start, period_end, end_state) VALUES
    (41, 1, DATE '2026-03-01', NULL,              'open'),      -- running: has a broken leg
    (42, 2, DATE '2024-05-01', DATE '2024-07-01', 'closed');    -- ended: a history of a fracture

INSERT INTO observation (observation_id, patient_id, analyte, value, observed_at) VALUES
    (51, 1, 'glucose', 6.1, TIMESTAMP '2026-03-02 07:00:00');
