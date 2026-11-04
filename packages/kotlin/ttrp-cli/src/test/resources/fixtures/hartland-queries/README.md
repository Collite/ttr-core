Query projection regression fixture copied from hartland model/{db,er,binding,queries} at df8c9f4751e6e729f8925bdee5609fdc6ffee33f.
Includes the complete 25-query set and its ER/database/mapping dependencies, including customer_channel_overlap / bool_or.
No query has been simplified. Kept as a frozen producer fixture; no hartland change is required.
The CLI test relocates these unchanged sources into their declared hartland package and supplies the stock CNC role vocabulary; the projector test loads the snapshot directly.
