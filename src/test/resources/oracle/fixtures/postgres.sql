-- Orafit PostgreSQL differential fixture.
-- Each marked block is executed as one JDBC statement.

-- @statement
DROP SCHEMA IF EXISTS bs_pkg CASCADE;
-- @statement
DROP TABLE IF EXISTS bs_events CASCADE;
-- @statement
DROP TABLE IF EXISTS bs_byte_length CASCADE;
-- @statement
DROP TABLE IF EXISTS bs_model CASCADE;
-- @statement
DROP TABLE IF EXISTS bs_wide CASCADE;
-- @statement
DROP TABLE IF EXISTS bs_sales CASCADE;
-- @statement
DROP TABLE IF EXISTS bs_long_text CASCADE;
-- @statement
DROP TABLE IF EXISTS bs_merge_src CASCADE;
-- @statement
DROP TABLE IF EXISTS bs_dml CASCADE;
-- @statement
DROP TABLE IF EXISTS bs_cycle CASCADE;
-- @statement
DROP TABLE IF EXISTS bs_hierarchy CASCADE;
-- @statement
DROP TABLE IF EXISTS bs_emp CASCADE;
-- @statement
DROP TABLE IF EXISTS bs_dept CASCADE;
-- @statement
DROP TABLE IF EXISTS bs_location CASCADE;
-- @statement
DROP SEQUENCE IF EXISTS bs_seq CASCADE;
-- @statement
DROP FUNCTION IF EXISTS bs_fn_add(numeric, numeric) CASCADE;
-- @statement
DROP PROCEDURE IF EXISTS bs_proc_math(numeric, numeric, numeric) CASCADE;
-- @statement
DROP PROCEDURE IF EXISTS bs_ref_cursor(numeric) CASCADE;

-- @statement
CREATE TABLE bs_location (
    location_id numeric(4) PRIMARY KEY,
    city        varchar(30)
);
-- @statement
INSERT INTO bs_location VALUES (1, 'Tokyo'), (2, 'Osaka');

-- @statement
CREATE TABLE bs_dept (
    deptno      numeric(2) PRIMARY KEY,
    dname       varchar(30),
    active      numeric(1),
    location_id numeric(4)
);
-- @statement
INSERT INTO bs_dept VALUES
    (10, 'Engineering', 1, 1),
    (20, 'Operations', 1, 2),
    (30, 'Dormant', 0, NULL),
    (40, 'Empty', 1, 1);

-- @statement
CREATE TABLE bs_emp (
    empno  numeric(4) PRIMARY KEY,
    ename  varchar(20),
    job    varchar(10),
    deptno numeric(2),
    mgr    numeric(4),
    sal    numeric(8,2),
    hired  date,
    note   varchar(30)
);
-- @statement
INSERT INTO bs_emp VALUES
    (1, 'ALICE', 'DEV', 10, NULL, 5000, DATE '2020-01-31', 'alpha'),
    (2, 'BOB', 'DEV', 10, 1, 3200, DATE '2020-02-29', NULL),
    (3, 'CAROL', 'OPS', 20, NULL, 4500, DATE '2021-03-15', 'gamma'),
    (4, 'DAVE', 'OPS', 20, 3, 1800, DATE '2021-04-30', NULL),
    (5, 'ERIN', 'DEV', 10, 1, 2500, DATE '2022-05-01', 'epsilon'),
    (6, 'FRANK', 'QA', 30, NULL, 1500, DATE '2023-06-10', 'zeta'),
    (7, 'GRACE', 'DEV', 99, NULL, 2100, DATE '2024-01-01', 'eta'),
    (8, NULL, 'OPS', 20, 3, 1700, DATE '2024-02-29', 'theta');

-- @statement
CREATE TABLE bs_hierarchy (
    id        numeric(4) PRIMARY KEY,
    parent_id numeric(4),
    name      varchar(30),
    sort_key  numeric(4),
    deptno    numeric(2)
);
-- @statement
INSERT INTO bs_hierarchy VALUES
    (1, NULL, 'root-a', 20, 10),
    (2, 1, 'child-a', 20, 10),
    (3, 1, 'child-b', 10, 10),
    (4, 2, 'grandchild-a', 10, 20),
    (10, NULL, 'root-b', 10, 20),
    (11, 10, 'child-c', 10, 20);

-- @statement
CREATE TABLE bs_cycle (
    id        numeric(4) PRIMARY KEY,
    parent_id numeric(4),
    name      varchar(30)
);
-- @statement
INSERT INTO bs_cycle VALUES (1, 3, 'one'), (2, 1, 'two'), (3, 2, 'three');

