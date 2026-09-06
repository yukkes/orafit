-- VARCHAR2 and Oracle string semantics.

CREATE FUNCTION orafit._number_to_varchar2(value numeric, nls_numeric_characters text)
RETURNS text
LANGUAGE plpgsql IMMUTABLE PARALLEL SAFE
AS $$
DECLARE
    result text;
    decimal_character text;
BEGIN
    IF value IS NULL THEN RETURN NULL; END IF;
    IF nls_numeric_characters IS NULL
       OR length(nls_numeric_characters) <> 2
       OR substr(nls_numeric_characters, 1, 1) = substr(nls_numeric_characters, 2, 1) THEN
        RAISE EXCEPTION 'invalid NLS_NUMERIC_CHARACTERS value: %', nls_numeric_characters
            USING ERRCODE = '22023';
    END IF;
    decimal_character := substr(nls_numeric_characters, 1, 1);
    -- Oracle NUMBER stores a value, not a declared column scale. Its default character
    -- conversion therefore omits insignificant fractional zeros, unlike PostgreSQL numeric.
    result := pg_catalog.trim_scale(value)::text;
    result := regexp_replace(result, '^(-?)0[.]', '\1.');
    IF decimal_character <> '.' THEN result := replace(result, '.', decimal_character); END IF;
    RETURN result;
END
$$;

CREATE FUNCTION orafit.to_varchar2(
    value text, date_format text, timestamp_format text, timestamp_tz_format text,
    nls_numeric_characters text)
RETURNS text LANGUAGE sql IMMUTABLE PARALLEL SAFE AS $$ SELECT NULLIF($1, '') $$;
CREATE FUNCTION orafit.to_varchar2(
    value numeric, date_format text, timestamp_format text, timestamp_tz_format text,
    nls_numeric_characters text)
RETURNS text LANGUAGE sql IMMUTABLE PARALLEL SAFE
AS $$ SELECT orafit._number_to_varchar2($1, $5) $$;
CREATE FUNCTION orafit.to_varchar2(
    value smallint, date_format text, timestamp_format text, timestamp_tz_format text,
    nls_numeric_characters text)
RETURNS text LANGUAGE sql IMMUTABLE PARALLEL SAFE
AS $$ SELECT orafit._number_to_varchar2($1::numeric, $5) $$;
CREATE FUNCTION orafit.to_varchar2(
    value integer, date_format text, timestamp_format text, timestamp_tz_format text,
    nls_numeric_characters text)
RETURNS text LANGUAGE sql IMMUTABLE PARALLEL SAFE
AS $$ SELECT orafit._number_to_varchar2($1::numeric, $5) $$;
CREATE FUNCTION orafit.to_varchar2(
    value bigint, date_format text, timestamp_format text, timestamp_tz_format text,
    nls_numeric_characters text)
RETURNS text LANGUAGE sql IMMUTABLE PARALLEL SAFE
AS $$ SELECT orafit._number_to_varchar2($1::numeric, $5) $$;
CREATE FUNCTION orafit.to_varchar2(
    value real, date_format text, timestamp_format text, timestamp_tz_format text,
    nls_numeric_characters text)
RETURNS text LANGUAGE sql IMMUTABLE PARALLEL SAFE
AS $$ SELECT orafit._number_to_varchar2($1::numeric, $5) $$;
CREATE FUNCTION orafit.to_varchar2(
    value double precision, date_format text, timestamp_format text, timestamp_tz_format text,
    nls_numeric_characters text)
RETURNS text LANGUAGE sql IMMUTABLE PARALLEL SAFE
AS $$ SELECT orafit._number_to_varchar2($1::numeric, $5) $$;
CREATE FUNCTION orafit.to_varchar2(
    value date, date_format text, timestamp_format text, timestamp_tz_format text,
    nls_numeric_characters text)
RETURNS text LANGUAGE sql STABLE PARALLEL SAFE
AS $$ SELECT pg_catalog.to_char($1::timestamp, orafit._pg_datetime_format($2)) $$;
CREATE FUNCTION orafit.to_varchar2(
    value timestamp without time zone, date_format text, timestamp_format text,
    timestamp_tz_format text, nls_numeric_characters text)
RETURNS text LANGUAGE sql STABLE PARALLEL SAFE
AS $$ SELECT pg_catalog.to_char($1, orafit._pg_datetime_format($3)) $$;
CREATE FUNCTION orafit.to_varchar2(
    value timestamp with time zone, date_format text, timestamp_format text,
    timestamp_tz_format text, nls_numeric_characters text)
RETURNS text LANGUAGE sql STABLE PARALLEL SAFE
AS $$ SELECT pg_catalog.to_char($1, orafit._pg_datetime_format($4)) $$;

