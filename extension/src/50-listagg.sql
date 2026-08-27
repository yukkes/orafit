-- Aggregate result/error semantics used by LISTAGG lowering.

CREATE FUNCTION orafit.listagg_check(value text, max_bytes integer)
RETURNS text
LANGUAGE plpgsql IMMUTABLE PARALLEL SAFE
AS $$
BEGIN
    IF value IS NULL THEN RETURN NULL; END IF;
    IF max_bytes NOT IN (4000, 32767) THEN
        RAISE EXCEPTION 'invalid Orafit LISTAGG maximum: %', max_bytes
            USING ERRCODE = '22023';
    END IF;
    IF octet_length(value) > max_bytes THEN
        RAISE EXCEPTION 'ORA-01489: result of string concatenation is too long'
            USING ERRCODE = '72000';
    END IF;
    RETURN value;
END
$$;

CREATE FUNCTION orafit.listagg_truncate(
    values_in text[], delimiter text, truncation_indicator text,
    with_count boolean, max_bytes integer)
RETURNS text
LANGUAGE plpgsql IMMUTABLE PARALLEL SAFE
AS $$
DECLARE
    item text;
    sep text := coalesce(delimiter, '');
    indicator text := coalesce(truncation_indicator, '');
    total_count integer := 0;
    included_count integer := 0;
    full_result text := '';
    result text := '';
    candidate text;
    reserve integer;
BEGIN
    IF max_bytes NOT IN (4000, 32767) THEN
        RAISE EXCEPTION 'invalid Orafit LISTAGG maximum: %', max_bytes
            USING ERRCODE = '22023';
    END IF;
    IF values_in IS NULL THEN RETURN NULL; END IF;

    FOREACH item IN ARRAY values_in LOOP
        IF item IS NULL THEN CONTINUE; END IF;
        total_count := total_count + 1;
        full_result := CASE WHEN full_result = '' THEN item ELSE full_result || sep || item END;
    END LOOP;
    IF total_count = 0 THEN RETURN NULL; END IF;
    IF octet_length(full_result) <= max_bytes THEN RETURN full_result; END IF;

    reserve := octet_length(sep) + octet_length(indicator)
               + CASE WHEN with_count THEN 24 ELSE 0 END;
    FOREACH item IN ARRAY values_in LOOP
        IF item IS NULL THEN CONTINUE; END IF;
        candidate := CASE WHEN included_count = 0 THEN item ELSE result || sep || item END;
        IF octet_length(candidate) + reserve > max_bytes THEN EXIT; END IF;
        result := candidate;
        included_count := included_count + 1;
    END LOOP;

    IF included_count > 0 THEN result := result || sep; END IF;
    result := result || indicator;
    IF with_count THEN
        result := result || '(' || (total_count - included_count)::text || ')';
    END IF;
    IF octet_length(result) > max_bytes THEN
        RAISE EXCEPTION 'ORA-01489: result of string concatenation is too long'
            USING ERRCODE = '72000';
    END IF;
    RETURN result;
END
$$;
