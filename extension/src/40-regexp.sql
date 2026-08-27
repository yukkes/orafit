-- Oracle POSIX regular-expression compatibility for the supported subset.

CREATE FUNCTION orafit.regexp_pg_flags(match_param text)
RETURNS text
LANGUAGE plpgsql IMMUTABLE PARALLEL SAFE
AS $$
DECLARE
    input text := coalesce(match_param, '');
    ch text;
    case_flag text := 'c';
    multiline boolean := false;
    dot_newline boolean := false;
    newline_flag text;
BEGIN
    FOR i IN 1..char_length(input) LOOP
        ch := lower(substr(input, i, 1));
        IF ch IN ('c', 'i') THEN
            case_flag := ch;
        ELSIF ch = 'm' THEN
            multiline := true;
        ELSIF ch = 'n' THEN
            dot_newline := true;
        ELSIF ch = 'x' THEN
            RAISE EXCEPTION 'Orafit REGEXP match_param x is not supported'
                USING ERRCODE = '0A000';
        ELSE
            RAISE EXCEPTION 'invalid Oracle REGEXP match_param: %', ch USING ERRCODE = '22023';
        END IF;
    END LOOP;
    newline_flag := CASE
        WHEN multiline AND dot_newline THEN 'w'
        WHEN multiline THEN 'n'
        WHEN dot_newline THEN 's'
        ELSE 'p'
    END;
    RETURN case_flag || newline_flag;
END
$$;

CREATE FUNCTION orafit.regexp_validate_pattern(pattern text)
RETURNS text
LANGUAGE plpgsql IMMUTABLE PARALLEL SAFE
AS $$
BEGIN
    IF pattern IS NULL OR pattern = '' THEN RETURN NULL; END IF;
    IF octet_length(pattern) > 512 THEN
        RAISE EXCEPTION 'Oracle regular-expression pattern exceeds 512 bytes'
            USING ERRCODE = '22023';
    END IF;
    RETURN pattern;
END
$$;

CREATE FUNCTION orafit.regexp_like(value text, pattern text, match_param text)
RETURNS boolean
LANGUAGE plpgsql IMMUTABLE PARALLEL SAFE
AS $$
BEGIN
    IF value IS NULL OR value = '' OR pattern IS NULL OR pattern = '' THEN RETURN NULL; END IF;
    RETURN pg_catalog.regexp_like(
        value, orafit.regexp_validate_pattern(pattern),
        orafit.regexp_pg_flags(match_param));
END
$$;
CREATE FUNCTION orafit.regexp_like(value text, pattern text)
RETURNS boolean LANGUAGE sql IMMUTABLE PARALLEL SAFE
AS $$ SELECT orafit.regexp_like($1, $2, NULL) $$;

CREATE FUNCTION orafit.regexp_substr(
    value text, pattern text, start_position numeric, occurrence numeric,
    match_param text, subexpr numeric)
RETURNS text
LANGUAGE plpgsql IMMUTABLE PARALLEL SAFE
AS $$
DECLARE p numeric; occ numeric; sub numeric;
BEGIN
    IF value IS NULL OR value = '' OR pattern IS NULL OR pattern = ''
       OR start_position IS NULL OR occurrence IS NULL OR subexpr IS NULL THEN RETURN NULL; END IF;
    p := trunc(start_position); occ := trunc(occurrence); sub := trunc(subexpr);
    IF p < 1 THEN RAISE EXCEPTION 'Oracle REGEXP_SUBSTR position must be positive' USING ERRCODE = '22023'; END IF;
    IF occ < 1 THEN RAISE EXCEPTION 'Oracle REGEXP_SUBSTR occurrence must be positive' USING ERRCODE = '22023'; END IF;
    IF sub < 0 OR sub > 9 THEN RAISE EXCEPTION 'Oracle REGEXP_SUBSTR subexpr must be between 0 and 9' USING ERRCODE = '22023'; END IF;
    IF p > 2147483647 OR occ > 2147483647 THEN RETURN NULL; END IF;
    RETURN NULLIF(pg_catalog.regexp_substr(
        value, orafit.regexp_validate_pattern(pattern), p::integer, occ::integer,
        orafit.regexp_pg_flags(match_param), sub::integer), '');