-- Explicit TO_CHAR formats use PostgreSQL type dispatch instead of JDBC schema lookups.
CREATE FUNCTION orafit.to_char_format(value date, format text)
RETURNS text
LANGUAGE sql STABLE STRICT PARALLEL SAFE
AS $$ SELECT pg_catalog.to_char($1::timestamp, orafit._pg_datetime_format($2)) $$;

CREATE FUNCTION orafit.to_char_format(value timestamp without time zone, format text)
RETURNS text
LANGUAGE sql STABLE STRICT PARALLEL SAFE
AS $$ SELECT pg_catalog.to_char($1, orafit._pg_datetime_format($2)) $$;

CREATE FUNCTION orafit.to_char_format(value timestamp with time zone, format text)
RETURNS text
LANGUAGE sql STABLE STRICT PARALLEL SAFE
AS $$ SELECT pg_catalog.to_char($1, orafit._pg_datetime_format($2)) $$;

CREATE FUNCTION orafit.to_char_format(value anyelement, format text)
RETURNS text
LANGUAGE plpgsql STABLE STRICT PARALLEL SAFE
AS $$
BEGIN
    RAISE EXCEPTION 'ORA-01481: invalid number format model' USING ERRCODE = '72000';
END
$$;

CREATE FUNCTION orafit.concat_varchar2(left_value text, right_value text)
RETURNS text
LANGUAGE sql IMMUTABLE PARALLEL SAFE
AS $$ SELECT NULLIF(COALESCE($1, '') || COALESCE($2, ''), '') $$;

CREATE FUNCTION orafit.replace(value text, search text, replacement text DEFAULT NULL)
RETURNS text LANGUAGE sql IMMUTABLE PARALLEL SAFE
AS $$ SELECT NULLIF(pg_catalog.replace($1, COALESCE($2, ''), COALESCE($3, '')), '') $$;

CREATE FUNCTION orafit.translate(value text, source text, target text)
RETURNS text LANGUAGE sql IMMUTABLE PARALLEL SAFE
AS $$ SELECT NULLIF(pg_catalog.translate(NULLIF($1, ''), NULLIF($2, ''), NULLIF($3, '')), '') $$;

CREATE FUNCTION orafit.substr(value text, start_position numeric)
RETURNS text
LANGUAGE plpgsql IMMUTABLE PARALLEL SAFE
AS $$
DECLARE
    p numeric;
    source_length integer;
BEGIN
    IF value IS NULL OR value = '' OR start_position IS NULL THEN RETURN NULL; END IF;
    source_length := char_length(value);
    p := trunc(start_position);
    IF p = 0 THEN p := 1; END IF;
    IF p < 0 THEN p := source_length + p + 1; END IF;
    IF p < 1 OR p > source_length THEN RETURN NULL; END IF;
    RETURN NULLIF(pg_catalog.substr(value, p::integer), '');
END
$$;

CREATE FUNCTION orafit.substr(value text, start_position numeric, substring_length numeric)
RETURNS text
LANGUAGE plpgsql IMMUTABLE PARALLEL SAFE
AS $$
DECLARE
    p numeric;
    requested numeric;
    source_length integer;
    actual_length integer;
BEGIN
    IF value IS NULL OR value = '' OR start_position IS NULL OR substring_length IS NULL THEN
        RETURN NULL;
    END IF;
    source_length := char_length(value);
    p := trunc(start_position);
    requested := trunc(substring_length);
    IF requested < 1 THEN RETURN NULL; END IF;
    IF p = 0 THEN p := 1; END IF;
    IF p < 0 THEN p := source_length + p + 1; END IF;
    IF p < 1 OR p > source_length THEN RETURN NULL; END IF;
    actual_length := LEAST(requested, source_length - p + 1)::integer;
    RETURN NULLIF(pg_catalog.substr(value, p::integer, actual_length), '');
END
$$;

CREATE FUNCTION orafit.instr(
    value text, needle text, start_position numeric, occurrence numeric)
RETURNS numeric
LANGUAGE plpgsql IMMUTABLE PARALLEL SAFE
AS $$
DECLARE
    p numeric;
    wanted numeric;
    source_length integer;
    needle_length integer;
    cursor_pos integer;
    found_count integer := 0;
