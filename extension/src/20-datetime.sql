-- DATE/TIMESTAMP conversion and deterministic date arithmetic.

CREATE FUNCTION orafit.cast_date(value timestamp without time zone)
RETURNS timestamp without time zone LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE
AS $$ SELECT date_trunc('second', $1) $$;

CREATE FUNCTION orafit.date_difference(left_value timestamp without time zone, right_value timestamp without time zone)
RETURNS numeric LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE
AS $$ SELECT orafit.divide(extract(epoch FROM ($1 - $2)), 86400) $$;

CREATE FUNCTION orafit._pg_datetime_format(format text)
RETURNS text
LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE
AS $$ SELECT regexp_replace($1, 'FF[1-9]?', 'US', 'gi') $$;

CREATE FUNCTION orafit.to_date(value text, format text DEFAULT 'YYYY-MM-DD')
RETURNS timestamp without time zone
LANGUAGE plpgsql VOLATILE PARALLEL SAFE
AS $$
DECLARE
    parsed timestamp without time zone;
    pg_format text;
    exact_format text;
    rest text;
    token text;
    pattern text := '^[[:space:]]*';
    consumed text;
    digits integer;
BEGIN
    IF value IS NULL OR btrim(value) = '' THEN RETURN NULL; END IF;
    pg_format := orafit._pg_datetime_format(format);
    IF btrim(value) ~ '^[0-9]+$' AND upper(left(format, 2)) <> 'FX' THEN
        pg_format := regexp_replace(pg_format, '[^[:alnum:]]', '', 'g');
    END IF;
    parsed := pg_catalog.to_timestamp(btrim(value), pg_format)::timestamp without time zone;
    -- The public translator admits numeric format tokens only. Track the input
    -- consumed by those tokens: PostgreSQL silently ignores a trailing suffix.
    rest := regexp_replace(upper(format), '^FX', '');
    WHILE rest <> '' LOOP
        token := substring(rest FROM '^(YYYY|HH24|HH12|YY|MM|DD|MI|SS)');
        IF token IS NOT NULL THEN
            digits := CASE WHEN token = 'YYYY' THEN 4 ELSE 2 END;
            rest := substr(rest, length(token) + 1);
            IF rest ~ '^[A-Z]' THEN
                pattern := pattern || '[0-9]{' || digits || '}';
            ELSE
                pattern := pattern || '[0-9]{1,' || digits || '}';
            END IF;
        ELSIF left(rest, 1) !~ '[[:alnum:]]' THEN
            pattern := pattern || '[^[:alnum:]]*';
            rest := substr(rest, 2);
        ELSE
            RAISE EXCEPTION 'Orafit: unsupported TO_DATE format token' USING ERRCODE = '0A000';
        END IF;
    END LOOP;
    consumed := substring(value FROM pattern);
    IF consumed IS NOT NULL AND btrim(substr(value, length(consumed) + 1)) <> '' THEN
        RAISE EXCEPTION 'ORA-01830: date format picture ends before converting entire input string' USING ERRCODE = 'P1830';
    END IF;
    IF upper(left(btrim(format), 2)) = 'FX' THEN
        exact_format := regexp_replace(pg_format, '^FX', '', 'i');
        IF lower(pg_catalog.to_char(parsed, exact_format)) <> lower(btrim(value)) THEN
            RAISE EXCEPTION 'ORA-01861: literal does not match format string' USING ERRCODE = '22008';
        END IF;
    END IF;
    RETURN parsed;
EXCEPTION
    WHEN SQLSTATE 'P1830' THEN
        RAISE EXCEPTION 'ORA-01830: date format picture ends before converting entire input string' USING ERRCODE = '22008';
    WHEN datetime_field_overflow OR invalid_datetime_format OR invalid_parameter_value THEN
        RAISE EXCEPTION 'ORA-01861: literal does not match format string' USING ERRCODE = '22008';
END
$$;

CREATE FUNCTION orafit.to_timestamp(
    value text, format text DEFAULT 'YYYY-MM-DD HH24:MI:SS')