END
$$;
CREATE FUNCTION orafit.regexp_substr(value text, pattern text)
RETURNS text LANGUAGE sql IMMUTABLE PARALLEL SAFE
AS $$ SELECT orafit.regexp_substr($1, $2, 1, 1, NULL, 0) $$;
CREATE FUNCTION orafit.regexp_substr(value text, pattern text, start_position numeric)
RETURNS text LANGUAGE sql IMMUTABLE PARALLEL SAFE
AS $$ SELECT orafit.regexp_substr($1, $2, $3, 1, NULL, 0) $$;
CREATE FUNCTION orafit.regexp_substr(value text, pattern text, start_position numeric, occurrence numeric)
RETURNS text LANGUAGE sql IMMUTABLE PARALLEL SAFE
AS $$ SELECT orafit.regexp_substr($1, $2, $3, $4, NULL, 0) $$;
CREATE FUNCTION orafit.regexp_substr(
    value text, pattern text, start_position numeric, occurrence numeric, match_param text)
RETURNS text LANGUAGE sql IMMUTABLE PARALLEL SAFE
AS $$ SELECT orafit.regexp_substr($1, $2, $3, $4, $5, 0) $$;

CREATE FUNCTION orafit.regexp_replace(
    value text, pattern text, replacement text, start_position numeric,
    occurrence numeric, match_param text)
RETURNS text
LANGUAGE plpgsql IMMUTABLE PARALLEL SAFE
AS $$
DECLARE p numeric; occ numeric;
BEGIN
    IF value IS NULL OR value = '' OR pattern IS NULL OR pattern = ''
       OR start_position IS NULL OR occurrence IS NULL THEN RETURN NULL; END IF;
    p := trunc(start_position); occ := trunc(occurrence);
    IF p < 1 THEN RAISE EXCEPTION 'Oracle REGEXP_REPLACE position must be positive' USING ERRCODE = '22023'; END IF;
    IF occ < 0 THEN RAISE EXCEPTION 'Oracle REGEXP_REPLACE occurrence must not be negative' USING ERRCODE = '22023'; END IF;
    IF p > 2147483647 OR occ > 2147483647 THEN RETURN value; END IF;
    RETURN NULLIF(pg_catalog.regexp_replace(
        value, orafit.regexp_validate_pattern(pattern), coalesce(replacement, ''),
        p::integer, occ::integer, orafit.regexp_pg_flags(match_param)), '');
END
$$;
CREATE FUNCTION orafit.regexp_replace(value text, pattern text)
RETURNS text LANGUAGE sql IMMUTABLE PARALLEL SAFE
AS $$ SELECT orafit.regexp_replace($1, $2, NULL, 1, 0, NULL) $$;
CREATE FUNCTION orafit.regexp_replace(value text, pattern text, replacement text)
RETURNS text LANGUAGE sql IMMUTABLE PARALLEL SAFE
AS $$ SELECT orafit.regexp_replace($1, $2, $3, 1, 0, NULL) $$;
CREATE FUNCTION orafit.regexp_replace(
    value text, pattern text, replacement text, start_position numeric)
RETURNS text LANGUAGE sql IMMUTABLE PARALLEL SAFE
AS $$ SELECT orafit.regexp_replace($1, $2, $3, $4, 0, NULL) $$;
CREATE FUNCTION orafit.regexp_replace(
    value text, pattern text, replacement text, start_position numeric, occurrence numeric)
RETURNS text LANGUAGE sql IMMUTABLE PARALLEL SAFE
AS $$ SELECT orafit.regexp_replace($1, $2, $3, $4, $5, NULL) $$;

CREATE FUNCTION orafit.regexp_count(
    value text, pattern text, start_position numeric, match_param text)
RETURNS numeric
LANGUAGE plpgsql IMMUTABLE PARALLEL SAFE
AS $$
DECLARE p numeric;
BEGIN
    IF value IS NULL OR value = '' OR pattern IS NULL OR pattern = ''
       OR start_position IS NULL THEN RETURN NULL; END IF;
    p := trunc(start_position);
    IF p < 1 THEN RAISE EXCEPTION 'Oracle REGEXP_COUNT position must be positive' USING ERRCODE = '22023'; END IF;
    IF p > 2147483647 THEN RETURN 0; END IF;
    RETURN pg_catalog.regexp_count(
        value, orafit.regexp_validate_pattern(pattern), p::integer,
        orafit.regexp_pg_flags(match_param))::numeric;
