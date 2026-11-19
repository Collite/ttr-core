# Accepted TS strictness divergence (review-373 C3)

Control room §54 (⚑MW-P7-S72A-1): TS follows YAML 1.2 flow indentation and
refuses this under-indented double-quoted continuation at 1:6. Kotlin's
SnakeYAML loader is lenient and resolves the same input as `{"x":"a b"}`.
This is a TS-only negative, not a shared Kotlin refusal oracle. Indenting
`b` makes both readers accept it. No parser leniency is added in S7.2a.
