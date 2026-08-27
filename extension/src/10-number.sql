-- NUMBER conversion and numeric Oracle semantics.

CREATE FUNCTION orafit.to_number(value text, nls_numeric_characters text)
RETURNS numeric
LANGUAGE plpgsql IMMUTABLE PARALLEL SAFE
AS $$
DECLARE
    normalized text;
    decimal_character text;
    group_character text;
BEGIN
    IF value IS NULL OR btrim(value) = '' THEN RETURN NULL; END IF;
    IF nls_numeric_characters IS NULL
       OR length(nls_numeric_characters) <> 2
       OR substr(nls_numeric_characters, 1, 1) = substr(nls_numeric_characters, 2, 1) THEN
        RAISE EXCEPTION 'invalid NLS_NUMERIC_CHARACTERS value: %', nls_numeric_characters
            USING ERRCODE = '22023';
    END IF;
    decimal_character := substr(nls_numeric_characters, 1, 1);
    group_character := substr(nls_numeric_characters, 2, 1);
    normalized := replace(btrim(value), group_character, '');
    IF decimal_character <> '.' THEN
        normalized := replace(normalized, decimal_character, '.');
    END IF;
    RETURN normalized::numeric;
EXCEPTION
    WHEN invalid_text_representation OR numeric_value_out_of_range THEN
        RAISE EXCEPTION 'ORA-01722: invalid number' USING ERRCODE = '42000';
END
$$;

CREATE FUNCTION orafit.to_number(value text)
RETURNS numeric
LANGUAGE sql IMMUTABLE PARALLEL SAFE
AS $$ SELECT orafit.to_number($1, '.,') $$;

CREATE FUNCTION orafit.to_number_deferred(value text)
RETURNS numeric
LANGUAGE plpgsql VOLATILE PARALLEL SAFE
AS $$
BEGIN
    RETURN orafit.to_number(value);
END
$$;

-- PreparedStatement parameters can reach PostgreSQL with different concrete OIDs.
-- Keep only the bind-sensitive conversion that current Java lowering actually emits.
CREATE FUNCTION orafit.to_number_bind(value text, nls_numeric_characters text DEFAULT '.,')
RETURNS numeric LANGUAGE sql IMMUTABLE PARALLEL SAFE
AS $$ SELECT orafit.to_number($1, $2) $$;
CREATE FUNCTION orafit.to_number_bind(value numeric, nls_numeric_characters text DEFAULT '.,')
RETURNS numeric LANGUAGE sql IMMUTABLE PARALLEL SAFE AS $$ SELECT $1 $$;
CREATE FUNCTION orafit.to_number_bind(value smallint, nls_numeric_characters text DEFAULT '.,')
RETURNS numeric LANGUAGE sql IMMUTABLE PARALLEL SAFE AS $$ SELECT $1::numeric $$;
CREATE FUNCTION orafit.to_number_bind(value integer, nls_numeric_characters text DEFAULT '.,')
RETURNS numeric LANGUAGE sql IMMUTABLE PARALLEL SAFE AS $$ SELECT $1::numeric $$;
CREATE FUNCTION orafit.to_number_bind(value bigint, nls_numeric_characters text DEFAULT '.,')
RETURNS numeric LANGUAGE sql IMMUTABLE PARALLEL SAFE AS $$ SELECT $1::numeric $$;
CREATE FUNCTION orafit.to_number_bind(value real, nls_numeric_characters text DEFAULT '.,')
RETURNS numeric LANGUAGE sql IMMUTABLE PARALLEL SAFE AS $$ SELECT $1::numeric $$;
CREATE FUNCTION orafit.to_number_bind(value double precision, nls_numeric_characters text DEFAULT '.,')
RETURNS numeric LANGUAGE sql IMMUTABLE PARALLEL SAFE AS $$ SELECT $1::numeric $$;