RETURNS timestamp without time zone
LANGUAGE plpgsql VOLATILE PARALLEL SAFE
AS $$
BEGIN
    IF value IS NULL OR btrim(value) = '' THEN RETURN NULL; END IF;
    RETURN pg_catalog.to_timestamp(
        btrim(value), orafit._pg_datetime_format(format))::timestamp without time zone;
EXCEPTION
    WHEN datetime_field_overflow OR invalid_datetime_format OR invalid_parameter_value THEN
        RAISE EXCEPTION 'ORA-01861: literal does not match format string' USING ERRCODE = '22008';
END
$$;

CREATE FUNCTION orafit.to_timestamp_tz(
    value text, format text DEFAULT 'YYYY-MM-DD HH24:MI:SS TZH:TZM')
RETURNS timestamp with time zone
LANGUAGE plpgsql VOLATILE PARALLEL SAFE
AS $$
BEGIN
    IF value IS NULL OR btrim(value) = '' THEN RETURN NULL; END IF;
    RETURN pg_catalog.to_timestamp(
        btrim(value), orafit._pg_datetime_format(format));
EXCEPTION
    WHEN datetime_field_overflow OR invalid_datetime_format OR invalid_parameter_value THEN
        RAISE EXCEPTION 'ORA-01861: literal does not match format string' USING ERRCODE = '22008';
END
$$;

CREATE FUNCTION orafit.last_day(value date)
RETURNS date
LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE
AS $$ SELECT (date_trunc('month', $1::timestamp) + interval '1 month - 1 day')::date $$;

CREATE FUNCTION orafit.last_day(value timestamp without time zone)
RETURNS timestamp without time zone
LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE
AS $$
    SELECT orafit.last_day($1::date)::timestamp
           + ($1 - date_trunc('day', $1))
$$;

CREATE FUNCTION orafit.add_months(value date, months integer)
RETURNS date
LANGUAGE plpgsql IMMUTABLE STRICT PARALLEL SAFE
AS $$
DECLARE
    source_last date;
    target_first date;
    target_last date;
    source_day integer;
BEGIN
    source_last := orafit.last_day(value);
    target_first := (date_trunc('month', value::timestamp)
                     + make_interval(months => months))::date;
    target_last := orafit.last_day(target_first);
    source_day := extract(day from value)::integer;
    IF value = source_last OR source_day > extract(day from target_last)::integer THEN
        RETURN target_last;
    END IF;
    RETURN target_first + (source_day - 1);
END
$$;

CREATE FUNCTION orafit.add_months(value timestamp without time zone, months integer)
RETURNS timestamp without time zone
LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE
AS $$
    SELECT orafit.add_months($1::date, $2)::timestamp
           + ($1 - date_trunc('day', $1))
$$;

CREATE FUNCTION orafit.add_months(value date, months numeric)
RETURNS date LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE
AS $$ SELECT orafit.add_months($1, pg_catalog.trunc($2)::integer) $$;

CREATE FUNCTION orafit.add_months(value timestamp without time zone, months numeric)
RETURNS timestamp without time zone LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE
AS $$ SELECT orafit.add_months($1, pg_catalog.trunc($2)::integer) $$;

CREATE FUNCTION orafit.months_between(
    left_value timestamp without time zone,
    right_value timestamp without time zone)
RETURNS numeric
LANGUAGE plpgsql IMMUTABLE STRICT PARALLEL SAFE
AS $$
DECLARE
    whole_months integer;
    left_last boolean;
    right_last boolean;
    day_fraction numeric;
    result numeric;
    result_scale integer;
