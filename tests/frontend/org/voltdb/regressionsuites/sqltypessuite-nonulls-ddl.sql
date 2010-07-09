-- Attribute for ea. supported types: no nulls
CREATE TABLE NO_NULLS (
 PKEY          INTEGER       NOT NULL,
 A_TINYINT     TINYINT       NOT NULL,
 A_SMALLINT    SMALLINT      NOT NULL,
 A_INTEGER     INTEGER       NOT NULL,
 A_BIGINT      BIGINT        NOT NULL,
 A_FLOAT       FLOAT         NOT NULL,
 A_TIMESTAMP   TIMESTAMP     NOT NULL,
 A_INLINE_S1   VARCHAR(4)    NOT NULL,   -- smaller than ptr
 A_INLINE_S2   VARCHAR(63)   NOT NULL,   -- bigger than ptr
 A_POOL_S      VARCHAR(65536) NOT NULL,   -- not inlined
 A_POOL_MAX_S  VARCHAR(1048576) NOT NULL,  -- not inlined (max length)
 A_DECIMAL     DECIMAL       NOT NULL,   -- DECIMAL(19,12)
 PRIMARY KEY (PKEY)
);