END
$$;
CREATE FUNCTION orafit.regexp_count(value text, pattern text)
RETURNS numeric LANGUAGE sql IMMUTABLE PARALLEL SAFE
AS $$ SELECT orafit.regexp_count($1, $2, 1, NULL) $$;
CREATE FUNCTION orafit.regexp_count(value text, pattern text, start_position numeric)
RETURNS numeric LANGUAGE sql IMMUTABLE PARALLEL SAFE
AS $$ SELECT orafit.regexp_count($1, $2, $3, NULL) $$;

CREATE FUNCTION orafit.regexp_instr(
    value text, pattern text, start_position numeric, occurrence numeric,
    return_option numeric, match_param text, subexpr numeric)
RETURNS numeric
LANGUAGE plpgsql IMMUTABLE PARALLEL SAFE
AS $$
DECLARE p numeric; occ numeric; ret numeric; sub numeric;
BEGIN
    IF value IS NULL OR value = '' OR pattern IS NULL OR pattern = ''
       OR start_position IS NULL OR occurrence IS NULL
       OR return_option IS NULL OR subexpr IS NULL THEN RETURN NULL; END IF;
    p := trunc(start_position); occ := trunc(occurrence);
    ret := trunc(return_option); sub := trunc(subexpr);
    IF p < 1 THEN RAISE EXCEPTION 'Oracle REGEXP_INSTR position must be positive' USING ERRCODE = '22023'; END IF;
    IF occ < 1 THEN RAISE EXCEPTION 'Oracle REGEXP_INSTR occurrence must be positive' USING ERRCODE = '22023'; END IF;
    IF ret NOT IN (0, 1) THEN RAISE EXCEPTION 'Oracle REGEXP_INSTR return_option must be 0 or 1' USING ERRCODE = '22023'; END IF;
    IF sub < 0 OR sub > 9 THEN RAISE EXCEPTION 'Oracle REGEXP_INSTR subexpr must be between 0 and 9' USING ERRCODE = '22023'; END IF;
    IF p > 2147483647 OR occ > 2147483647 THEN RETURN 0; END IF;
    RETURN pg_catalog.regexp_instr(
        value, orafit.regexp_validate_pattern(pattern), p::integer, occ::integer,
        ret::integer, orafit.regexp_pg_flags(match_param), sub::integer)::numeric;
END
$$;
CREATE FUNCTION orafit.regexp_instr(value text, pattern text)
RETURNS numeric LANGUAGE sql IMMUTABLE PARALLEL SAFE
AS $$ SELECT orafit.regexp_instr($1, $2, 1, 1, 0, NULL, 0) $$;
CREATE FUNCTION orafit.regexp_instr(value text, pattern text, start_position numeric)
RETURNS numeric LANGUAGE sql IMMUTABLE PARALLEL SAFE
AS $$ SELECT orafit.regexp_instr($1, $2, $3, 1, 0, NULL, 0) $$;
CREATE FUNCTION orafit.regexp_instr(
    value text, pattern text, start_position numeric, occurrence numeric)
RETURNS numeric LANGUAGE sql IMMUTABLE PARALLEL SAFE
AS $$ SELECT orafit.regexp_instr($1, $2, $3, $4, 0, NULL, 0) $$;
CREATE FUNCTION orafit.regexp_instr(
    value text, pattern text, start_position numeric, occurrence numeric, return_option numeric)
RETURNS numeric LANGUAGE sql IMMUTABLE PARALLEL SAFE
AS $$ SELECT orafit.regexp_instr($1, $2, $3, $4, $5, NULL, 0) $$;
CREATE FUNCTION orafit.regexp_instr(
    value text, pattern text, start_position numeric, occurrence numeric,
    return_option numeric, match_param text)
RETURNS numeric LANGUAGE sql IMMUTABLE PARALLEL SAFE
AS $$ SELECT orafit.regexp_instr($1, $2, $3, $4, $5, $6, 0) $$;
