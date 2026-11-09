-- ================================================================
-- Схема БД для Лабораторной работы 5 (Платёжная система: Vert.x + jOOQ + PostgreSQL).
-- Идемпотентно: повторный запуск безопасен (IF NOT EXISTS).
-- Диапазон денег: M = 9999999999999999.99 (храним в константе как CHECK).
-- ================================================================

-- Деньги защищаются и на уровне БД: NUMERIC(18,2), без FLOAT/DOUBLE.

-- Банк-участник контура.
CREATE TABLE IF NOT EXISTS bank (
  bic        VARCHAR(11)   NOT NULL,
  name       VARCHAR(128)  NOT NULL,
  balance    NUMERIC(18,2) NOT NULL,
  updated_at TIMESTAMPTZ   NOT NULL DEFAULT now(),
  CONSTRAINT pk_bank PRIMARY KEY (bic),
  -- BIC: ровно 8 или 11 символов A-Z0-9, без пробелов/смены регистра.
  CONSTRAINT chk_bank_bic CHECK (bic ~ '^[A-Z0-9]{8}([A-Z0-9]{3})?$'),
  CONSTRAINT chk_bank_name CHECK (btrim(name) <> ''),
  -- 0 <= balance <= M
  CONSTRAINT chk_bank_balance CHECK (balance >= 0 AND balance <= 9999999999999999.99)
);

-- Платёж. Три участника -- три РАЗЛИЧНЫХ существующих банка.
CREATE TABLE IF NOT EXISTS payment_transaction (
  id                 UUID          NOT NULL,
  client_reference   VARCHAR(64)   NOT NULL,
  issuer_bic         VARCHAR(11)   NOT NULL,
  receiver_bic       VARCHAR(11)   NOT NULL,
  acquirer_bic       VARCHAR(11)   NOT NULL,
  amount             NUMERIC(18,2) NOT NULL,
  commission_amount  NUMERIC(18,2) NOT NULL,
  status             VARCHAR(16)   NOT NULL,
  created_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
  reversed_at        TIMESTAMPTZ,
  CONSTRAINT pk_payment_transaction PRIMARY KEY (id),
  CONSTRAINT fk_payment_issuer   FOREIGN KEY (issuer_bic)   REFERENCES bank (bic),
  CONSTRAINT fk_payment_receiver FOREIGN KEY (receiver_bic) REFERENCES bank (bic),
  CONSTRAINT fk_payment_acquirer FOREIGN KEY (acquirer_bic) REFERENCES bank (bic),
  CONSTRAINT chk_payment_client_reference CHECK (btrim(client_reference) <> ''),
  -- 0 < amount <= M
  CONSTRAINT chk_payment_amount CHECK (amount > 0 AND amount <= 9999999999999999.99),
  -- 0 <= commission_amount <= M
  CONSTRAINT chk_payment_commission CHECK (commission_amount >= 0 AND commission_amount <= 9999999999999999.99),
  CONSTRAINT chk_payment_status CHECK (status IN ('COMMITTED', 'REVERSED')),
  -- три различных банка
  CONSTRAINT chk_payment_distinct CHECK (
    issuer_bic <> receiver_bic AND issuer_bic <> acquirer_bic AND receiver_bic <> acquirer_bic
  )
);

CREATE INDEX IF NOT EXISTS idx_payment_transaction_issuer   ON payment_transaction (issuer_bic);
CREATE INDEX IF NOT EXISTS idx_payment_transaction_receiver ON payment_transaction (receiver_bic);
CREATE INDEX IF NOT EXISTS idx_payment_transaction_acquirer ON payment_transaction (acquirer_bic);

-- Уведомление банку (исторический документ; reversal его не меняет).
CREATE TABLE IF NOT EXISTS bank_notification (
  id             UUID          NOT NULL,
  transaction_id UUID          NOT NULL,
  bank_bic       VARCHAR(11)   NOT NULL,
  type           VARCHAR(20)   NOT NULL,
  payload_xml    XML           NOT NULL,
  created_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
  CONSTRAINT pk_bank_notification PRIMARY KEY (id),
  CONSTRAINT fk_notification_transaction FOREIGN KEY (transaction_id) REFERENCES payment_transaction (id),
  CONSTRAINT fk_notification_bank        FOREIGN KEY (bank_bic)       REFERENCES bank (bic),
  CONSTRAINT chk_notification_type CHECK (type IN ('DEBIT_REQUEST', 'CREDIT_ADVICE'))
);

CREATE INDEX IF NOT EXISTS idx_notification_transaction ON bank_notification (transaction_id);
CREATE INDEX IF NOT EXISTS idx_notification_bank        ON bank_notification (bank_bic);