// SPDX-License-Identifier: Apache-2.0
package org.apache.calcite.sql.fun;

import org.apache.calcite.sql.SqlFunction;
import org.apache.calcite.sql.type.OperandTypes;
import org.apache.calcite.sql.type.SqlTypeFamily;

/**
 * TF-P1.S1 — the T-SQL {@code DATEDIFF(timeUnit, startdate, enddate)} with datetime operands.
 *
 * <p>Calcite's {@link SqlLibraryOperators#DATEDIFF} declares its operands as the {@code DATE} family,
 * so the validator coerces every {@code datetime} argument with an implicit {@code CAST(… AS DATE)}.
 * SQL Server's {@code DATEDIFF} takes {@code datetime}/{@code datetime2} directly; carried faithfully
 * over the plan.v1 wire, that cast makes {@code DATEDIFF(HOUR, a, b)} count whole days. This instance is
 * the same {@link SqlTimestampDiffFunction} (same validation of the time unit, same return type, no
 * convertlet — like the library one) with {@code DATETIME} operands.
 *
 * <p>It lives in Calcite's package only because {@code SqlTimestampDiffFunction}'s constructor is
 * package-private; nothing else of Calcite's is touched. Reach it through
 * {@code org.tatrman.translator.functions.DateOperators.DATEDIFF}.
 */
public final class TsqlDateDiffFunctions {
  private TsqlDateDiffFunctions() {
  }

  /** {@code DATEDIFF(ANY, DATETIME, DATETIME)}. */
  public static final SqlFunction DATEDIFF =
      new SqlTimestampDiffFunction("DATEDIFF",
          OperandTypes.family(SqlTypeFamily.ANY, SqlTypeFamily.DATETIME,
              SqlTypeFamily.DATETIME));
}
