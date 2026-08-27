-- Orafit Oracle Database 18c XE reference fixture.
-- Each marked block is executed as one JDBC statement.

-- @statement
BEGIN EXECUTE IMMEDIATE 'DROP TABLE bs_events PURGE'; EXCEPTION WHEN OTHERS THEN IF SQLCODE != -942 THEN RAISE; END IF; END;
-- @statement
BEGIN EXECUTE IMMEDIATE 'DROP TABLE bs_byte_length PURGE'; EXCEPTION WHEN OTHERS THEN IF SQLCODE != -942 THEN RAISE; END IF; END;
-- @statement
BEGIN EXECUTE IMMEDIATE 'DROP TABLE bs_model PURGE'; EXCEPTION WHEN OTHERS THEN IF SQLCODE != -942 THEN RAISE; END IF; END;
-- @statement
BEGIN EXECUTE IMMEDIATE 'DROP TABLE bs_wide PURGE'; EXCEPTION WHEN OTHERS THEN IF SQLCODE != -942 THEN RAISE; END IF; END;
-- @statement
BEGIN EXECUTE IMMEDIATE 'DROP TABLE bs_sales PURGE'; EXCEPTION WHEN OTHERS THEN IF SQLCODE != -942 THEN RAISE; END IF; END;
-- @statement
BEGIN EXECUTE IMMEDIATE 'DROP TABLE bs_long_text PURGE'; EXCEPTION WHEN OTHERS THEN IF SQLCODE != -942 THEN RAISE; END IF; END;
-- @statement
BEGIN EXECUTE IMMEDIATE 'DROP TABLE bs_merge_src PURGE'; EXCEPTION WHEN OTHERS THEN IF SQLCODE != -942 THEN RAISE; END IF; END;
-- @statement
BEGIN EXECUTE IMMEDIATE 'DROP TABLE bs_dml PURGE'; EXCEPTION WHEN OTHERS THEN IF SQLCODE != -942 THEN RAISE; END IF; END;
-- @statement
BEGIN EXECUTE IMMEDIATE 'DROP TABLE bs_cycle PURGE'; EXCEPTION WHEN OTHERS THEN IF SQLCODE != -942 THEN RAISE; END IF; END;
-- @statement
BEGIN EXECUTE IMMEDIATE 'DROP TABLE bs_hierarchy PURGE'; EXCEPTION WHEN OTHERS THEN IF SQLCODE != -942 THEN RAISE; END IF; END;
-- @statement
BEGIN EXECUTE IMMEDIATE 'DROP TABLE bs_emp PURGE'; EXCEPTION WHEN OTHERS THEN IF SQLCODE != -942 THEN RAISE; END IF; END;
-- @statement
BEGIN EXECUTE IMMEDIATE 'DROP TABLE bs_dept PURGE'; EXCEPTION WHEN OTHERS THEN IF SQLCODE != -942 THEN RAISE; END IF; END;
-- @statement
BEGIN EXECUTE IMMEDIATE 'DROP TABLE bs_location PURGE'; EXCEPTION WHEN OTHERS THEN IF SQLCODE != -942 THEN RAISE; END IF; END;
-- @statement
BEGIN EXECUTE IMMEDIATE 'DROP SEQUENCE bs_seq'; EXCEPTION WHEN OTHERS THEN IF SQLCODE != -2289 THEN RAISE; END IF; END;

-- @statement
CREATE TABLE bs_location (
    location_id NUMBER(4) PRIMARY KEY,
    city        VARCHAR2(30)
)
-- @statement
INSERT INTO bs_location VALUES (1, 'Tokyo')
-- @statement
INSERT INTO bs_location VALUES (2, 'Osaka')

-- @statement
CREATE TABLE bs_dept (
    deptno      NUMBER(2) PRIMARY KEY,
    dname       VARCHAR2(30),
    active      NUMBER(1),
    location_id NUMBER(4)
)
-- @statement
INSERT INTO bs_dept VALUES (10, 'Engineering', 1, 1)
-- @statement
INSERT INTO bs_dept VALUES (20, 'Operations', 1, 2)
-- @statement
INSERT INTO bs_dept VALUES (30, 'Dormant', 0, NULL)
-- @statement
INSERT INTO bs_dept VALUES (40, 'Empty', 1, 1)

