SELECT patient.patient_id
FROM patient
WHERE EXISTS (
  SELECT 1
  FROM fracture_episode e
  WHERE e.patient_id = patient.patient_id AND e.period_start <= {as_of} AND (e.period_end IS NULL OR e.period_end >= {as_of})
)
