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
    IF value IS NULL OR value = '' THEN RETURN NULL; END IF;
    IF nls_numeric_characters IS NULL
       OR length(nls_numeric_characters) <> 2
       OR substr(nls_numeric_characters, 1, 1) = substr(nls_numeric_characters, 2, 1) THEN
        RAISE EXCEPTION 'invalid NLS_NUMERIC_CHARACTERS value: %', nls_numeric_characters
            USING ERRCODE = '22023';
    END IF;
    decimal_character := substr(nls_numeric_characters, 1, 1);
    group_character := substr(nls_numeric_characters, 2, 1);
    -- Without a format model Oracle accepts decimal text, not group separators
    -- or PostgreSQL extensions such as NaN, Infinity, radix prefixes and underscores.
    normalized := btrim(value);
    IF strpos(normalized, group_character) > 0 THEN
        RAISE invalid_text_representation;
    END IF;
    IF decimal_character <> '.' THEN
        normalized := replace(normalized, decimal_character, '.');
    END IF;
    IF normalized !~ '^[+-]?([0-9]+([.][0-9]*)?|[.][0-9]+)([eE][+-]?[0-9]+)?$' THEN
        RAISE invalid_text_representation;
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
LANGUAGE plpgsql STABLE STRICT PARALLEL SAFE
AS $$
DECLARE formatted text := pg_catalog.to_char(value, format);
BEGIN
    IF strpos(formatted, '#') > 0 THEN RETURN repeat('#', length(formatted)); END IF;
    IF value < 0 AND strpos(formatted, '-') = 0 THEN
        formatted := regexp_replace(formatted, ' ([0-9.])', '-\1');
    END IF;
    RETURN formatted;
END
$$;

CREATE FUNCTION orafit.ceil(value numeric)
RETURNS numeric LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE
AS $$ SELECT pg_catalog.ceil($1) $$;

CREATE FUNCTION orafit.mod(dividend numeric, divisor numeric)
RETURNS numeric
LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE
AS $$ SELECT CASE WHEN $2 = 0 THEN $1 ELSE pg_catalog.mod($1, $2) END $$;

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
-- NUMBER division retains twenty base-100 digits. Use integer quotient/remainder
-- for rounding so PostgreSQL's default numeric division scale cannot lose digits.
CREATE FUNCTION orafit.divide(dividend numeric, divisor numeric)
RETURNS numeric LANGUAGE plpgsql IMMUTABLE STRICT PARALLEL SAFE
AS $$
DECLARE
    a numeric := abs(dividend);
    b numeric := abs(divisor);
    exponent integer;
    places integer;
    factor numeric;
    quotient numeric;
BEGIN
    IF divisor = 0 THEN
        RAISE EXCEPTION 'ORA-01476: divisor is equal to zero' USING ERRCODE = '22012';
    END IF;
    IF dividend = 0 THEN RETURN 0; END IF;
    IF a = 'NaN'::numeric OR b = 'NaN'::numeric OR a = 'Infinity'::numeric OR b = 'Infinity'::numeric THEN
        RAISE EXCEPTION 'Orafit: non-finite NUMBER division is unsupported' USING ERRCODE = '0A000';
    END IF;
    exponent := floor(log(100::numeric, a) - log(100::numeric, b));
    -- Correct logarithm rounding at exact powers of 100 by comparing integers.
    WHILE a < b * power(100::numeric, exponent) LOOP exponent := exponent - 1; END LOOP;
    WHILE a >= b * power(100::numeric, exponent + 1) LOOP exponent := exponent + 1; END LOOP;
    IF exponent < -65 THEN RETURN 0; END IF;
    IF exponent > 62 THEN
        RAISE EXCEPTION 'ORA-01426: numeric overflow' USING ERRCODE = '22003';
    END IF;
    places := 38 - 2 * exponent;
    factor := power(10::numeric, abs(places));
    IF places >= 0 THEN a := a * factor; ELSE b := b * factor; END IF;
    quotient := div(a, b);
    IF mod(a, b) * 2 >= b THEN quotient := quotient + 1; END IF;
    RETURN sign(dividend) * sign(divisor) * quotient * power(10::numeric, -places);
END
$$;
