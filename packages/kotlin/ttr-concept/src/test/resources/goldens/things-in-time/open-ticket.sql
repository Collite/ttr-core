SELECT ticket.id
FROM ticket
WHERE EXISTS (
  SELECT 1
  FROM (
    SELECT started.subject AS subject_0, started.occurred AS "at"
    FROM started
  ) starts
  WHERE starts.subject_0 = ticket.id AND starts."at" <= {as_of} AND NOT EXISTS (
    SELECT 1
    FROM (
      SELECT finished.subject AS subject_0, finished.occurred AS "at"
      FROM finished
    ) terminations
    WHERE terminations.subject_0 = starts.subject_0 AND terminations."at" >= starts."at" AND terminations."at" <= {as_of}
  )
)
