-- Runtime error semantics for translated CONNECT BY.

CREATE FUNCTION orafit.connect_by_cycle_guard(has_cycle boolean)
RETURNS boolean
LANGUAGE plpgsql VOLATILE STRICT PARALLEL UNSAFE
AS $$
BEGIN
    IF has_cycle THEN
        RAISE EXCEPTION 'ORA-01436: CONNECT BY loop in user data'
            USING ERRCODE = '72000';
    END IF;
    RETURN true;
END
$$;