BEGIN
    IF value IS NULL OR value = '' OR needle IS NULL OR needle = ''
       OR start_position IS NULL OR occurrence IS NULL THEN RETURN NULL; END IF;
    p := trunc(start_position);
    wanted := trunc(occurrence);
    IF p = 0 THEN
        RAISE EXCEPTION 'ORA-01428: argument ''0'' is out of range' USING ERRCODE = '72000';
    END IF;
    IF wanted < 1 THEN
        RAISE EXCEPTION 'ORA-01428: occurrence must be positive' USING ERRCODE = '72000';
    END IF;
    IF wanted > 2147483647 THEN RETURN 0; END IF;
    source_length := char_length(value);
    needle_length := char_length(needle);
    IF p > 0 THEN
        IF p > source_length THEN RETURN 0; END IF;
        cursor_pos := p::integer;
        WHILE cursor_pos <= source_length - needle_length + 1 LOOP
            IF pg_catalog.substr(value, cursor_pos, needle_length) = needle THEN
                found_count := found_count + 1;
                IF found_count = wanted::integer THEN RETURN cursor_pos; END IF;
            END IF;
            cursor_pos := cursor_pos + 1;
        END LOOP;
        RETURN 0;
    END IF;
    p := source_length + p + 1;
    IF p < 1 THEN RETURN 0; END IF;
    cursor_pos := LEAST(p, source_length - needle_length + 1)::integer;
    WHILE cursor_pos >= 1 LOOP
        IF pg_catalog.substr(value, cursor_pos, needle_length) = needle THEN
            found_count := found_count + 1;
            IF found_count = wanted::integer THEN RETURN cursor_pos; END IF;
        END IF;
        cursor_pos := cursor_pos - 1;
    END LOOP;
    RETURN 0;
END
$$;

CREATE FUNCTION orafit.instr(value text, needle text)
RETURNS numeric LANGUAGE sql IMMUTABLE PARALLEL SAFE
AS $$ SELECT orafit.instr($1, $2, 1::numeric, 1::numeric) $$;
CREATE FUNCTION orafit.instr(value text, needle text, start_position numeric)
RETURNS numeric LANGUAGE sql IMMUTABLE PARALLEL SAFE
AS $$ SELECT orafit.instr($1, $2, $3, 1::numeric) $$;

-- Oracle NVL chooses the first argument family and converts the fallback only when needed.
CREATE FUNCTION orafit.nvl(value text, fallback numeric)
RETURNS text
LANGUAGE sql IMMUTABLE PARALLEL SAFE
AS $$
SELECT CASE
    WHEN NULLIF($1, '') IS NOT NULL THEN NULLIF($1, '')
    ELSE orafit._number_to_varchar2($2, '.,')
END
$$;

CREATE FUNCTION orafit.nvl(value numeric, fallback text)
RETURNS numeric
LANGUAGE plpgsql IMMUTABLE PARALLEL SAFE
AS $$
BEGIN
    IF value IS NOT NULL THEN RETURN value; END IF;
    RETURN orafit.to_number(fallback);
END
$$;

CREATE FUNCTION orafit.nvl(value numeric, fallback integer)
RETURNS numeric LANGUAGE sql IMMUTABLE PARALLEL SAFE
AS $$ SELECT COALESCE($1, $2::numeric) $$;

CREATE FUNCTION orafit.nvl(value numeric, fallback bigint)
RETURNS numeric LANGUAGE sql IMMUTABLE PARALLEL SAFE
AS $$ SELECT COALESCE($1, $2::numeric) $$;

CREATE FUNCTION orafit.nvl(value numeric, fallback smallint)
RETURNS numeric LANGUAGE sql IMMUTABLE PARALLEL SAFE
AS $$ SELECT COALESCE($1, $2::numeric) $$;

-- LPAD and RPAD share Oracle length, empty-string, and range handling.
CREATE FUNCTION orafit._pad(
    value text, target_length numeric, fill text, left_side boolean)
RETURNS text
LANGUAGE plpgsql IMMUTABLE PARALLEL SAFE
AS $$
DECLARE
    requested integer;
    padded text;
BEGIN
    IF value IS NULL OR value = '' OR target_length IS NULL OR fill IS NULL OR fill = '' THEN
        RETURN NULL;
    END IF;
    requested := pg_catalog.trunc(target_length)::integer;
    IF left_side THEN
        padded := pg_catalog.lpad(value, requested, fill);
    ELSE
        padded := pg_catalog.rpad(value, requested, fill);
    END IF;
    RETURN NULLIF(padded, '');
EXCEPTION
    WHEN numeric_value_out_of_range THEN
        RAISE EXCEPTION 'Orafit: Oracle padding length is outside the supported integer range'
            USING ERRCODE = '0A000';
END
$$;

CREATE FUNCTION orafit.lpad(value text, target_length numeric, fill text)
RETURNS text
LANGUAGE sql IMMUTABLE PARALLEL SAFE
AS $$ SELECT orafit._pad($1, $2, $3, true) $$;

CREATE FUNCTION orafit.lpad(value text, target_length numeric)
RETURNS text
LANGUAGE sql IMMUTABLE PARALLEL SAFE
AS $$ SELECT orafit._pad($1, $2, ' ', true) $$;