-- Deterministic numeric TO_CHAR subset. The Java rule admits only 9/0 models with one literal dot,
-- avoiding NLS-dependent D/G/L/C format elements.
CREATE FUNCTION orafit.to_char_number_format(value numeric, format text)
RETURNS text
LANGUAGE sql STABLE STRICT PARALLEL SAFE
AS $$ SELECT pg_catalog.to_char($1, $2) $$;

CREATE FUNCTION orafit.trunc(value numeric)
RETURNS numeric
LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE
AS $$ SELECT pg_catalog.trunc($1) $$;

CREATE FUNCTION orafit.trunc(value numeric, places numeric)
RETURNS numeric
LANGUAGE plpgsql IMMUTABLE STRICT PARALLEL SAFE
AS $$
DECLARE integer_places integer;
BEGIN
    integer_places := pg_catalog.trunc(places)::integer;
    RETURN pg_catalog.trunc(value, integer_places);
EXCEPTION
    WHEN numeric_value_out_of_range THEN
        RAISE EXCEPTION 'Orafit: Oracle TRUNC numeric precision is outside the supported integer range'
            USING ERRCODE = '0A000';
END
$$;

CREATE FUNCTION orafit.round(value numeric)
RETURNS numeric
LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE
AS $$ SELECT pg_catalog.round($1) $$;

CREATE FUNCTION orafit.round(value numeric, places numeric)
RETURNS numeric
LANGUAGE plpgsql IMMUTABLE STRICT PARALLEL SAFE
AS $$
DECLARE integer_places integer;
BEGIN
    integer_places := pg_catalog.trunc(places)::integer;
    RETURN pg_catalog.round(value, integer_places);
EXCEPTION
    WHEN numeric_value_out_of_range THEN
        RAISE EXCEPTION 'Orafit: Oracle ROUND numeric precision is outside the supported integer range'
            USING ERRCODE = '0A000';
END
$$;

CREATE FUNCTION orafit.greatest_number(VARIADIC input_values numeric[])
RETURNS numeric
LANGUAGE sql IMMUTABLE PARALLEL SAFE
AS $$
    SELECT CASE
        WHEN array_position($1, NULL::numeric) IS NOT NULL THEN NULL
        ELSE (SELECT max(value) FROM unnest($1) AS input(value))
    END
$$;

CREATE FUNCTION orafit.least_number(VARIADIC input_values numeric[])
RETURNS numeric
LANGUAGE sql IMMUTABLE PARALLEL SAFE
AS $$
    SELECT CASE
        WHEN array_position($1, NULL::numeric) IS NOT NULL THEN NULL
        ELSE (SELECT min(value) FROM unnest($1) AS input(value))
    END
$$;

-- Ordinary Oracle numeric contexts accept already-numeric values without conversion.
CREATE FUNCTION orafit.to_number(value numeric)
RETURNS numeric LANGUAGE sql IMMUTABLE PARALLEL SAFE AS $$ SELECT $1 $$;
CREATE FUNCTION orafit.to_number(value smallint)
RETURNS numeric LANGUAGE sql IMMUTABLE PARALLEL SAFE AS $$ SELECT $1::numeric $$;
CREATE FUNCTION orafit.to_number(value integer)
RETURNS numeric LANGUAGE sql IMMUTABLE PARALLEL SAFE AS $$ SELECT $1::numeric $$;
CREATE FUNCTION orafit.to_number(value bigint)
RETURNS numeric LANGUAGE sql IMMUTABLE PARALLEL SAFE AS $$ SELECT $1::numeric $$;
CREATE FUNCTION orafit.to_number(value real)
RETURNS numeric LANGUAGE sql IMMUTABLE PARALLEL SAFE AS $$ SELECT $1::numeric $$;
CREATE FUNCTION orafit.to_number(value double precision)
RETURNS numeric LANGUAGE sql IMMUTABLE PARALLEL SAFE AS $$ SELECT $1::numeric $$;