BEGIN
    whole_months := (extract(year from left_value)::integer
                     - extract(year from right_value)::integer) * 12
                    + extract(month from left_value)::integer
                    - extract(month from right_value)::integer;
    left_last := left_value::date = orafit.last_day(left_value::date);
    right_last := right_value::date = orafit.last_day(right_value::date);
    IF extract(day from left_value) = extract(day from right_value)
       OR (left_last AND right_last) THEN
        RETURN whole_months;
    END IF;
    day_fraction := (
        (extract(day from left_value)::numeric
         - extract(day from right_value)::numeric)::numeric(80, 50)
        + (extract(epoch from (left_value - date_trunc('day', left_value)))
           - extract(epoch from (right_value - date_trunc('day', right_value))))::numeric(80, 50) / 86400
    ) / 31;
    result := whole_months + day_fraction;
    IF result = 0 THEN RETURN 0; END IF;
    result_scale := 37 - floor(log(10, abs(result)))::integer;
    RETURN pg_catalog.trim_scale(round(result, result_scale));
END
$$;

CREATE FUNCTION orafit.months_between(left_value date, right_value date)
RETURNS numeric
LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE
AS $$ SELECT orafit.months_between($1::timestamp, $2::timestamp) $$;

CREATE FUNCTION orafit.trunc(value timestamp without time zone, format text DEFAULT 'DD')
RETURNS timestamp without time zone
LANGUAGE plpgsql IMMUTABLE PARALLEL SAFE
AS $$
DECLARE
    fmt text;
    base timestamp without time zone;
    iso_year integer;
BEGIN
    IF value IS NULL OR format IS NULL OR btrim(format) = '' THEN RETURN NULL; END IF;
    fmt := upper(btrim(format));
    CASE fmt
        WHEN 'DD', 'DDD', 'J' THEN RETURN date_trunc('day', value);
        WHEN 'HH', 'HH12', 'HH24' THEN RETURN date_trunc('hour', value);
        WHEN 'MI' THEN RETURN date_trunc('minute', value);
        WHEN 'MM', 'MON', 'MONTH', 'RM' THEN RETURN date_trunc('month', value);
        WHEN 'Q' THEN RETURN date_trunc('quarter', value);
        WHEN 'YYYY', 'SYYYY', 'YEAR', 'SYEAR', 'YYY', 'YY', 'Y' THEN RETURN date_trunc('year', value);
        WHEN 'IW' THEN RETURN date_trunc('week', value);
        WHEN 'IYYY', 'IYY', 'IY', 'I' THEN
            iso_year := extract(isoyear from value)::integer;
            RETURN date_trunc('week', make_date(iso_year, 1, 4)::timestamp);
        WHEN 'WW' THEN
            base := date_trunc('year', value);
            RETURN base + ((value::date - base::date) / 7) * interval '7 days';
        WHEN 'W' THEN
            base := date_trunc('month', value);
            RETURN base + ((value::date - base::date) / 7) * interval '7 days';
        WHEN 'D', 'DY', 'DAY' THEN
            RAISE EXCEPTION 'Orafit: Oracle TRUNC format % depends on NLS_TERRITORY and is not supported yet', fmt
                USING ERRCODE = '0A000';
        WHEN 'CC', 'SCC' THEN
            RAISE EXCEPTION 'Orafit: Oracle TRUNC century format % is not supported yet', fmt
                USING ERRCODE = '0A000';
        ELSE
            RAISE EXCEPTION 'Orafit: unsupported Oracle TRUNC date format model: %', fmt
                USING ERRCODE = '0A000';
    END CASE;
END
$$;

CREATE FUNCTION orafit.trunc(value date, format text DEFAULT 'DD')
RETURNS timestamp without time zone
LANGUAGE sql IMMUTABLE PARALLEL SAFE
AS $$ SELECT orafit.trunc($1::timestamp without time zone, $2) $$;

CREATE FUNCTION orafit.trunc(value timestamp with time zone, format text DEFAULT 'DD')
RETURNS timestamp without time zone
LANGUAGE plpgsql STABLE PARALLEL SAFE
AS $$
BEGIN
    RAISE EXCEPTION 'Orafit: TRUNC(TIMESTAMP WITH TIME ZONE) is not supported'
        USING ERRCODE = '0A000';
END
$$;

CREATE FUNCTION orafit.round(value timestamp without time zone, format text DEFAULT 'DD')
RETURNS timestamp without time zone
LANGUAGE plpgsql IMMUTABLE PARALLEL SAFE
AS $$
DECLARE
    fmt text;
    base timestamp without time zone;
