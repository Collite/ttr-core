SELECT ticket.id
FROM ticket
WHERE EXISTS (
  SELECT 1
  FROM (
    SELECT starts.subject_0 AS subject_0, starts."at" AS start_at, MIN(terminations."at") AS end_at
    FROM (
      SELECT started.subject AS subject_0, started.occurred AS "at", LEAD(started.occurred) OVER (PARTITION BY started.subject ORDER BY started.occurred) AS next_start
      FROM started
    ) starts
    LEFT JOIN (
      SELECT finished.subject AS subject_0, finished.occurred AS "at"
      FROM finished
    ) terminations ON starts.subject_0 = terminations.subject_0 AND terminations."at" > starts."at"
    GROUP BY starts.subject_0, starts."at", starts.next_start
  ) episodes
  WHERE episodes.subject_0 = ticket.id AND episodes.start_at <= {as_of} AND (episodes.end_at IS NULL OR episodes.end_at > {as_of})
)