-- @statement
CREATE TABLE bs_dml (
    id     numeric(6) PRIMARY KEY,
    code   varchar(10),
    amount numeric(12,2),
    note   varchar(30)
);
-- @statement
INSERT INTO bs_dml VALUES
    (1, 'A', 10, 'one'),
    (2, 'B', 20, 'two'),
    (3, 'C', 30, 'three');

-- @statement
CREATE TABLE bs_merge_src (
    id     numeric(6) PRIMARY KEY,
    amount numeric(12,2),
    note   varchar(30)
);
-- @statement
INSERT INTO bs_merge_src VALUES
    (1, 15, 'updated-one'),
    (4, 40, 'insert-four'),
    (5, 0, 'zero-five');

-- @statement
CREATE TABLE bs_long_text (
    id      numeric(4) PRIMARY KEY,
    payload varchar(100)
);
-- @statement
INSERT INTO bs_long_text(id, payload)
SELECT g, repeat(chr(CAST(64 + ((g - 1) % 26) + 1 AS integer)), 100)
FROM generate_series(1, 60) AS g;

-- @statement
CREATE TABLE bs_sales (
    deptno numeric(2),
    job    varchar(10),
    sal    numeric(10,2)
);
-- @statement
INSERT INTO bs_sales VALUES
    (10, 'DEV', 100), (10, 'OPS', 50),
    (20, 'DEV', 70), (20, 'OPS', 80);

-- @statement
CREATE TABLE bs_wide (
    deptno  numeric(2),
    dev_sal numeric(10,2),
    ops_sal numeric(10,2)
);
-- @statement
INSERT INTO bs_wide VALUES (10, 100, 50), (20, 70, 80);

-- @statement
CREATE TABLE bs_model (
    product varchar(10),
    year    numeric(4),
    sales   numeric(10,2)
);
-- @statement
INSERT INTO bs_model VALUES ('A', 2024, 100), ('A', 2025, 0);

-- @statement
CREATE TABLE bs_events (
    event_time numeric(4),
    value      numeric(10,2)
);
-- @statement
INSERT INTO bs_events VALUES (1, 10), (2, 20), (3, 15);

-- @statement
CREATE TABLE bs_byte_length (
    id             numeric(4) PRIMARY KEY,
    variable_value varchar(3),
    fixed_value    char(3)
);
-- @statement
INSERT INTO bs_byte_length VALUES (9, 'A', 'A');

-- @statement
INSERT INTO bs_byte_length VALUES (10, 'B', NULL);

-- @statement
SELECT orafit.apply_byte_length_semantics('public');

-- @statement
CREATE SEQUENCE bs_seq START WITH 100 INCREMENT BY 1 NO CYCLE;

-- @statement
CREATE FUNCTION bs_fn_add(p_a numeric, p_b numeric)
RETURNS numeric
LANGUAGE sql
IMMUTABLE
AS $$ SELECT p_a + p_b $$;

-- @statement
CREATE PROCEDURE bs_proc_math(IN p_in numeric, OUT p_out numeric, INOUT p_both numeric)
LANGUAGE plpgsql
AS $$
BEGIN
    p_out := p_in * 2;
    p_both := p_both + p_in;
END;
$$;

-- @statement
CREATE PROCEDURE bs_ref_cursor(IN p_min numeric, OUT p_out refcursor)
LANGUAGE plpgsql
AS $$
BEGIN
    OPEN p_out FOR SELECT empno, ename FROM bs_emp WHERE empno >= p_min ORDER BY empno;
END;
$$;

-- @statement
CREATE SCHEMA bs_pkg;
-- @statement
CREATE FUNCTION bs_pkg.mul(p_a numeric, p_b numeric)
RETURNS numeric
LANGUAGE sql
IMMUTABLE
AS $$ SELECT p_a * p_b $$;

-- @statement
CREATE PROCEDURE bs_pkg.bump(INOUT p_value numeric, IN p_delta numeric)
LANGUAGE plpgsql
AS $$
BEGIN
    p_value := p_value + p_delta;
END;
$$;

-- @statement
CREATE PROCEDURE bs_pkg.choose(IN p_value numeric, OUT p_out text)
LANGUAGE plpgsql
AS $$
BEGIN
    p_out := 'N:' || p_value::text;
END;
$$;

-- @statement
CREATE PROCEDURE bs_pkg.choose(IN p_value text, OUT p_out text)
LANGUAGE plpgsql
AS $$
BEGIN
    p_out := 'S:' || p_value;
END;
$$;
