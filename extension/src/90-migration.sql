-- Oracle BYTE length semantics for Ora2Pg-style bounded character columns.

CREATE FUNCTION orafit.apply_byte_length_semantics(target_schema name)
RETURNS integer
LANGUAGE plpgsql VOLATILE
AS $$
DECLARE
    column_definition record;
    constraint_name name;
    applied_count integer := 0;
    value_expression text;
BEGIN
    IF target_schema IN ('pg_catalog', 'information_schema', 'orafit') THEN
        RAISE EXCEPTION 'refusing to modify protected schema %', target_schema
            USING ERRCODE = '22023';
    END IF;
    IF NOT EXISTS (
        SELECT 1
          FROM pg_catalog.pg_namespace
         WHERE nspname = target_schema
    ) THEN
        RAISE EXCEPTION 'schema % does not exist', target_schema
            USING ERRCODE = '3F000';
    END IF;

    FOR column_definition IN
        SELECT cls.oid AS table_oid,
               namespace.nspname AS schema_name,
               cls.relname AS table_name,
               attribute.attname AS column_name,
               attribute.atttypid AS type_oid,
               attribute.atttypmod - 4 AS max_bytes
          FROM pg_catalog.pg_attribute attribute
          JOIN pg_catalog.pg_class cls
            ON cls.oid = attribute.attrelid
          JOIN pg_catalog.pg_namespace namespace
            ON namespace.oid = cls.relnamespace
         WHERE namespace.nspname = target_schema
           AND cls.relkind IN ('r', 'p')
           AND NOT cls.relispartition
           AND attribute.attnum > 0
           AND NOT attribute.attisdropped
           AND attribute.atttypid IN (
               'pg_catalog.varchar'::pg_catalog.regtype,
               'pg_catalog.bpchar'::pg_catalog.regtype)
           AND attribute.atttypmod > 4
         ORDER BY cls.oid, attribute.attnum
    LOOP
        constraint_name := 'orafit_byte_length_' || pg_catalog.substr(
            pg_catalog.md5(
                column_definition.schema_name || pg_catalog.chr(31) ||
                column_definition.table_name || pg_catalog.chr(31) ||
                column_definition.column_name),
            1,
            32);
        IF EXISTS (
            SELECT 1
              FROM pg_catalog.pg_constraint
             WHERE conrelid = column_definition.table_oid
               AND conname = constraint_name
        ) THEN
            CONTINUE;
        END IF;

        value_expression := pg_catalog.format('%I', column_definition.column_name);
        IF column_definition.type_oid = 'pg_catalog.bpchar'::pg_catalog.regtype THEN
            value_expression := value_expression || '::pg_catalog.text';
        END IF;
        EXECUTE pg_catalog.format(
            'ALTER TABLE %I.%I ADD CONSTRAINT %I CHECK '
                || '(pg_catalog.octet_length(%s) <= %s)',
            column_definition.schema_name,
            column_definition.table_name,
            constraint_name,
            value_expression,
            column_definition.max_bytes);
        applied_count := applied_count + 1;
    END LOOP;
    RETURN applied_count;
END
$$;
