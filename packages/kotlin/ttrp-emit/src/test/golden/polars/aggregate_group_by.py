import polars as pl
# --- island: grp ---
sales = pl.read_ipc("staging/sales.arrow")
sums = sales.group_by(["region"]).agg([pl.when(pl.col("amount").count() > 0).then(pl.col("amount").sum()).otherwise(None).alias("total"), pl.col("amount").mean().alias("avg_amt"), pl.col("customer").drop_nulls().n_unique().cast(pl.Int64).alias("n_cust")])
