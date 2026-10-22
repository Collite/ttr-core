SELECT patient.patient_id FROM patient WHERE EXISTS (SELECT 1 FROM fracture_episode e WHERE e.patient_id = patient.patient_id AND e.period_end IS NOT NULL AND e.period_end < {as_of})
