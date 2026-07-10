# Queries

The fluent `QueryBuilder` pushes conditions down to MongrelDB's native indexes
for sub-millisecond lookups - bitmap, learned-range, FM-index full text, HNSW
vector similarity, and more. Each condition type maps to one specialized index;
conditions are AND-ed together.

```scala
val rows = db.query("orders")
  .where("range_f64", Map("column" -> 3L, "min" -> 100.0, "max" -> 500.0))
  .projection(List(1L, 2L))
  .limit(100L)
  .execute()
```

---

## The basics

| Method | Purpose |
|--------|---------|
| `where(condType, params)` | Add a native condition. Multiple calls are AND-ed. |
| `projection(columnIds)` | Return only these column ids (`null` means all columns). |
| `limit(n)` | Cap the number of rows. |
| `build()` | Produce the request payload (useful for debugging). |
| `execute()` | Send and decode. Records the `truncated` flag. |
| `truncated` | Whether the last `execute` hit the limit. |

## Condition types

`params` is a `Map[String, Any]`. Column references use the numeric **column
id**, never the column name.

### `pk` - exact primary-key match

```scala
db.query("orders").where("pk", Map("value" -> 42L)).execute()
```

### `range` - integer range (learned-range index)

```scala
db.query("orders").where("range", Map("column" -> 3L, "min" -> 100L, "max" -> 500L)).execute()
```

### `range_f64` - float range with inclusive/exclusive control

```scala
db.query("orders")
  .where("range_f64", Map("column" -> 3L, "min" -> 100.0, "max" -> 500.0,
                           "min_inclusive" -> true, "max_inclusive" -> false))
  .execute()
```

### `bitmap_eq` - equality on a bitmap-indexed column

```scala
db.query("orders").where("bitmap_eq", Map("column" -> 2L, "value" -> "Alice")).execute()
```

### `bitmap_in` - IN predicate

```scala
db.query("orders").where("bitmap_in", Map("column" -> 2L, "values" -> List("Alice", "Bob"))).execute()
```

### `is_null` / `is_not_null`

```scala
db.query("orders").where("is_null", Map("column" -> 3L)).execute()
```

### `fm_contains` - full-text substring search (FM-index)

Use `pattern` (the server key) or the friendly `value` alias:

```scala
db.query("documents")
  .where("fm_contains", Map("column" -> 2L, "value" -> "database"))
  .limit(10L).execute()
```

### `ann` - dense vector similarity (HNSW)

```scala
db.query("embeddings")
  .where("ann", Map("column" -> 2L, "query" -> List(0.1, 0.2, 0.3, 0.4), "k" -> 10))
  .execute()
```

## Friendly alias translation

| You write | Sent as | Applies to |
|-----------|---------|------------|
| `column` | `column_id` | all condition types |
| `min` | `lo` | `range`, `range_f64` |
| `max` | `hi` | `range`, `range_f64` |
| `min_inclusive` | `lo_inclusive` | `range_f64` |
| `max_inclusive` | `hi_inclusive` | `range_f64` |
| `value` | `pattern` | `fm_contains`, `fm_contains_all` only |

## Limit and the truncated flag

```scala
val q = db.query("orders").where("range", Map("column" -> 3L, "min" -> 0L)).limit(100L)
val rows = q.execute()
if q.truncated then
  println("result capped at " + rows.length)
```

`truncated` returns `false` until `execute` has run, so build a fresh query for
each independent lookup.

## Putting it together

```scala
def topSpenders(db: MongrelDB, customer: String): List[Map[String, Any]] =
  val q = db.query("orders")
    .where("bitmap_eq", Map("column" -> 2L, "value" -> customer))
    .where("range", Map("column" -> 3L, "min" -> 100L))
    .projection(List(1L, 3L))
    .limit(50L)
  val rows = q.execute()
  if q.truncated then println("warning: topSpenders result capped at 50")
  rows
```

For arbitrary predicates, joins, and aggregations, use SQL - see [sql.md](sql.md).