BEGIN
    IF value IS NULL OR format IS NULL OR btrim(format) = '' THEN RETURN NULL; END IF;
    fmt := upper(btrim(format));
    CASE fmt
        WHEN 'DD', 'DDD', 'J' THEN
            base := orafit.trunc(value, 'DD');
            RETURN CASE WHEN value >= base + interval '12 hours' THEN base + interval '1 day' ELSE base END;
        WHEN 'HH', 'HH12', 'HH24' THEN
            base := orafit.trunc(value, 'HH24');
            RETURN CASE WHEN value >= base + interval '30 minutes' THEN base + interval '1 hour' ELSE base END;
        WHEN 'MI' THEN
            base := orafit.trunc(value, 'MI');
            RETURN CASE WHEN value >= base + interval '30 seconds' THEN base + interval '1 minute' ELSE base END;
        WHEN 'MM', 'MON', 'MONTH', 'RM' THEN
            base := orafit.trunc(value, 'MM');
            RETURN CASE WHEN value >= base + interval '15 days' THEN base + interval '1 month' ELSE base END;
        WHEN 'Q' THEN
            base := orafit.trunc(value, 'Q');
            RETURN CASE WHEN value >= base + interval '1 month 15 days' THEN base + interval '3 months' ELSE base END;
        WHEN 'YYYY', 'SYYYY', 'YEAR', 'SYEAR', 'YYY', 'YY', 'Y' THEN
            base := orafit.trunc(value, 'YYYY');
            RETURN CASE WHEN value >= base + interval '6 months' THEN base + interval '1 year' ELSE base END;
        WHEN 'IW' THEN
            base := orafit.trunc(value, 'IW');
            RETURN CASE WHEN value >= base + interval '3 days 12 hours' THEN base + interval '7 days' ELSE base END;
        WHEN 'WW' THEN
            base := orafit.trunc(value, 'WW');
            RETURN CASE WHEN value >= base + interval '3 days 12 hours' THEN base + interval '7 days' ELSE base END;
        WHEN 'W' THEN
            base := orafit.trunc(value, 'W');
            RETURN CASE WHEN value >= base + interval '3 days 12 hours' THEN base + interval '7 days' ELSE base END;
        WHEN 'IYYY', 'IYY', 'IY', 'I' THEN
            RAISE EXCEPTION 'Orafit: Oracle ROUND ISO-year format % is not supported yet', fmt
                USING ERRCODE = '0A000';
        WHEN 'D', 'DY', 'DAY' THEN
            RAISE EXCEPTION 'Orafit: Oracle ROUND format % depends on NLS_TERRITORY and is not supported yet', fmt
                USING ERRCODE = '0A000';
        WHEN 'CC', 'SCC' THEN
            RAISE EXCEPTION 'Orafit: Oracle ROUND century format % is not supported yet', fmt
                USING ERRCODE = '0A000';
        ELSE
            RAISE EXCEPTION 'Orafit: unsupported Oracle ROUND date format model: %', fmt
                USING ERRCODE = '0A000';
    END CASE;
END
$$;

CREATE FUNCTION orafit.round(value date, format text DEFAULT 'DD')
RETURNS timestamp without time zone
LANGUAGE sql IMMUTABLE PARALLEL SAFE
AS $$ SELECT orafit.round($1::timestamp without time zone, $2) $$;

CREATE FUNCTION orafit.round(value timestamp with time zone, format text DEFAULT 'DD')
RETURNS timestamp without time zone
LANGUAGE plpgsql STABLE PARALLEL SAFE
AS $$
BEGIN
    RAISE EXCEPTION 'Orafit: ROUND(TIMESTAMP WITH TIME ZONE) is not supported'
        USING ERRCODE = '0A000';
END
$$;

CREATE FUNCTION orafit.days_interval(days numeric)
RETURNS interval
LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE
AS $$ SELECT $1 * interval '1 day' $$;
