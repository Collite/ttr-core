Review-382 F5 / D-MW-107 codec regressions. Expected YAML is produced by Kotlin's
CanonicalNormalization + YamlText, and compared byte-for-byte by both Kotlin and TS.
Unordered entity bodies sort free keys lexically (`10`, `9`, `b`); ordered cn kind
bodies keep the authored property sequence (`10`, `2`, `b`, `1`).

These deliberately use free properties outside the current canonical/profile
schema. They test codec normalization directly, rather than profile operations or
canonical parsing. The valid tree/canonical profile corpus is unchanged.