-- @statement
CREATE TABLE bs_emp (
    empno  NUMBER(4) PRIMARY KEY,
    ename  VARCHAR2(20),
    job    VARCHAR2(10),
    deptno NUMBER(2),
    mgr    NUMBER(4),
    sal    NUMBER(8,2),
    hired  DATE,
    note   VARCHAR2(30)
)
-- @statement
INSERT INTO bs_emp VALUES (1, 'ALICE', 'DEV', 10, NULL, 5000, DATE '2020-01-31', 'alpha')
-- @statement
INSERT INTO bs_emp VALUES (2, 'BOB', 'DEV', 10, 1, 3200, DATE '2020-02-29', NULL)
-- @statement
INSERT INTO bs_emp VALUES (3, 'CAROL', 'OPS', 20, NULL, 4500, DATE '2021-03-15', 'gamma')
-- @statement
INSERT INTO bs_emp VALUES (4, 'DAVE', 'OPS', 20, 3, 1800, DATE '2021-04-30', '')
-- @statement
INSERT INTO bs_emp VALUES (5, 'ERIN', 'DEV', 10, 1, 2500, DATE '2022-05-01', 'epsilon')
-- @statement
INSERT INTO bs_emp VALUES (6, 'FRANK', 'QA', 30, NULL, 1500, DATE '2023-06-10', 'zeta')
-- @statement
INSERT INTO bs_emp VALUES (7, 'GRACE', 'DEV', 99, NULL, 2100, DATE '2024-01-01', 'eta')
-- @statement
INSERT INTO bs_emp VALUES (8, NULL, 'OPS', 20, 3, 1700, DATE '2024-02-29', 'theta')

-- @statement
CREATE TABLE bs_hierarchy (
    id        NUMBER(4) PRIMARY KEY,
    parent_id NUMBER(4),
    name      VARCHAR2(30),
    sort_key  NUMBER(4),
    deptno    NUMBER(2)
)
-- @statement
INSERT INTO bs_hierarchy VALUES (1, NULL, 'root-a', 20, 10)
-- @statement
INSERT INTO bs_hierarchy VALUES (2, 1, 'child-a', 20, 10)
-- @statement
INSERT INTO bs_hierarchy VALUES (3, 1, 'child-b', 10, 10)
-- @statement
INSERT INTO bs_hierarchy VALUES (4, 2, 'grandchild-a', 10, 20)
-- @statement
INSERT INTO bs_hierarchy VALUES (10, NULL, 'root-b', 10, 20)
-- @statement
INSERT INTO bs_hierarchy VALUES (11, 10, 'child-c', 10, 20)

-- @statement
CREATE TABLE bs_cycle (
    id        NUMBER(4) PRIMARY KEY,
    parent_id NUMBER(4),
    name      VARCHAR2(30)
)
-- @statement
INSERT INTO bs_cycle VALUES (1, 3, 'one')
-- @statement
INSERT INTO bs_cycle VALUES (2, 1, 'two')
-- @statement
INSERT INTO bs_cycle VALUES (3, 2, 'three')

-- @statement
CREATE TABLE bs_dml (
    id     NUMBER(6) PRIMARY KEY,
    code   VARCHAR2(10),
    amount NUMBER(12,2),
    note   VARCHAR2(30)
)
-- @statement
INSERT INTO bs_dml VALUES (1, 'A', 10, 'one')
-- @statement
INSERT INTO bs_dml VALUES (2, 'B', 20, 'two')
-- @statement
INSERT INTO bs_dml VALUES (3, 'C', 30, 'three')

-- @statement
CREATE TABLE bs_merge_src (
    id     NUMBER(6) PRIMARY KEY,
    amount NUMBER(12,2),
    note   VARCHAR2(30)
)
-- @statement
INSERT INTO bs_merge_src VALUES (1, 15, 'updated-one')
-- @statement
INSERT INTO bs_merge_src VALUES (4, 40, 'insert-four')
-- @statement
INSERT INTO bs_merge_src VALUES (5, 0, 'zero-five')

-- @statement
CREATE TABLE bs_long_text (
    id      NUMBER(4) PRIMARY KEY,
    payload VARCHAR2(100)
)
-- @statement
INSERT INTO bs_long_text(id, payload)
SELECT LEVEL, RPAD(CHR(64 + MOD(LEVEL - 1, 26) + 1), 100, CHR(64 + MOD(LEVEL - 1, 26) + 1))
FROM dual CONNECT BY LEVEL <= 60

