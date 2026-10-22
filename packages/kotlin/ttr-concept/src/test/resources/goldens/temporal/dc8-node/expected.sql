SELECT "condition".condition_id
FROM "condition"
WHERE "condition".code IN (
  SELECT code_closure.descendant_code
  FROM code_closure
  WHERE code_closure.ancestor_code = 'X20-X25'
)
