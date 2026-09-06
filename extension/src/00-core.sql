-- Core Oracle runtime semantics required by Orafit.

COMMENT ON SCHEMA orafit IS
'Orafit Oracle runtime compatibility helpers; does not modify PostgreSQL core semantics.';

CREATE FUNCTION orafit.row_offset(value numeric)
RETURNS bigint
LANGUAGE sql IMMUTABLE PARALLEL SAFE
AS $$
SELECT CASE
         WHEN value IS NULL THEN 9223372036854775807::bigint
         WHEN value < 0 THEN 0::bigint
         WHEN value >= 9223372036854775807::numeric THEN 9223372036854775807::bigint
         ELSE trunc(value)::bigint
       END
$$;

CREATE VIEW orafit.dual AS
SELECT 'X'::varchar(1) AS dummy;

CREATE FUNCTION orafit.restart_sequence(target regclass)
RETURNS void
LANGUAGE plpgsql VOLATILE
AS $$
DECLARE
    minimum_value bigint;
BEGIN
    SELECT seqmin
      INTO STRICT minimum_value
      FROM pg_catalog.pg_sequence
     WHERE seqrelid = target;
    PERFORM pg_catalog.setval(target, minimum_value, false);
END
$$;

CREATE FUNCTION orafit.nvl(value anycompatible, fallback anycompatible)
RETURNS anycompatible
LANGUAGE plpgsql IMMUTABLE PARALLEL SAFE
AS $$ BEGIN RETURN COALESCE(value, fallback); END $$;

CREATE FUNCTION orafit.nvl(value text, fallback text)
RETURNS text
LANGUAGE plpgsql IMMUTABLE PARALLEL SAFE
AS $$ BEGIN RETURN COALESCE(NULLIF(value, ''), NULLIF(fallback, '')); END $$;

CREATE FUNCTION orafit.nvl2(
    expression anyelement,
    value_if_not_null anycompatible,
    value_if_null anycompatible)
RETURNS anycompatible
LANGUAGE sql IMMUTABLE PARALLEL SAFE
AS $$ SELECT CASE WHEN $1 IS NOT NULL THEN $2 ELSE $3 END $$;

CREATE FUNCTION orafit.nvl2(
    expression text,
    value_if_not_null text,
    value_if_null text)
RETURNS text
LANGUAGE sql IMMUTABLE PARALLEL SAFE
AS $$
SELECT CASE
    WHEN NULLIF($1, '') IS NOT NULL THEN NULLIF($2, '')
    ELSE NULLIF($3, '')
END
$$;

CREATE FUNCTION orafit.sysdate()
RETURNS timestamp(0) without time zone
LANGUAGE sql STABLE PARALLEL SAFE
AS $$ SELECT CAST(statement_timestamp() AS timestamp(0) without time zone) $$;

CREATE FUNCTION orafit.systimestamp()
RETURNS timestamp with time zone
LANGUAGE sql STABLE PARALLEL SAFE
AS $$ SELECT statement_timestamp() $$;

-- FETCH has a different NULL rule from OFFSET; both truncate fractional counts.
CREATE FUNCTION orafit.row_count(value numeric)
RETURNS bigint LANGUAGE sql IMMUTABLE PARALLEL SAFE
AS $$ SELECT CASE WHEN $1 IS NULL THEN 0::bigint ELSE orafit.row_offset($1) END $$;