-- @statement
CREATE TABLE bs_sales (
    deptno NUMBER(2),
    job    VARCHAR2(10),
    sal    NUMBER(10,2)
)
-- @statement
INSERT INTO bs_sales VALUES (10, 'DEV', 100)
-- @statement
INSERT INTO bs_sales VALUES (10, 'OPS', 50)
-- @statement
INSERT INTO bs_sales VALUES (20, 'DEV', 70)
-- @statement
INSERT INTO bs_sales VALUES (20, 'OPS', 80)

-- @statement
CREATE TABLE bs_wide (
    deptno  NUMBER(2),
    dev_sal NUMBER(10,2),
    ops_sal NUMBER(10,2)
)
-- @statement
INSERT INTO bs_wide VALUES (10, 100, 50)
-- @statement
INSERT INTO bs_wide VALUES (20, 70, 80)

-- @statement
CREATE TABLE bs_model (
    product VARCHAR2(10),
    year    NUMBER(4),
    sales   NUMBER(10,2)
)
-- @statement
INSERT INTO bs_model VALUES ('A', 2024, 100)
-- @statement
INSERT INTO bs_model VALUES ('A', 2025, 0)

-- @statement
CREATE TABLE bs_events (
    event_time NUMBER(4),
    value      NUMBER(10,2)
)
-- @statement
INSERT INTO bs_events VALUES (1, 10)
-- @statement
INSERT INTO bs_events VALUES (2, 20)
-- @statement
INSERT INTO bs_events VALUES (3, 15)

-- @statement
CREATE TABLE bs_byte_length (
    id             NUMBER(4) PRIMARY KEY,
    variable_value VARCHAR2(3),
    fixed_value    CHAR(3)
)
-- @statement
INSERT INTO bs_byte_length VALUES (9, 'A', 'A')

-- @statement
INSERT INTO bs_byte_length VALUES (10, 'B', NULL)

-- @statement
CREATE SEQUENCE bs_seq START WITH 100 INCREMENT BY 1 NOCACHE

-- @statement
CREATE OR REPLACE FUNCTION bs_fn_add(p_a NUMBER, p_b NUMBER)
RETURN NUMBER
IS
BEGIN
    RETURN p_a + p_b;
END;
-- @statement
CREATE OR REPLACE PROCEDURE bs_proc_math(p_in IN NUMBER, p_out OUT NUMBER, p_both IN OUT NUMBER)
IS
BEGIN
    p_out := p_in * 2;
    p_both := p_both + p_in;
END;
-- @statement
CREATE OR REPLACE PROCEDURE bs_ref_cursor(p_min IN NUMBER, p_out OUT SYS_REFCURSOR)
IS
BEGIN
    OPEN p_out FOR
        SELECT empno, ename FROM bs_emp WHERE empno >= p_min ORDER BY empno;
END;
-- @statement
CREATE OR REPLACE PACKAGE bs_pkg AS
    FUNCTION mul(p_a NUMBER, p_b NUMBER) RETURN NUMBER;
    PROCEDURE bump(p_value IN OUT NUMBER, p_delta IN NUMBER);
    PROCEDURE choose(p_value IN NUMBER, p_out OUT VARCHAR2);
    PROCEDURE choose(p_value IN VARCHAR2, p_out OUT VARCHAR2);
END bs_pkg;
-- @statement
CREATE OR REPLACE PACKAGE BODY bs_pkg AS
    FUNCTION mul(p_a NUMBER, p_b NUMBER) RETURN NUMBER IS
    BEGIN
        RETURN p_a * p_b;
    END;

    PROCEDURE bump(p_value IN OUT NUMBER, p_delta IN NUMBER) IS
    BEGIN
        p_value := p_value + p_delta;
    END;

    PROCEDURE choose(p_value IN NUMBER, p_out OUT VARCHAR2) IS
    BEGIN
        p_out := 'N:' || TO_CHAR(p_value);
    END;

    PROCEDURE choose(p_value IN VARCHAR2, p_out OUT VARCHAR2) IS
    BEGIN
        p_out := 'S:' || p_value;
    END;
END bs_pkg;