CREATE FUNCTION orafit.rpad(value text, target_length numeric, fill text)
RETURNS text
LANGUAGE sql IMMUTABLE PARALLEL SAFE
AS $$ SELECT orafit._pad($1, $2, $3, false) $$;

CREATE FUNCTION orafit.rpad(value text, target_length numeric)
RETURNS text
LANGUAGE sql IMMUTABLE PARALLEL SAFE
AS $$ SELECT orafit._pad($1, $2, ' ', false) $$;

-- TRIM uses a single trim character; LTRIM/RTRIM use a character set.
CREATE FUNCTION orafit.trim(value text, direction text)
RETURNS text
LANGUAGE plpgsql IMMUTABLE PARALLEL SAFE
AS $$
BEGIN
    IF value IS NULL OR value = '' THEN RETURN NULL; END IF;
    RETURN NULLIF(
        CASE upper(direction)
            WHEN 'LEADING' THEN pg_catalog.ltrim(value, ' ')
            WHEN 'TRAILING' THEN pg_catalog.rtrim(value, ' ')
            WHEN 'BOTH' THEN pg_catalog.btrim(value, ' ')
            ELSE NULL
        END,
        '');
END
$$;

CREATE FUNCTION orafit.trim(value text, trim_character text, direction text)
RETURNS text
LANGUAGE plpgsql IMMUTABLE PARALLEL SAFE
AS $$
BEGIN
    IF value IS NULL OR value = '' OR trim_character IS NULL OR trim_character = '' THEN
        RETURN NULL;
    END IF;
    IF char_length(trim_character) <> 1 THEN
        RAISE EXCEPTION 'ORA-30001: trim set should have only one character'
            USING ERRCODE = '72000';
    END IF;
    RETURN NULLIF(
        CASE upper(direction)
            WHEN 'LEADING' THEN pg_catalog.ltrim(value, trim_character)
            WHEN 'TRAILING' THEN pg_catalog.rtrim(value, trim_character)
            WHEN 'BOTH' THEN pg_catalog.btrim(value, trim_character)
            ELSE NULL
        END,
        '');
END
$$;

CREATE FUNCTION orafit.ltrim(value text, trim_set text)
RETURNS text
LANGUAGE sql IMMUTABLE PARALLEL SAFE
AS $$ SELECT NULLIF(pg_catalog.ltrim(NULLIF($1, ''), NULLIF($2, '')), '') $$;

CREATE FUNCTION orafit.ltrim(value text)
RETURNS text
LANGUAGE sql IMMUTABLE PARALLEL SAFE
AS $$ SELECT NULLIF(pg_catalog.ltrim(NULLIF($1, ''), ' '), '') $$;

CREATE FUNCTION orafit.rtrim(value text, trim_set text)
RETURNS text
LANGUAGE sql IMMUTABLE PARALLEL SAFE
AS $$ SELECT NULLIF(pg_catalog.rtrim(NULLIF($1, ''), NULLIF($2, '')), '') $$;

CREATE FUNCTION orafit.rtrim(value text)
RETURNS text
LANGUAGE sql IMMUTABLE PARALLEL SAFE
AS $$ SELECT NULLIF(pg_catalog.rtrim(NULLIF($1, ''), ' '), '') $$;
-- Casting bpchar to text discards trailing blanks in PostgreSQL. Recover only
-- those padding bytes; all non-padding characters retain their original width.
CREATE FUNCTION orafit.char_text(value bpchar)
RETURNS text LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE
AS $$ SELECT rpad($1::text, char_length($1::text) + octet_length($1) - octet_length($1::text), ' ') $$;

CREATE FUNCTION orafit.length(value bpchar)
RETURNS numeric LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE
AS $$ SELECT char_length(orafit.char_text($1))::numeric $$;

CREATE FUNCTION orafit.cast_char(value text, width integer, byte_semantics boolean)
RETURNS bpchar LANGUAGE plpgsql IMMUTABLE STRICT PARALLEL SAFE
AS $$
DECLARE padding integer;
BEGIN
    IF value = '' THEN RETURN NULL; END IF;
    padding := width - CASE WHEN byte_semantics THEN octet_length(value) ELSE char_length(value) END;
    IF padding < 0 THEN
        RAISE EXCEPTION 'Orafit: overlength CHAR cast is unsupported' USING ERRCODE = '0A000';
    END IF;
    RETURN (value || repeat(' ', padding))::bpchar;
END
$$;

CREATE FUNCTION orafit.length(value text)
RETURNS numeric LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE
AS $$ SELECT char_length(NULLIF($1, ''))::numeric $$;

CREATE FUNCTION orafit.length(value numeric)
RETURNS numeric LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE
AS $$ SELECT char_length(orafit._number_to_varchar2($1, '.,'))::numeric $$;
