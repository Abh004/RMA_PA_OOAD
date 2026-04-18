-- ============================================================
--  SCM Supply Chain Management System — MySQL Schema
--  Batch 1: 5 Subsystems
--  Subsystems covered:
--    1. Multi-level Pricing & Discount Management
--    2. Warehouse Management
--    3. Reporting & Analytics Dashboard (read-only views)
--    4. UI Subsystem (persisted data only)
--    5. Double-Entry Stock Keeping
--
--  Design Principles applied:
--    SOLID  — Single Responsibility: each table owns exactly one
--             business concept.
--    SOLID  — Open/Closed: new price types / tier levels can be
--             added as new rows without altering table structure.
--    GRASP  — Information Expert: each table stores the data it
--             is the natural owner of.
--    GRASP  — Low Coupling: cross-subsystem references use plain
--             VARCHAR FKs so external teams can evolve their PKs
--             without breaking this schema.
--
--  Pattern notes (referenced in Java layer):
--    Singleton — DatabaseConnectionPool (one pool for all DAOs)
--    Factory   — DAOFactory (produces the correct DAO instance)
--    Facade    — SupplyChainFacade (single entry point)
--    Observer  — EventBus (cross-subsystem event wiring)
--    Adapter   — BarcodeReaderAdapter, ExternalCRMAdapter
-- ============================================================

SET FOREIGN_KEY_CHECKS = 0;
SET NAMES utf8mb4;

-- ============================================================
-- SUBSYSTEM 1 — MULTI-LEVEL PRICING & DISCOUNT MANAGEMENT
-- ============================================================

-- -------------------------------------------------------
-- Component 1 — Price List Manager
-- Stores the active/historical price for every SKU,
-- broken down by region, channel and buyer type.
-- -------------------------------------------------------
CREATE DATABASE OOAD;

USE OOAD;
CREATE TABLE IF NOT EXISTS price_list (
    price_id        VARCHAR(50)    NOT NULL,
    sku_id          VARCHAR(50)    NOT NULL  COMMENT 'FK-style ref to Inventory SKU; owned externally',
    region_code     VARCHAR(20)    NOT NULL  COMMENT 'e.g. SOUTH, NORTH',
    channel         VARCHAR(30)    NOT NULL  COMMENT 'e.g. RETAIL, DISTRIBUTOR',
    price_type      ENUM('RETAIL','DISTRIBUTOR') NOT NULL COMMENT 'Buyer-level classification',
    base_price      DECIMAL(12,2)  NOT NULL  COMMENT 'Written by Component 3, read by Component 4',
    price_floor     DECIMAL(12,2)  NOT NULL  COMMENT 'Minimum price; discount engine must not go below this',
    currency_code   CHAR(3)        NOT NULL  DEFAULT 'INR' COMMENT 'ISO 4217 code',
    effective_from  DATETIME       NOT NULL,
    effective_to    DATETIME       NOT NULL,
    status          ENUM('ACTIVE','INACTIVE','SUPERSEDED') NOT NULL DEFAULT 'ACTIVE',

    PRIMARY KEY (price_id),

    -- A SKU must not have two active prices for the same
    -- region + channel + price_type combination.
    UNIQUE KEY uq_price_list_sku_region_channel_type
        (sku_id, region_code, channel, price_type, effective_from),

    -- Business rule: floor must be <= base price
    CONSTRAINT chk_price_floor CHECK (price_floor <= base_price),
    -- Prices cannot be negative
    CONSTRAINT chk_base_price_positive CHECK (base_price >= 0),
    -- Date range must be valid
    CONSTRAINT chk_price_date_range CHECK (effective_to > effective_from)
) COMMENT 'Component 1 — Price List Manager';


-- -------------------------------------------------------
-- Component 2a — Tier Definitions
-- Master list of customer tiers (Bronze, Silver, Gold).
-- Separated from segmentation so tiers can be reused
-- across many customers. (GRASP: Information Expert)
-- -------------------------------------------------------
CREATE TABLE IF NOT EXISTS tier_definitions (
    tier_id                 INT            NOT NULL,
    tier_name               VARCHAR(50)    NOT NULL  COMMENT 'e.g. Bronze, Silver, Gold',
    min_spend_threshold     DECIMAL(12,2)  NOT NULL  COMMENT 'Minimum cumulative spend to qualify',
    default_discount_pct    DECIMAL(5,2)   NOT NULL  COMMENT 'Default discount % for this tier',

    PRIMARY KEY (tier_id),
    UNIQUE KEY uq_tier_name (tier_name),

    CONSTRAINT chk_tier_discount CHECK (default_discount_pct BETWEEN 0 AND 100),
    CONSTRAINT chk_tier_spend    CHECK (min_spend_threshold >= 0)
) COMMENT 'Component 2a — Tier Definitions master table';


-- -------------------------------------------------------
-- Component 2b — Customer Segmentation
-- Maps each customer to a computed or manually-overridden
-- tier. customer_id is sourced from the CRM subsystem.
-- -------------------------------------------------------
CREATE TABLE IF NOT EXISTS customer_segmentation (
    segmentation_id     VARCHAR(50)    NOT NULL,
    customer_id         VARCHAR(50)    NOT NULL  COMMENT 'Ref to CRM subsystem; not a local FK',
    cumulative_spend    DECIMAL(14,2)  NOT NULL  DEFAULT 0.00 COMMENT 'Total historical spend from CRM',
    historical_order_totals DECIMAL(14,2) NOT NULL DEFAULT 0.00 COMMENT 'Read-only aggregated order value',
    assigned_tier_id    INT            NOT NULL,
    manual_override     BOOLEAN        NOT NULL  DEFAULT FALSE,
    override_tier_id    INT            NULL      COMMENT 'NULL unless manual_override = TRUE',

    PRIMARY KEY (segmentation_id),
    UNIQUE KEY uq_segmentation_customer (customer_id),

    FOREIGN KEY (assigned_tier_id)  REFERENCES tier_definitions(tier_id),
    FOREIGN KEY (override_tier_id)  REFERENCES tier_definitions(tier_id),

    -- If manual_override is TRUE, override_tier_id must be set
    CONSTRAINT chk_override_tier
        CHECK (manual_override = FALSE OR override_tier_id IS NOT NULL),
    CONSTRAINT chk_cumulative_spend CHECK (cumulative_spend >= 0)
) COMMENT 'Component 2b — Customer tier assignment and segmentation';


-- -------------------------------------------------------
-- Component 3 — Base Price Configuration
-- Pricing Admin inputs COGS and desired margin.
-- computed_base_price is derived and written to price_list.
-- -------------------------------------------------------
CREATE TABLE IF NOT EXISTS price_configuration (
    price_config_id         VARCHAR(50)    NOT NULL,
    sku_id                  VARCHAR(50)    NOT NULL  COMMENT 'Product this config applies to',
    cogs_value              DECIMAL(12,2)  NOT NULL  COMMENT 'Read-only; fetched from Warehouse subsystem',
    desired_margin_pct      DECIMAL(5,2)   NOT NULL  COMMENT 'Target margin % entered by Pricing Admin',
    computed_base_price     DECIMAL(12,2)  NOT NULL  COMMENT 'Derived: COGS / (1 - margin%). Written to price_list',
    product_attributes      TEXT           NULL      COMMENT 'Read-only metadata from Inventory subsystem',
    created_at              DATETIME       NOT NULL  DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (price_config_id),

    CONSTRAINT chk_margin_pct   CHECK (desired_margin_pct > 0 AND desired_margin_pct < 100),
    CONSTRAINT chk_cogs_positive CHECK (cogs_value > 0)
) COMMENT 'Component 3 — Base price configuration; computes base_price from COGS + margin';


-- -------------------------------------------------------
-- Component 4 — Discount Rules Engine
-- Records the outcome of the discount calculation for
-- each order line. order_id / order_line_id come from
-- the POS or Order Fulfillment subsystem.
-- -------------------------------------------------------
CREATE TABLE IF NOT EXISTS discount_rule_results (
    order_line_id           VARCHAR(50)    NOT NULL  COMMENT 'PK; sourced from Order subsystem',
    order_id                VARCHAR(50)    NOT NULL  COMMENT 'Parent order; sourced from POS / Order subsystem',
    quantity                INT            NOT NULL  COMMENT 'Units on this line; used for volume discount',
    batch_expiry_date       DATETIME       NULL      COMMENT 'Written by Warehouse; read by rules engine',
    final_price             DECIMAL(12,2)  NULL      COMMENT 'Computed discounted unit price',
    applied_discount_pct    DECIMAL(5,2)   NULL      COMMENT 'Total combined discount %',
    discount_breakdown      TEXT           NULL      COMMENT 'Itemised log of every discount applied',
    computed_at             DATETIME       NOT NULL  DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (order_line_id),

    CONSTRAINT chk_quantity_positive   CHECK (quantity > 0),
    CONSTRAINT chk_final_price_pos     CHECK (final_price IS NULL OR final_price >= 0),
    CONSTRAINT chk_discount_pct_range  CHECK (applied_discount_pct IS NULL
                                           OR applied_discount_pct BETWEEN 0 AND 100)
) COMMENT 'Component 4 — Discount Rules Engine output per order line';


-- -------------------------------------------------------
-- Component 5 — Promotion & Campaign Manager
-- Stores promo codes and their redemption counters.
-- eligible_sku_ids is stored as JSON for flexibility.
-- -------------------------------------------------------
CREATE TABLE IF NOT EXISTS promotions (
    promo_id            VARCHAR(50)    NOT NULL,
    promo_name          VARCHAR(100)   NOT NULL,
    coupon_code         VARCHAR(50)    NOT NULL,
    discount_type       ENUM('PERCENTAGE_OFF','FIXED_AMOUNT','BUY_X_GET_Y') NOT NULL,
    discount_value      DECIMAL(10,2)  NOT NULL,
    start_date          DATETIME       NOT NULL,
    end_date            DATETIME       NOT NULL,
    eligible_sku_ids    JSON           NOT NULL  COMMENT 'JSON array of SKU IDs this promo applies to',
    min_cart_value      DECIMAL(12,2)  NOT NULL  DEFAULT 0.00,
    max_uses            INT            NOT NULL,
    current_use_count   INT            NOT NULL  DEFAULT 0,

    PRIMARY KEY (promo_id),
    UNIQUE KEY uq_coupon_code (coupon_code),

    CONSTRAINT chk_promo_date_range     CHECK (end_date > start_date),
    CONSTRAINT chk_promo_discount_val   CHECK (discount_value > 0),
    CONSTRAINT chk_promo_max_uses       CHECK (max_uses > 0),
    CONSTRAINT chk_use_count_not_exceed CHECK (current_use_count <= max_uses),
    CONSTRAINT chk_min_cart_value       CHECK (min_cart_value >= 0)
) COMMENT 'Component 5 — Promotion and campaign manager';


-- -------------------------------------------------------
-- Component 6 — Discount Policy Store
-- Global rules governing how discounts stack and cap.
-- -------------------------------------------------------
CREATE TABLE IF NOT EXISTS discount_policies (
    policy_id               VARCHAR(50)    NOT NULL,
    policy_name             VARCHAR(100)   NOT NULL,
    stacking_rule           ENUM('EXCLUSIVE','ADDITIVE') NOT NULL COMMENT 'How discounts combine',
    priority_level          INT            NOT NULL  COMMENT 'Higher = applied first when conflicts arise',
    max_discount_cap_pct    DECIMAL(5,2)   NOT NULL  COMMENT 'Absolute ceiling for combined discounts',
    perishability_days      INT            NOT NULL  COMMENT 'Days-to-expiry that triggers clearance markdown',
    clearance_discount_pct  DECIMAL(5,2)   NOT NULL  COMMENT 'Auto-applied markdown % near expiry',

    PRIMARY KEY (policy_id),
    UNIQUE KEY uq_policy_priority (priority_level),

    CONSTRAINT chk_cap_pct          CHECK (max_discount_cap_pct BETWEEN 0 AND 100),
    CONSTRAINT chk_clearance_pct    CHECK (clearance_discount_pct BETWEEN 0 AND 100),
    CONSTRAINT chk_perish_days      CHECK (perishability_days > 0)
) COMMENT 'Component 6 — Discount policy and stacking rules';


-- -------------------------------------------------------
-- Component 7 — Contract Pricing Module
-- B2B negotiated prices per customer per SKU.
-- -------------------------------------------------------
CREATE TABLE IF NOT EXISTS contract_pricing (
    contract_id             VARCHAR(50)    NOT NULL,
    contract_customer_id    VARCHAR(50)    NOT NULL  COMMENT 'FK-style ref to CRM customer',
    contract_sku_id         VARCHAR(50)    NOT NULL  COMMENT 'FK-style ref to Inventory SKU',
    negotiated_price        DECIMAL(12,2)  NOT NULL  COMMENT 'Locked price agreed by Sales Rep',
    contract_start_date     DATETIME       NOT NULL,
    contract_expiry_date    DATETIME       NOT NULL,
    contract_status         ENUM('ACTIVE','EXPIRED','PENDING') NOT NULL DEFAULT 'PENDING',

    PRIMARY KEY (contract_id),
    -- A customer cannot have two active contracts for the same SKU
    UNIQUE KEY uq_contract_customer_sku (contract_customer_id, contract_sku_id, contract_start_date),

    CONSTRAINT chk_contract_price_pos  CHECK (negotiated_price > 0),
    CONSTRAINT chk_contract_date_range CHECK (contract_expiry_date > contract_start_date)
) COMMENT 'Component 7 — B2B contract pricing per customer per SKU';


-- -------------------------------------------------------
-- Component 8 — Price Approval & Workflow Engine
-- Audit trail for all manual price override requests.
-- -------------------------------------------------------
CREATE TABLE IF NOT EXISTS price_approvals (
    approval_id             VARCHAR(50)    NOT NULL,
    request_type            ENUM('MANUAL_DISCOUNT','CONTRACT_BYPASS','POLICY_EXCEPTION') NOT NULL,
    requested_by            VARCHAR(50)    NOT NULL  COMMENT 'Employee ID of cashier / sales rep',
    requested_discount_amt  DECIMAL(10,2)  NOT NULL  COMMENT 'Discount amount or % being requested',
    justification_text      TEXT           NOT NULL  COMMENT 'Free-text reason for the override',
    approving_manager_id    VARCHAR(50)    NULL      COMMENT 'Determined from login hierarchy; set externally',
    approval_status         ENUM('PENDING','APPROVED','REJECTED','ESCALATED') NOT NULL DEFAULT 'PENDING',
    approval_timestamp      DATETIME       NULL      COMMENT 'Set when manager acts on request',
    audit_log_flag          BOOLEAN        NOT NULL  DEFAULT FALSE COMMENT 'TRUE once audit entry is written',
    created_at              DATETIME       NOT NULL  DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (approval_id),

    CONSTRAINT chk_approval_discount_pos CHECK (requested_discount_amt > 0)
) COMMENT 'Component 8 — Price approval workflow and audit trail';


-- ============================================================
-- SUBSYSTEM 2 — WAREHOUSE MANAGEMENT
-- The raw data has repeating productId / binId groups which
-- indicate separate logical tables. They are separated here.
-- productName, sku, employeeId, employeeName are read-only
-- refs from external subsystems — stored as VARCHAR.
-- ============================================================

-- -------------------------------------------------------
-- Master: Warehouses
-- -------------------------------------------------------
CREATE TABLE IF NOT EXISTS warehouses (
    warehouse_id    VARCHAR(50)  NOT NULL,
    warehouse_name  VARCHAR(100) NOT NULL,

    PRIMARY KEY (warehouse_id),
    UNIQUE KEY uq_warehouse_name (warehouse_name)
) COMMENT 'Warehouse master — top-level location entity';


-- -------------------------------------------------------
-- Zones within a warehouse
-- -------------------------------------------------------
CREATE TABLE IF NOT EXISTS warehouse_zones (
    zone_id         VARCHAR(50)  NOT NULL,
    warehouse_id    VARCHAR(50)  NOT NULL,
    zone_type       ENUM('STORAGE','PICKING','STAGING','RECEIVING','DISPATCH') NOT NULL,

    PRIMARY KEY (zone_id),
    FOREIGN KEY (warehouse_id) REFERENCES warehouses(warehouse_id)
        ON DELETE CASCADE
) COMMENT 'Zone sub-division within a warehouse';


-- -------------------------------------------------------
-- Bins within a zone
-- -------------------------------------------------------
CREATE TABLE IF NOT EXISTS bins (
    bin_id          VARCHAR(50)  NOT NULL,
    zone_id         VARCHAR(50)  NOT NULL,
    bin_capacity    INT          NOT NULL  COMMENT 'Maximum unit capacity of this bin',
    bin_status      ENUM('AVAILABLE','OCCUPIED','RESERVED','DAMAGED') NOT NULL DEFAULT 'AVAILABLE',

    PRIMARY KEY (bin_id),
    FOREIGN KEY (zone_id) REFERENCES warehouse_zones(zone_id)
        ON DELETE CASCADE,

    CONSTRAINT chk_bin_capacity CHECK (bin_capacity > 0)
) COMMENT 'Individual storage bin within a zone';


-- -------------------------------------------------------
-- Goods Receipt — records inbound PO deliveries
-- -------------------------------------------------------
CREATE TABLE IF NOT EXISTS goods_receipts (
    goods_receipt_id    VARCHAR(50)  NOT NULL,
    purchase_order_id   VARCHAR(50)  NOT NULL  COMMENT 'Ref to Purchase Order subsystem',
    supplier_id         VARCHAR(50)  NOT NULL  COMMENT 'Ref to Supplier subsystem',
    product_id          VARCHAR(50)  NOT NULL  COMMENT 'Ref to Inventory subsystem',
    ordered_qty         INT          NOT NULL,
    received_qty        INT          NOT NULL,
    received_at         DATETIME     NOT NULL  DEFAULT CURRENT_TIMESTAMP,
    condition_status    ENUM('GOOD','DAMAGED','PARTIAL','REJECTED') NOT NULL DEFAULT 'GOOD',

    PRIMARY KEY (goods_receipt_id),

    CONSTRAINT chk_received_qty CHECK (received_qty >= 0),
    CONSTRAINT chk_ordered_qty  CHECK (ordered_qty > 0)
) COMMENT 'Records inbound goods received against a purchase order';


-- -------------------------------------------------------
-- Stock Records — current inventory per bin per product
-- -------------------------------------------------------
CREATE TABLE IF NOT EXISTS stock_records (
    stock_id        VARCHAR(50)  NOT NULL,
    product_id      VARCHAR(50)  NOT NULL  COMMENT 'Ref to Inventory subsystem',
    bin_id          VARCHAR(50)  NOT NULL,
    quantity        INT          NOT NULL  DEFAULT 0,
    last_updated    DATETIME     NOT NULL  DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    PRIMARY KEY (stock_id),
    UNIQUE KEY uq_stock_product_bin (product_id, bin_id),
    FOREIGN KEY (bin_id) REFERENCES bins(bin_id),

    CONSTRAINT chk_stock_qty CHECK (quantity >= 0)
) COMMENT 'Current stock level per product per bin';


-- -------------------------------------------------------
-- Stock Movements — audit trail of every bin transfer
-- -------------------------------------------------------
CREATE TABLE IF NOT EXISTS stock_movements (
    movement_id     VARCHAR(50)  NOT NULL,
    movement_type   ENUM('INBOUND','OUTBOUND','TRANSFER','ADJUSTMENT','RETURN') NOT NULL,
    from_bin        VARCHAR(50)  NULL  COMMENT 'NULL for inbound movements',
    to_bin          VARCHAR(50)  NULL  COMMENT 'NULL for outbound movements',
    product_id      VARCHAR(50)  NOT NULL,
    moved_qty       INT          NOT NULL,
    movement_ts     DATETIME     NOT NULL  DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (movement_id),
    FOREIGN KEY (from_bin) REFERENCES bins(bin_id),
    FOREIGN KEY (to_bin)   REFERENCES bins(bin_id),

    CONSTRAINT chk_moved_qty CHECK (moved_qty > 0),
    -- At least one of from_bin or to_bin must be set
    CONSTRAINT chk_movement_bins CHECK (from_bin IS NOT NULL OR to_bin IS NOT NULL)
) COMMENT 'Audit trail of all stock movements between bins';


-- -------------------------------------------------------
-- Pick Tasks — assigned to employees for order picking
-- orderId is read-only; comes from Order Fulfillment.
-- -------------------------------------------------------
CREATE TABLE IF NOT EXISTS pick_tasks (
    pick_task_id        VARCHAR(50)  NOT NULL,
    order_id            VARCHAR(50)  NOT NULL  COMMENT 'Read-only ref from Order Fulfillment subsystem',
    assigned_employee_id VARCHAR(50) NOT NULL  COMMENT 'Ref to HR / Employee subsystem',
    product_id          VARCHAR(50)  NOT NULL,
    pick_qty            INT          NOT NULL,
    task_status         ENUM('PENDING','IN_PROGRESS','COMPLETED','CANCELLED') NOT NULL DEFAULT 'PENDING',

    PRIMARY KEY (pick_task_id),

    CONSTRAINT chk_pick_qty CHECK (pick_qty > 0)
) COMMENT 'Pick tasks assigned to warehouse employees for order fulfillment';


-- -------------------------------------------------------
-- Staging & Dispatch — outbound shipment staging
-- -------------------------------------------------------
CREATE TABLE IF NOT EXISTS staging_dispatch (
    staging_id      VARCHAR(50)  NOT NULL,
    dock_door_id    VARCHAR(50)  NOT NULL,
    order_id        VARCHAR(50)  NOT NULL  COMMENT 'Ref to Order subsystem',
    dispatched_at   DATETIME     NULL      COMMENT 'NULL until dispatched',
    shipment_status ENUM('STAGED','LOADED','DISPATCHED','CANCELLED') NOT NULL DEFAULT 'STAGED',

    PRIMARY KEY (staging_id)
) COMMENT 'Staging and dispatch records for outbound shipments';


-- -------------------------------------------------------
-- Returns — inbound return records
-- -------------------------------------------------------
CREATE TABLE IF NOT EXISTS warehouse_returns (
    return_id           VARCHAR(50)  NOT NULL,
    product_id          VARCHAR(50)  NOT NULL,
    return_qty          INT          NOT NULL,
    condition_status    ENUM('GOOD','DAMAGED','PARTIAL','REJECTED') NOT NULL,
    return_ts           DATETIME     NOT NULL  DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (return_id),

    CONSTRAINT chk_return_qty CHECK (return_qty > 0)
) COMMENT 'Records of returned goods received at warehouse';


-- -------------------------------------------------------
-- Cycle Counts — periodic stock accuracy checks
-- productId, productName, sku are read-only from Inventory.
-- employeeId, employeeName are read-only from HR.
-- -------------------------------------------------------
CREATE TABLE IF NOT EXISTS cycle_counts (
    cycle_count_id  VARCHAR(50)  NOT NULL,
    product_id      VARCHAR(50)  NOT NULL  COMMENT 'Read-only ref from Inventory subsystem',
    product_name    VARCHAR(100) NOT NULL  COMMENT 'Read-only snapshot from Inventory',
    sku             VARCHAR(50)  NOT NULL  COMMENT 'Read-only snapshot from Inventory',
    employee_id     VARCHAR(50)  NOT NULL  COMMENT 'Read-only ref from HR subsystem',
    employee_name   VARCHAR(100) NOT NULL  COMMENT 'Read-only snapshot from HR',
    expected_qty    INT          NOT NULL,
    counted_qty     INT          NOT NULL,
    count_ts        DATETIME     NOT NULL  DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (cycle_count_id),

    CONSTRAINT chk_expected_qty CHECK (expected_qty >= 0),
    CONSTRAINT chk_counted_qty  CHECK (counted_qty >= 0)
) COMMENT 'Periodic physical stock counts for accuracy verification';


-- ============================================================
-- SUBSYSTEM 3 — REPORTING & ANALYTICS DASHBOARD
-- This subsystem is entirely read-only. No new base tables
-- are created. Instead, we define MySQL VIEWs that aggregate
-- data from Subsystems 1 and 2 (and will extend to orders,
-- suppliers, shipments once those subsystems are added).
-- GRASP — Information Expert: data stays in its owning table;
-- the view just projects it for reporting consumers.
-- ============================================================

CREATE OR REPLACE VIEW vw_inventory_stock_report AS
    -- Stock levels per product per bin with warehouse context
    SELECT
        sr.product_id       AS productID,
        sr.bin_id,
        b.zone_id,
        wz.warehouse_id     AS warehouseID,
        sr.quantity         AS current_stock_level,
        sr.last_updated
    FROM stock_records sr
    JOIN bins           b  ON sr.bin_id    = b.bin_id
    JOIN warehouse_zones wz ON b.zone_id   = wz.zone_id;


CREATE OR REPLACE VIEW vw_price_discount_report AS
    -- Active prices with promotion and discount context
    SELECT
        pl.sku_id           AS productID,
        pl.base_price       AS product_price,
        pl.region_code,
        pl.channel,
        pl.currency_code,
        pl.status
    FROM price_list pl
    WHERE pl.status = 'ACTIVE';


CREATE OR REPLACE VIEW vw_exception_report AS
    -- Approval requests flagged for audit (exception tracking)
    SELECT
        pa.approval_id      AS exceptionID,
        pa.request_type     AS exceptionType,
        pa.approval_status  AS severity_level,
        pa.created_at       AS timestamp,
        pa.requested_by,
        pa.justification_text
    FROM price_approvals pa
    WHERE pa.approval_status IN ('REJECTED','ESCALATED');


-- vw_reporting_dashboard is created later in the file after all
-- dependent tables have been defined (orders, products, delivery_orders,
-- demand_forecasts, subsystem_exceptions, commission_sales, etc.).


-- ============================================================
-- SUBSYSTEM 4 — UI SUBSYSTEM (PERSISTED DATA ONLY)
-- Only data that is genuinely stored in the DB is modelled.
-- Frontend-assembled objects (chartDatasetArray, routePlan,
-- List<Product> etc.) are service-layer concerns, not DB rows.
-- ============================================================

-- -------------------------------------------------------
-- C-01 — Users & Authentication
-- password is stored as a bcrypt hash — never plaintext.
-- -------------------------------------------------------
CREATE TABLE IF NOT EXISTS ui_users (
    user_id                 INT            NOT NULL AUTO_INCREMENT,
    username                VARCHAR(100)   NOT NULL,
    password_hash           VARCHAR(255)   NOT NULL  COMMENT 'bcrypt hash; never store plaintext',
    two_factor_token        INT            NULL,
    authorized_menu_items   TEXT           NULL,
    user_role               ENUM('ADMIN','MANAGER','SALES_REP','WAREHOUSE_STAFF',
                                 'CASHIER','ANALYST','DRIVER') NOT NULL,
    is_account_locked       BOOLEAN        NOT NULL  DEFAULT FALSE,
    login_attempt_count     INT            NOT NULL  DEFAULT 0,
    last_login_timestamp    DATETIME       NULL,
    user_email              VARCHAR(150)   NOT NULL,
    user_display_name       VARCHAR(100)   NOT NULL,
    theme_preference        VARCHAR(20)    NOT NULL  DEFAULT 'LIGHT',
    language_preference     VARCHAR(10)    NOT NULL  DEFAULT 'en',
    created_at              DATETIME       NOT NULL  DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (user_id),
    UNIQUE KEY uq_username (username),
    UNIQUE KEY uq_email    (user_email),

    CONSTRAINT chk_login_attempts CHECK (login_attempt_count >= 0)
) COMMENT 'UI C-01/C-10 — User accounts, roles, and profile settings';


-- -------------------------------------------------------
-- C-01 — Active Sessions (JWT tokens)
-- -------------------------------------------------------
CREATE TABLE IF NOT EXISTS ui_sessions (
    session_id          INT            NOT NULL AUTO_INCREMENT,
    user_id             INT            NOT NULL,
    jwt_session_token   VARCHAR(512)   NOT NULL  COMMENT 'Signed JWT; validated on each request',
    redirect_panel_url  VARCHAR(255)   NULL,
    session_expiry_time BIGINT         NOT NULL  COMMENT 'Unix epoch millis',
    session_status      VARCHAR(20)    NOT NULL  DEFAULT 'ACTIVE',
    created_at          DATETIME       NOT NULL  DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (session_id),
    UNIQUE KEY uq_jwt_token (jwt_session_token(255)),
    FOREIGN KEY (user_id) REFERENCES ui_users(user_id)
        ON DELETE CASCADE
) COMMENT 'UI C-01 — Active JWT sessions per user';


-- -------------------------------------------------------
-- C-02 — Navigation Panel State
-- Persists which panel each user last had open.
-- -------------------------------------------------------
CREATE TABLE IF NOT EXISTS ui_panel_state (
    panel_state_id      INT            NOT NULL AUTO_INCREMENT,
    user_id             INT            NOT NULL,
    panel_id            VARCHAR(50)    NOT NULL,
    notification_count  INT            NULL,
    current_panel_state VARCHAR(100)   NOT NULL,
    breadcrumb_trail    TEXT           NULL,
    sidebar_menu_items  TEXT           NULL,
    active_user_role    VARCHAR(50)    NULL,
    updated_at          DATETIME       NOT NULL  DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    PRIMARY KEY (panel_state_id),
    UNIQUE KEY uq_user_panel (user_id, panel_id),
    FOREIGN KEY (user_id) REFERENCES ui_users(user_id)
        ON DELETE CASCADE
) COMMENT 'UI C-02 — Persisted panel navigation state per user';


-- -------------------------------------------------------
-- C-09 — Notifications
-- -------------------------------------------------------
CREATE TABLE IF NOT EXISTS ui_notifications (
    notification_id     INT            NOT NULL AUTO_INCREMENT,
    user_id             INT            NOT NULL,
    notification_type   VARCHAR(50)    NOT NULL,
    notification_message VARCHAR(500)  NOT NULL,
    is_read             BOOLEAN        NOT NULL  DEFAULT FALSE,
    created_at          DATETIME       NOT NULL  DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (notification_id),
    FOREIGN KEY (user_id) REFERENCES ui_users(user_id)
        ON DELETE CASCADE
) COMMENT 'UI C-09 — Notification inbox per user';


-- -------------------------------------------------------
-- C-09 — Audit Log
-- Immutable audit trail; no UPDATE or DELETE allowed
-- on this table (enforced at the DAO layer).
-- -------------------------------------------------------
CREATE TABLE IF NOT EXISTS ui_audit_log (
    audit_id                INT            NOT NULL AUTO_INCREMENT,
    audit_timestamp         DATETIME       NOT NULL  DEFAULT CURRENT_TIMESTAMP,
    audit_action_user       VARCHAR(100)   NOT NULL,
    audit_action_description VARCHAR(500)  NOT NULL,
    audit_module_name       VARCHAR(100)   NOT NULL,

    PRIMARY KEY (audit_id)
) COMMENT 'UI C-09 — Immutable audit log; INSERT only, no UPDATE/DELETE';


-- -------------------------------------------------------
-- C-10 — Notification Preferences
-- Stored as individual rows for each preference key
-- rather than a JSON blob for queryability.
-- -------------------------------------------------------
CREATE TABLE IF NOT EXISTS ui_notification_preferences (
    pref_id         INT            NOT NULL AUTO_INCREMENT,
    user_id         INT            NOT NULL,
    pref_key        VARCHAR(100)   NOT NULL  COMMENT 'e.g. EMAIL_ON_LOW_STOCK',
    pref_value      BOOLEAN        NOT NULL  DEFAULT TRUE,

    PRIMARY KEY (pref_id),
    UNIQUE KEY uq_user_pref_key (user_id, pref_key),
    FOREIGN KEY (user_id) REFERENCES ui_users(user_id)
        ON DELETE CASCADE
) COMMENT 'UI C-10 — Per-user notification preferences';


-- -------------------------------------------------------
-- C-10 — System Configuration
-- Key-value store for global system settings.
-- -------------------------------------------------------
CREATE TABLE IF NOT EXISTS ui_system_config (
    config_key      VARCHAR(100)   NOT NULL,
    config_value    VARCHAR(500)   NOT NULL,
    updated_at      DATETIME       NOT NULL  DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    PRIMARY KEY (config_key)
) COMMENT 'UI C-10 — System-wide configuration key-value store';


-- ============================================================
-- SUBSYSTEM 5 — DOUBLE-ENTRY STOCK KEEPING
-- Classic double-entry ledger: every stock movement has a
-- debit account and a credit account. The ledger is
-- append-only (no UPDATE/DELETE) — enforced at DAO layer.
-- SOLID — Single Responsibility: this table only records
-- the ledger entry; stock_records owns current quantities.
-- ============================================================

CREATE TABLE IF NOT EXISTS stock_ledger_entries (
    ledger_id           INT            NOT NULL AUTO_INCREMENT COMMENT 'Surrogate PK for DB use',
    transaction_id      VARCHAR(50)    NOT NULL  COMMENT 'Business-level transaction identifier; read-only after insert',
    transaction_type    VARCHAR(50)    NOT NULL  COMMENT 'e.g. INBOUND, SALE, RETURN, ADJUSTMENT',
    item_name           VARCHAR(100)   NOT NULL,
    quantity            INT            NOT NULL,
    unit                VARCHAR(20)    NOT NULL  COMMENT 'e.g. KG, PCS, LITRE',
    debit_account       VARCHAR(100)   NOT NULL  COMMENT 'System-determined; not written by application',
    credit_account      VARCHAR(100)   NOT NULL  COMMENT 'System-determined; not written by application',
    entry_date          DATE           NOT NULL  COMMENT 'System-set on insert; not updatable',
    reference_number    VARCHAR(50)    NOT NULL  COMMENT 'Links to source document (PO, sale order, etc.)',
    total_debit         DECIMAL(14,2)  NOT NULL  COMMENT 'System-computed; read-only',
    total_credit        DECIMAL(14,2)  NOT NULL  COMMENT 'System-computed; read-only',
    balance_status      VARCHAR(20)    NOT NULL  COMMENT 'BALANCED / UNBALANCED; system-computed',

    PRIMARY KEY (ledger_id),
    UNIQUE KEY uq_transaction_id (transaction_id),

    CONSTRAINT chk_ledger_qty          CHECK (quantity > 0),
    -- Double-entry invariant: debits must equal credits per entry
    CONSTRAINT chk_double_entry_balance CHECK (total_debit = total_credit),
    CONSTRAINT chk_debit_positive       CHECK (total_debit > 0)
) COMMENT 'Subsystem 5 — Double-entry stock ledger; INSERT only, no updates';

-- ============================================================
-- ADDITIONAL SUBSYSTEMS (EXTENSION — FULLY DOCUMENTED)
-- These components extend the system without modifying
-- existing schema. Designed following GRASP and SOLID.
-- ============================================================


-- ============================================================
-- SUBSYSTEM — INVENTORY MANAGEMENT
-- This subsystem manages product-level data, stock visibility,
-- batch tracking, and operational inventory decisions.
-- It complements Warehouse (location-based) but does not
-- duplicate its responsibilities (GRASP: Information Expert).
-- ============================================================

-- -------------------------------------------------------
-- Component 1 — Stock Level Monitoring
-- Used by InventoryController to track real-time stock,
-- reserved stock, and trigger reorder decisions.
-- -------------------------------------------------------
CREATE TABLE IF NOT EXISTS stock_levels (
    stock_level_id        VARCHAR(50)    NOT NULL,
    product_id            VARCHAR(50)    NOT NULL COMMENT 'Ref to Product Master',
    current_stock_qty     INT            NOT NULL COMMENT 'Total physical stock',
    reserved_stock_qty    INT            NOT NULL DEFAULT 0 COMMENT 'Allocated to orders',
    available_stock_qty   INT            NOT NULL COMMENT 'Available = current - reserved',
    reorder_threshold     INT            NOT NULL COMMENT 'Minimum stock before reorder',
    reorder_quantity      INT            NOT NULL COMMENT 'Suggested reorder quantity',
    safety_stock_level    INT            NOT NULL COMMENT 'Buffer stock to avoid stockouts',
    zone_assignment       VARCHAR(100)   NULL COMMENT 'Zone assignment for stock placement',
    stock_health_status   VARCHAR(50)    NULL COMMENT 'Healthy, low stock, critical, etc.',
    snapshot_timestamp    DATETIME       NULL COMMENT 'Inventory snapshot time',
    last_updated          DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (stock_level_id),

    CONSTRAINT chk_stock_positive CHECK (current_stock_qty >= 0),
    CONSTRAINT chk_reserved_positive CHECK (reserved_stock_qty >= 0),
    CONSTRAINT chk_available_positive CHECK (available_stock_qty >= 0)
) COMMENT 'Tracks stock levels, availability, and reorder signals';


-- -------------------------------------------------------
-- Component 2 — Product Master
-- Owned by Inventory subsystem. Provides product metadata
-- used across Pricing, Warehouse, and Order subsystems.
-- -------------------------------------------------------
CREATE TABLE IF NOT EXISTS products (
    product_id            VARCHAR(50)    NOT NULL,
    product_name          VARCHAR(150)   NOT NULL,
    sku                   VARCHAR(50)    NOT NULL COMMENT 'Unique Stock Keeping Unit',
    category              VARCHAR(100)   NOT NULL,
    sub_category          VARCHAR(100)   NOT NULL,
    supplier_id           VARCHAR(50)    NOT NULL COMMENT 'Ref to Supplier subsystem',
    unit_of_measure       VARCHAR(20)    NOT NULL COMMENT 'e.g. PCS, KG, LITRE',
    zone                  VARCHAR(100)   NULL COMMENT 'Default storage or catalog zone',
    reorder_threshold     INT            NULL COMMENT 'Product-level reorder threshold',
    product_image_reference VARCHAR(255) NULL COMMENT 'External image reference for catalog UI',
    storage_conditions    VARCHAR(255)   NULL COMMENT 'Temperature, humidity constraints',
    shelf_life_days       INT            NULL COMMENT 'Expected usable duration',
    created_at            DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (product_id),
    UNIQUE KEY uq_sku (sku)
) COMMENT 'Central product catalog shared across subsystems';


-- -------------------------------------------------------
-- Component 3 — Batch & Lot Tracking
-- Enables traceability for compliance, recalls, and
-- quality tracking. Used by Warehouse and Expiry modules.
-- -------------------------------------------------------
CREATE TABLE IF NOT EXISTS product_batches (
    batch_id              VARCHAR(50)    NOT NULL,
    product_id            VARCHAR(50)    NOT NULL,
    lot_id                VARCHAR(50)    NOT NULL,
    manufacturing_date    DATE           NOT NULL,
    supplier_id           VARCHAR(50)    NOT NULL,
    batch_status          VARCHAR(50)    NOT NULL COMMENT 'e.g. ACTIVE, BLOCKED',
    linked_sku            VARCHAR(50)    NULL,
    quantity_received     INT            NULL,
    receipt_date          DATETIME       NULL,
    expiry_date           DATE           NULL,
    lot_status            VARCHAR(50)    NULL,
    perishability_flag    BOOLEAN        NULL,
    received_date         DATETIME       NOT NULL,

    PRIMARY KEY (batch_id)
) COMMENT 'Tracks batches and lot-level traceability';


-- -------------------------------------------------------
-- Component 4 — Expiry Management
-- Used by InventoryController to monitor perishable goods
-- and trigger alerts or clearance actions.
-- -------------------------------------------------------
CREATE TABLE IF NOT EXISTS expiry_tracking (
    expiry_id             VARCHAR(50)    NOT NULL,
    batch_id              VARCHAR(50)    NOT NULL,
    expiry_date           DATE           NOT NULL,
    days_remaining        INT            NOT NULL,
    expiry_status         VARCHAR(50)    NOT NULL COMMENT 'VALID, EXPIRING, EXPIRED',
    alert_flag            BOOLEAN        NOT NULL COMMENT 'Triggers alert if TRUE',
    expiry_trigger_flag   VARCHAR(50)    NULL,
    lot_status            VARCHAR(50)    NULL,

    PRIMARY KEY (expiry_id)
) COMMENT 'Tracks expiry status and alert conditions';


-- -------------------------------------------------------
-- Component 5 — Stock Adjustment
-- Captures manual/system corrections to stock. Provides
-- auditability and traceability (GRASP: Information Expert).
-- -------------------------------------------------------
CREATE TABLE IF NOT EXISTS stock_adjustments (
    adjustment_id         VARCHAR(50)    NOT NULL,
    product_id            VARCHAR(50)    NOT NULL,
    batch_id              VARCHAR(50)    NULL,
    adjustment_type       ENUM('INCREASE','DECREASE','CORRECTION') NOT NULL,
    quantity_adjusted     INT            NOT NULL,
    reason                VARCHAR(255)   NOT NULL,
    adjusted_by           VARCHAR(50)    NOT NULL COMMENT 'Employee reference',
    adjusted_at           DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    sku_reference         VARCHAR(50)    NULL,
    performer             VARCHAR(50)    NULL,
    reason_note           VARCHAR(255)   NULL,
    audit_lock_flag       BOOLEAN        NOT NULL DEFAULT FALSE,
    adjustment_date       DATETIME       NULL,

    PRIMARY KEY (adjustment_id),

    CONSTRAINT chk_adjustment_qty CHECK (quantity_adjusted > 0)
) COMMENT 'Maintains audit trail for stock changes';


-- -------------------------------------------------------
-- Component 6 — Reorder Management
-- Determines replenishment requirements based on stock
-- levels and thresholds. Used by Procurement subsystem.
-- -------------------------------------------------------
CREATE TABLE IF NOT EXISTS reorder_management (
    reorder_id            VARCHAR(50)    NOT NULL,
    product_id            VARCHAR(50)    NOT NULL,
    current_stock         INT            NOT NULL,
    reorder_threshold     INT            NOT NULL,
    reorder_quantity      INT            NOT NULL,
    supplier_id           VARCHAR(50)    NOT NULL,
    reorder_status        VARCHAR(50)    NOT NULL COMMENT 'PENDING, ORDERED',
    last_reorder_date     DATETIME       NULL,
    supplier_name         VARCHAR(150)   NULL,
    suggested_reorder_qty INT            NULL,
    order_date            DATETIME       NULL,
    order_reference       VARCHAR(100)   NULL,

    PRIMARY KEY (reorder_id)
) COMMENT 'Reorder decision and supplier linkage';


-- -------------------------------------------------------
-- Component 7 — Stock Reservation
-- Ensures stock is reserved for confirmed orders to
-- prevent over-allocation.
-- -------------------------------------------------------
CREATE TABLE IF NOT EXISTS stock_reservations (
    reservation_id        VARCHAR(50)    NOT NULL,
    product_id            VARCHAR(50)    NOT NULL,
    order_id              VARCHAR(50)    NOT NULL,
    reserved_qty          INT            NOT NULL,
    reservation_status    VARCHAR(50)    NOT NULL COMMENT 'ACTIVE, RELEASED',
    reserved_at           DATETIME       NOT NULL,
    expiry_time           DATETIME       NULL,
    linked_sku            VARCHAR(50)    NULL,
    reserved_quantity     INT            NULL,

    PRIMARY KEY (reservation_id),

    CONSTRAINT chk_reserved_qty CHECK (reserved_qty > 0)
) COMMENT 'Tracks reservation lifecycle for stock';


-- -------------------------------------------------------
-- Component 8 — Stock Freeze Control
-- Used to block stock due to quality issues or regulatory
-- constraints. Integrated with QA workflows.
-- -------------------------------------------------------
CREATE TABLE IF NOT EXISTS stock_freeze (
    freeze_id             VARCHAR(50)    NOT NULL,
    product_id            VARCHAR(50)    NOT NULL,
    batch_id              VARCHAR(50)    NULL,
    freeze_status         BOOLEAN        NOT NULL,
    freeze_reason         VARCHAR(255)   NOT NULL,
    frozen_by             VARCHAR(50)    NOT NULL,
    frozen_at             DATETIME       NOT NULL,
    freeze_status_flag    BOOLEAN        NULL,
    reason_for_freeze     VARCHAR(255)   NULL,
    freeze_applied_by     VARCHAR(50)    NULL,
    freeze_timestamp      DATETIME       NULL,

    PRIMARY KEY (freeze_id)
) COMMENT 'Controls stock freezing for restricted items';


-- -------------------------------------------------------
-- Component 9 — Dead Stock Detection
-- Identifies slow-moving inventory to enable liquidation
-- or clearance strategies.
-- -------------------------------------------------------
CREATE TABLE IF NOT EXISTS dead_stock (
    dead_stock_id         VARCHAR(50)    NOT NULL,
    product_id            VARCHAR(50)    NOT NULL,
    last_movement_date    DATETIME       NOT NULL,
    stagnant_days         INT            NOT NULL,
    stagnant_quantity     INT            NOT NULL,
    action_flag           VARCHAR(50)    NOT NULL COMMENT 'CLEARANCE, HOLD',
    action_status         VARCHAR(50)    NULL,

    PRIMARY KEY (dead_stock_id)
) COMMENT 'Detects and flags non-moving stock';


-- -------------------------------------------------------
-- Component 10 — Stock Valuation
-- Provides financial view of inventory. Used by Accounting
-- and Reporting subsystems.
-- -------------------------------------------------------
CREATE TABLE IF NOT EXISTS stock_valuation (
    valuation_id          VARCHAR(50)    NOT NULL,
    product_id            VARCHAR(50)    NOT NULL,
    unit_cost             DECIMAL(12,2)  NOT NULL,
    total_quantity        INT            NOT NULL,
    total_value           DECIMAL(14,2)  NOT NULL,
    reserved_value        DECIMAL(14,2)  NOT NULL,
    valuation_method      VARCHAR(50)    NOT NULL COMMENT 'FIFO, LIFO, AVG',
    total_inventory_value DECIMAL(14,2)  NULL,
    reserved_stock_value  DECIMAL(14,2)  NULL,
    dead_stock_value      DECIMAL(14,2)  NULL,
    monthly_writeoff_value DECIMAL(14,2) NULL,
    stock_value_by_category TEXT         NULL,
    monthly_valuation_trend TEXT         NULL,

    PRIMARY KEY (valuation_id)
) COMMENT 'Financial valuation of inventory stock';


-- ============================================================
-- SUBSYSTEM — ORDER FULFILLMENT
-- Handles post-order processing: picking, packing, and
-- readiness for shipment.
-- ============================================================

-- -------------------------------------------------------
-- Component — Fulfillment Orders
-- -------------------------------------------------------
CREATE TABLE IF NOT EXISTS fulfillment_orders (
    fulfillment_id        VARCHAR(50)    NOT NULL,
    order_id              VARCHAR(50)    NOT NULL,
    customer_id           VARCHAR(50)    NULL,
    product_id            VARCHAR(50)    NULL,
    quantity              INT            NULL,
    order_status          VARCHAR(50)    NULL,
    order_date            DATETIME       NULL,
    total_amount          DECIMAL(12,2)  NULL,
    customer_name         VARCHAR(150)   NULL,
    shipping_address      TEXT           NULL,
    contact_number        VARCHAR(50)    NULL,
    payment_id            VARCHAR(50)    NULL,
    payment_status        VARCHAR(50)    NULL,
    payment_method        VARCHAR(50)    NULL,
    product_stock_available INT          NULL,
    reserved_quantity     INT            NULL,
    warehouse_id          VARCHAR(50)    NULL,
    storage_location_rack_id VARCHAR(100) NULL,
    picking_status        VARCHAR(50)    NULL,
    packing_status        VARCHAR(50)    NULL,
    shipment_id           VARCHAR(50)    NULL,
    courier_partner       VARCHAR(100)   NULL,
    tracking_id           VARCHAR(100)   NULL,
    shipping_status       VARCHAR(50)    NULL,
    estimated_delivery_date DATE         NULL,
    fulfillment_status    VARCHAR(50)    NOT NULL,
    assigned_staff_id     VARCHAR(50)    NULL,
    reservation_timestamp DATETIME       NULL,
    delivery_instructions TEXT           NULL,
    failed_delivery_attempts INT         NULL,
    cancellation_status   VARCHAR(50)    NULL,
    cancellation_reason   TEXT           NULL,
    cancellation_timestamp DATETIME      NULL,
    assigned_warehouse    VARCHAR(50)    NOT NULL,
    priority_level        VARCHAR(50)    NOT NULL,
    created_at            DATETIME       NOT NULL,

    PRIMARY KEY (fulfillment_id)
) COMMENT 'Tracks order processing stages';


-- -------------------------------------------------------
-- Component — Packing Details
-- -------------------------------------------------------
CREATE TABLE IF NOT EXISTS packing_details (
    packing_id            VARCHAR(50)    NOT NULL,
    fulfillment_id        VARCHAR(50)    NOT NULL,
    package_type          VARCHAR(50)    NOT NULL,
    packed_by             VARCHAR(50)    NOT NULL,
    packed_at             DATETIME       NOT NULL,
    package_weight        DECIMAL(10,2)  NOT NULL,

    PRIMARY KEY (packing_id)
) COMMENT 'Captures packing stage details';


-- ============================================================
-- SUBSYSTEM — MULTI-TIER COMMISSION TRACKING
-- ============================================================

CREATE TABLE IF NOT EXISTS agents (
    agent_id              VARCHAR(50)    NOT NULL,
    agent_name            VARCHAR(150)   NOT NULL,
    level                 INT            NOT NULL,
    parent_agent_id       VARCHAR(50)    NULL,
    downstream_agents     TEXT           NULL,
    status                VARCHAR(50)    NOT NULL,

    PRIMARY KEY (agent_id)
) COMMENT 'Defines hierarchical agent structure';


CREATE TABLE IF NOT EXISTS commission_sales (
    sale_id               VARCHAR(50)    NOT NULL,
    agent_id              VARCHAR(50)    NOT NULL,
    sale_amount           DECIMAL(12,2)  NOT NULL,
    sale_date             DATETIME       NOT NULL,
    status                VARCHAR(50)    NOT NULL,

    PRIMARY KEY (sale_id)
) COMMENT 'Stores sales transactions for commission';


CREATE TABLE IF NOT EXISTS commission_tiers (
    tier_id               VARCHAR(50)    NOT NULL,
    tier_level            INT            NOT NULL,
    min_sales             DECIMAL(12,2)  NOT NULL,
    max_sales             DECIMAL(12,2)  NULL,
    commission_pct        DECIMAL(5,2)   NOT NULL,

    PRIMARY KEY (tier_id)
) COMMENT 'Defines commission tiers';


CREATE TABLE IF NOT EXISTS commission_history (
    commission_id         VARCHAR(50)    NOT NULL,
    agent_id              VARCHAR(50)    NOT NULL,
    period_start          DATE           NOT NULL,
    period_end            DATE           NOT NULL,
    total_sales           DECIMAL(14,2)  NOT NULL,
    tier_breakdown        TEXT           NULL,
    total_commission      DECIMAL(14,2)  NOT NULL,
    calculated_at         DATETIME       NOT NULL,

    PRIMARY KEY (commission_id)
) COMMENT 'Stores computed commissions';


-- ============================================================
-- SUBSYSTEM — ORDERS
-- Core customer order header and line items used by
-- fulfillment, delivery, and downstream finance flows.
-- ============================================================

CREATE TABLE IF NOT EXISTS orders (
    order_id               VARCHAR(50)    NOT NULL,
    customer_id            VARCHAR(50)    NOT NULL,
    order_status           VARCHAR(50)    NOT NULL COMMENT 'PLACED, CONFIRMED, CANCELLED, FULFILLED',
    order_date             DATETIME       NOT NULL,
    total_amount           DECIMAL(12,2)  NOT NULL,
    payment_status         VARCHAR(50)    NOT NULL COMMENT 'PENDING, PAID, FAILED, REFUNDED',
    sales_channel          VARCHAR(50)    NOT NULL COMMENT 'ONLINE, POS, DISTRIBUTOR',

    PRIMARY KEY (order_id),
    CONSTRAINT chk_order_total_amount CHECK (total_amount >= 0)
) COMMENT 'Customer order header used across fulfillment and delivery';


CREATE TABLE IF NOT EXISTS order_items (
    order_item_id          VARCHAR(50)    NOT NULL,
    order_id               VARCHAR(50)    NOT NULL,
    product_id             VARCHAR(50)    NOT NULL,
    ordered_quantity       INT            NOT NULL,
    unit_price             DECIMAL(12,2)  NOT NULL,
    line_total             DECIMAL(12,2)  NOT NULL,

    PRIMARY KEY (order_item_id),
    FOREIGN KEY (order_id) REFERENCES orders(order_id)
        ON DELETE CASCADE,
    CONSTRAINT chk_order_item_qty CHECK (ordered_quantity > 0),
    CONSTRAINT chk_order_item_unit_price CHECK (unit_price >= 0),
    CONSTRAINT chk_order_item_line_total CHECK (line_total >= 0)
) COMMENT 'Line items that belong to customer orders';


-- ============================================================
-- SUBSYSTEM — DELIVERY ORDERS
-- ============================================================

CREATE TABLE IF NOT EXISTS delivery_orders (
    delivery_id           VARCHAR(50)    NOT NULL,
    order_id              VARCHAR(50)    NOT NULL,
    customer_id           VARCHAR(50)    NOT NULL,
    delivery_address      TEXT           NOT NULL,
    delivery_status       VARCHAR(50)    NOT NULL,
    delivery_date         DATETIME       NULL,
    delivery_type         VARCHAR(50)    NOT NULL,
    delivery_cost         DECIMAL(10,2)  NOT NULL,
    assigned_agent        VARCHAR(50)    NULL,
    warehouse_id          VARCHAR(50)    NOT NULL,
    created_at            DATETIME       NOT NULL,
    updated_at            DATETIME       NULL,

    PRIMARY KEY (delivery_id)
) COMMENT 'Handles final delivery execution';


-- ============================================================
-- SUBSYSTEM — REAL-TIME DELIVERY MONITORING
-- Captures route plans, assignments, and delivery tracking
-- events that go beyond the base delivery_orders table.
-- ============================================================

CREATE TABLE IF NOT EXISTS delivery_tracking_routes (
    route_plan_id                    VARCHAR(50)    NOT NULL,
    delivery_id                      VARCHAR(50)    NOT NULL,
    order_id                         VARCHAR(50)    NULL,
    customer_id                      VARCHAR(50)    NULL,
    pickup_address                   TEXT           NULL,
    dropoff_address                  TEXT           NULL,
    item_description                 TEXT           NULL,
    item_weight_kg                   DECIMAL(10,2)  NULL,
    committed_delivery_window_start  DATETIME       NULL,
    committed_delivery_window_end    DATETIME       NULL,
    order_created_at                 DATETIME       NULL,
    dispatched_at                    DATETIME       NULL,
    warehouse_id                     VARCHAR(50)    NULL,
    warehouse_latitude               DECIMAL(10,6)  NULL,
    warehouse_longitude              DECIMAL(10,6)  NULL,
    rider_id                         VARCHAR(50)    NULL,
    assigned_at                      DATETIME       NULL,
    customer_name                    VARCHAR(100)   NULL,
    customer_email                   VARCHAR(150)   NULL,
    customer_phone                   VARCHAR(50)    NULL,
    preferred_notification_channel   VARCHAR(50)    NULL,
    vehicle_id                       VARCHAR(50)    NULL,
    plate_number                     VARCHAR(30)    NULL,
    vehicle_type                     VARCHAR(50)    NULL,
    max_payload_kg                   DECIMAL(10,2)  NULL,
    temperature_min_c                DECIMAL(10,2)  NULL,
    temperature_max_c                DECIMAL(10,2)  NULL,
    is_hazardous                     BOOLEAN        NULL,
    carrier_id                       VARCHAR(50)    NULL,
    tracking_api_url                 VARCHAR(255)   NULL,
    waypoints                        TEXT           NULL,
    planned_departure                DATETIME       NULL,
    planned_arrival                  DATETIME       NULL,
    current_eta                      DATETIME       NULL,
    route_status                     VARCHAR(50)    NOT NULL DEFAULT 'PLANNED',

    PRIMARY KEY (route_plan_id),
    FOREIGN KEY (delivery_id) REFERENCES delivery_orders(delivery_id)
        ON DELETE CASCADE
) COMMENT 'Route-plan level tracking for real-time delivery monitoring';


CREATE TABLE IF NOT EXISTS delivery_tracking_waypoints (
    waypoint_id                      VARCHAR(50)    NOT NULL,
    route_plan_id                    VARCHAR(50)    NOT NULL,
    waypoint_sequence                INT            NOT NULL,
    waypoint_location                VARCHAR(255)   NOT NULL,

    PRIMARY KEY (waypoint_id),
    FOREIGN KEY (route_plan_id) REFERENCES delivery_tracking_routes(route_plan_id)
        ON DELETE CASCADE,
    CONSTRAINT chk_waypoint_sequence CHECK (waypoint_sequence > 0)
) COMMENT 'Waypoint list for monitored delivery routes';


CREATE TABLE IF NOT EXISTS delivery_tracking_events (
    tracking_event_id                VARCHAR(50)    NOT NULL,
    delivery_id                      VARCHAR(50)    NOT NULL,
    rider_id                         VARCHAR(50)    NULL,
    vehicle_id                       VARCHAR(50)    NULL,
    timeline_stage                   VARCHAR(50)    NOT NULL,
    gps_coordinates                  VARCHAR(100)   NULL,
    event_timestamp                  DATETIME       NOT NULL,
    alert_message                    VARCHAR(255)   NULL,
    requires_rerouting               BOOLEAN        NOT NULL DEFAULT FALSE,

    PRIMARY KEY (tracking_event_id),
    FOREIGN KEY (delivery_id) REFERENCES delivery_orders(delivery_id)
        ON DELETE CASCADE
) COMMENT 'Live delivery milestones and alert events';


-- ============================================================
-- SUBSYSTEM — TRANSPORT & LOGISTICS / ADVANCED ROUTING
-- Models shipment planning, carrier allocation, and route
-- optimisation data not covered by delivery_orders alone.
-- ============================================================

CREATE TABLE IF NOT EXISTS shipments (
    shipment_id                      VARCHAR(50)    NOT NULL,
    order_id                         VARCHAR(50)    NOT NULL,
    origin_address                   TEXT           NOT NULL,
    destination_address              TEXT           NOT NULL,
    package_weight                   DECIMAL(10,2)  NOT NULL,
    is_drop_ship                     BOOLEAN        NOT NULL DEFAULT FALSE,
    shipping_priority                VARCHAR(50)    NOT NULL,
    shipment_status                  VARCHAR(50)    NOT NULL,
    supplier_id                      VARCHAR(50)    NULL,
    inventory_level                  INT            NULL,
    route_id                         VARCHAR(50)    NULL,
    carrier_id                       VARCHAR(50)    NULL,
    tracking_id                      VARCHAR(50)    NULL,
    min_cost_constraint              BOOLEAN        NOT NULL DEFAULT FALSE,
    min_time_constraint              BOOLEAN        NOT NULL DEFAULT FALSE,
    avoid_tolls_constraint           BOOLEAN        NOT NULL DEFAULT FALSE,
    calculated_cost                  DECIMAL(12,2)  NULL,
    created_at                       DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (shipment_id),
    FOREIGN KEY (order_id) REFERENCES orders(order_id),
    CONSTRAINT chk_shipment_weight CHECK (package_weight > 0),
    CONSTRAINT chk_inventory_level_nonneg CHECK (inventory_level IS NULL OR inventory_level >= 0)
) COMMENT 'Shipment planning for transport and logistics decisions';


CREATE TABLE IF NOT EXISTS logistics_routes (
    route_id                         VARCHAR(50)    NOT NULL,
    shipment_id                      VARCHAR(50)    NOT NULL,
    gps_coordinates                  VARCHAR(100)   NULL,
    current_eta                      DATETIME       NULL,
    timeline_stage                   VARCHAR(50)    NULL,
    route_status                     VARCHAR(50)    NOT NULL DEFAULT 'PLANNED',
    requires_rerouting               BOOLEAN        NOT NULL DEFAULT FALSE,

    PRIMARY KEY (route_id),
    FOREIGN KEY (shipment_id) REFERENCES shipments(shipment_id)
        ON DELETE CASCADE
) COMMENT 'Calculated logistics routes for shipments';


CREATE TABLE IF NOT EXISTS shipment_alerts (
    alert_id                         VARCHAR(50)    NOT NULL,
    shipment_id                      VARCHAR(50)    NOT NULL,
    alert_message                    VARCHAR(255)   NOT NULL,
    alert_severity                   VARCHAR(20)    NOT NULL,
    created_at                       DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (alert_id),
    FOREIGN KEY (shipment_id) REFERENCES shipments(shipment_id)
        ON DELETE CASCADE
) COMMENT 'Alerts produced by transport and logistics flows';


-- ============================================================
-- SUBSYSTEM — PACKAGING, REPAIRS & RECEIPT MANAGEMENT
-- Tracks packaging jobs, repair requests, and receipt-side
-- acknowledgements around order handling.
-- ============================================================

CREATE TABLE IF NOT EXISTS packaging_jobs (
    package_id                       VARCHAR(50)    NOT NULL,
    order_id                         VARCHAR(50)    NOT NULL,
    quantity                         INT            NOT NULL,
    total_amount                     DECIMAL(12,2)  NOT NULL,
    discounts                        DECIMAL(12,2)  NOT NULL DEFAULT 0.00,
    packaging_status                 VARCHAR(50)    NOT NULL,
    packed_by                        VARCHAR(50)    NULL,
    created_at                       DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (package_id),
    FOREIGN KEY (order_id) REFERENCES orders(order_id),
    CONSTRAINT chk_packaging_qty CHECK (quantity > 0),
    CONSTRAINT chk_packaging_total CHECK (total_amount >= 0),
    CONSTRAINT chk_packaging_discount CHECK (discounts >= 0)
) COMMENT 'Packaging jobs linked to order handling';


CREATE TABLE IF NOT EXISTS repair_requests (
    request_id                       VARCHAR(50)    NOT NULL,
    order_id                         VARCHAR(50)    NOT NULL,
    product_id                       VARCHAR(50)    NOT NULL,
    defect_details                   TEXT           NOT NULL,
    request_status                   VARCHAR(50)    NOT NULL,
    requested_at                     DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (request_id),
    FOREIGN KEY (order_id) REFERENCES orders(order_id)
) COMMENT 'Repair requests raised for packaged or delivered items';


CREATE TABLE IF NOT EXISTS receipt_records (
    receipt_record_id                VARCHAR(50)    NOT NULL,
    order_id                         VARCHAR(50)    NOT NULL,
    package_id                       VARCHAR(50)    NULL,
    received_amount                  DECIMAL(12,2)  NOT NULL,
    receipt_status                   VARCHAR(50)    NOT NULL,
    recorded_at                      DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (receipt_record_id),
    FOREIGN KEY (order_id) REFERENCES orders(order_id),
    FOREIGN KEY (package_id) REFERENCES packaging_jobs(package_id),
    CONSTRAINT chk_receipt_amount CHECK (received_amount >= 0)
) COMMENT 'Receipt and acknowledgement records for packaged orders';


-- ============================================================
-- SUBSYSTEM — PRODUCT ADVANCEMENT & RETURNS MANAGEMENT
-- Goes beyond warehouse_returns by storing customer-facing
-- return requests, defect details, and trend metrics.
-- ============================================================

CREATE TABLE IF NOT EXISTS product_returns (
    return_request_id                VARCHAR(50)    NOT NULL,
    order_id                         VARCHAR(50)    NOT NULL,
    customer_id                      VARCHAR(50)    NOT NULL,
    product_details                  TEXT           NOT NULL,
    defect_details                   TEXT           NULL,
    customer_feedback                TEXT           NULL,
    transport_details                TEXT           NULL,
    warranty_valid_until             DATETIME       NULL,
    return_status                    VARCHAR(50)    NOT NULL,
    created_at                       DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (return_request_id),
    FOREIGN KEY (order_id) REFERENCES orders(order_id)
) COMMENT 'Customer-facing product return and advancement records';


CREATE TABLE IF NOT EXISTS return_growth_statistics (
    growth_stat_id                   VARCHAR(50)    NOT NULL,
    return_request_id                VARCHAR(50)    NOT NULL,
    metric_period                    VARCHAR(30)    NOT NULL,
    return_rate                      DECIMAL(8,2)   NULL,
    resolution_rate                  DECIMAL(8,2)   NULL,
    recorded_at                      DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (growth_stat_id),
    FOREIGN KEY (return_request_id) REFERENCES product_returns(return_request_id)
        ON DELETE CASCADE
) COMMENT 'Trend and growth metrics for returns management';


-- ============================================================
-- SUBSYSTEM — DEMAND FORECASTING (SUPPORT TABLES)
-- The main forecast output table already exists in schema.sql.
-- These tables capture supporting inputs and evaluation data.
-- ============================================================

CREATE TABLE IF NOT EXISTS sales_records (
    sale_id                          VARCHAR(50)    NOT NULL,
    product_id                       VARCHAR(50)    NOT NULL,
    store_id                         VARCHAR(50)    NOT NULL,
    sale_date                        DATE           NOT NULL,
    quantity_sold                    INT            NOT NULL,
    unit_price                       DECIMAL(12,2)  NOT NULL,
    revenue                          DECIMAL(14,2)  NOT NULL,
    region                           VARCHAR(50)    NULL,

    PRIMARY KEY (sale_id),
    CONSTRAINT chk_sales_qty CHECK (quantity_sold >= 0),
    CONSTRAINT chk_sales_unit_price CHECK (unit_price >= 0),
    CONSTRAINT chk_sales_revenue CHECK (revenue >= 0)
) COMMENT 'Historical sales inputs for forecasting models';


CREATE TABLE IF NOT EXISTS holiday_calendar (
    holiday_id                       VARCHAR(50)    NOT NULL,
    holiday_date                     DATE           NOT NULL,
    holiday_name                     VARCHAR(100)   NOT NULL,
    holiday_type                     VARCHAR(50)    NOT NULL,
    region_applicable                VARCHAR(50)    NULL,

    PRIMARY KEY (holiday_id)
) COMMENT 'Holiday and calendar features for demand forecasting';


CREATE TABLE IF NOT EXISTS promotional_calendar (
    promo_calendar_id                VARCHAR(50)    NOT NULL,
    promo_id                         VARCHAR(50)    NULL,
    promo_name                       VARCHAR(100)   NOT NULL,
    promo_start_date                 DATE           NOT NULL,
    promo_end_date                   DATE           NOT NULL,
    discount_percentage              DECIMAL(5,2)   NULL,
    promo_type                       VARCHAR(50)    NULL,
    applicable_products              TEXT           NULL,

    PRIMARY KEY (promo_calendar_id),
    CONSTRAINT chk_promo_calendar_range CHECK (promo_end_date >= promo_start_date)
) COMMENT 'Promotional events used as demand-forecasting features';

CREATE TABLE IF NOT EXISTS product_metadata (
    product_id                       VARCHAR(50)    NOT NULL,
    product_name                     VARCHAR(150)   NOT NULL,
    category                         VARCHAR(100)   NOT NULL,
    sub_category                     VARCHAR(100)   NULL,
    seasonality_type                 VARCHAR(100)   NULL,

    PRIMARY KEY (product_id)
) COMMENT 'Product metadata used by demand forecasting';


CREATE TABLE IF NOT EXISTS product_lifecycle_stages (
    lifecycle_id                     VARCHAR(50)    NOT NULL,
    product_id                       VARCHAR(50)    NOT NULL,
    current_stage                    VARCHAR(50)    NOT NULL,
    stage_start_date                 DATE           NOT NULL,
    previous_stage                   VARCHAR(50)    NULL,
    transition_date                  DATE           NULL,

    PRIMARY KEY (lifecycle_id)
) COMMENT 'Lifecycle-stage metadata for products in forecasting';

CREATE TABLE IF NOT EXISTS inventory_supply (
    product_id                       VARCHAR(50)    NOT NULL,
    current_stock                    INT            NULL,
    reorder_point                    INT            NULL,
    lead_time_days                   INT            NULL,
    supplier_id                      VARCHAR(50)    NULL,

    PRIMARY KEY (product_id)
) COMMENT 'Inventory and supply features used by forecasting';

-- -------------------------------------------------------
-- Component — Forecast Output (Core Table)
-- Stores generated demand forecasts used by inventory,
-- procurement, and reporting subsystems.
-- -------------------------------------------------------
CREATE TABLE IF NOT EXISTS demand_forecasts (
    forecast_id              VARCHAR(50)    NOT NULL,
    product_id               VARCHAR(50)    NOT NULL COMMENT 'Ref to Inventory subsystem',
    forecast_period          VARCHAR(30)    NOT NULL COMMENT 'e.g. WEEKLY, MONTHLY',
    forecast_date            DATE           NULL COMMENT 'Date for which prediction applies',
    predicted_demand         INT            NOT NULL COMMENT 'Model output for expected demand',
    confidence_score         DECIMAL(5,2)   NULL COMMENT 'Prediction confidence (0–100)',
    reorder_signal           BOOLEAN        NOT NULL DEFAULT FALSE COMMENT 'Triggers reorder if TRUE',
    suggested_order_qty      INT            NULL COMMENT 'Recommended replenishment quantity',
    lifecycle_stage          VARCHAR(50)    NULL COMMENT 'Derived from lifecycle metadata',
    algorithm_used           VARCHAR(100)   NULL COMMENT 'Model used (ARIMA, LSTM, etc.)',
    generated_at             DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    source_event_reference   VARCHAR(100),
    PRIMARY KEY (forecast_id),

    CONSTRAINT chk_predicted_qty_positive CHECK (predicted_demand >= 0),
    CONSTRAINT chk_confidence_range CHECK (confidence_score IS NULL 
                                          OR confidence_score BETWEEN 0 AND 100),
    CONSTRAINT chk_suggested_qty CHECK (suggested_order_qty IS NULL 
                                       OR suggested_order_qty >= 0)
) COMMENT 'Core demand forecasting output table used across subsystems';

CREATE TABLE IF NOT EXISTS forecast_performance_metrics (
    eval_id                          VARCHAR(50)    NOT NULL,
    forecast_id                      VARCHAR(50)    NOT NULL,
    forecast_date                    DATE           NOT NULL,
    predicted_qty                    INT            NOT NULL,
    actual_qty                       INT            NULL,
    mape                             DECIMAL(8,2)   NULL,
    rmse                             DECIMAL(12,4)  NULL,
    model_used                       VARCHAR(100)   NULL,

    PRIMARY KEY (eval_id),
    FOREIGN KEY (forecast_id) REFERENCES demand_forecasts(forecast_id)
        ON DELETE CASCADE,
    CONSTRAINT chk_predicted_qty_nonneg CHECK (predicted_qty >= 0),
    CONSTRAINT chk_actual_qty_nonneg CHECK (actual_qty IS NULL OR actual_qty >= 0)
) COMMENT 'Evaluation metrics for generated demand forecasts';

CREATE TABLE IF NOT EXISTS barcode_rfid_events (
    event_id VARCHAR(50) NOT NULL,
    product_id VARCHAR(50) NOT NULL,
    rfid_tag VARCHAR(100) NULL,
    product_name VARCHAR(150) NULL,
    category VARCHAR(100) NULL,
    description TEXT NULL,
    transaction_id VARCHAR(50) NULL,
    warehouse_id VARCHAR(50) NULL,
    event_timestamp DATETIME NOT NULL,
    status VARCHAR(50) NOT NULL,
    source VARCHAR(100) NOT NULL,
    PRIMARY KEY (event_id)
);

CREATE TABLE IF NOT EXISTS subsystem_exceptions (
    exception_id VARCHAR(50) NOT NULL,
    exception_name VARCHAR(150) NULL,
    subsystem_name VARCHAR(100) NOT NULL,
    severity VARCHAR(20) NOT NULL,
    timestamp_utc DATETIME NOT NULL,
    duration_ms BIGINT NULL,
    exception_message VARCHAR(500) NOT NULL,
    error_code BIGINT NULL,
    stack_trace TEXT NULL,
    inner_exception TEXT NULL,
    user_account VARCHAR(150) NULL,
    handling_plan TEXT NULL,
    retry_count TINYINT UNSIGNED NULL,
    status VARCHAR(30) NOT NULL,
    resolved_at DATETIME NULL,
    PRIMARY KEY (exception_id)
);

CREATE OR REPLACE VIEW vw_reporting_dashboard AS
    SELECT
        o.order_id                          AS order_id,
        o.order_date                        AS order_date,
        do.delivery_date                    AS delivery_date,
        o.order_status                      AS order_status,
        NULL                                AS fulfillment_time,
        oi.ordered_quantity                 AS order_quantity,
        oi.product_id                       AS product_id,
        p.product_name                      AS product_name,
        sl.current_stock_qty                AS current_stock_level,
        sl.reorder_threshold                AS reorder_level,
        CASE
            WHEN sl.current_stock_qty IS NOT NULL AND sl.current_stock_qty <= 0 THEN TRUE
            ELSE FALSE
        END                                 AS stock_out_flag,
        NULL                                AS inventory_turnover_rate,
        p.supplier_id                       AS supplier_id,
        NULL                                AS supplier_name,
        NULL                                AS supplier_performance_score,
        NULL                                AS lead_time,
        NULL                                AS on_time_supply_rate,
        do.delivery_id                      AS shipment_id,
        do.created_at                       AS dispatch_date,
        do.delivery_status                  AS delivery_status,
        NULL                                AS transit_time,
        CASE
            WHEN do.delivery_status IN ('DELAYED', 'FAILED') THEN TRUE
            ELSE FALSE
        END                                 AS delay_flag,
        do.delivery_address                 AS delivery_location,
        do.warehouse_id                     AS warehouse_id,
        NULL                                AS storage_capacity,
        NULL                                AS utilization_rate,
        NULL                                AS inbound_quantity,
        NULL                                AS outbound_quantity,
        pl.base_price                       AS product_price,
        NULL                                AS discount_applied,
        df.predicted_demand                 AS sales_volume,
        cs.sale_amount                      AS revenue,
        df.predicted_demand                 AS demand_forecast,
        df.forecast_period                  AS forecast_period,
        df.suggested_order_qty              AS predicted_inventory_needs,
        se.exception_id                     AS exception_id,
        se.exception_name                   AS exception_type,
        se.severity                         AS severity_level,
        se.timestamp_utc                    AS timestamp
    FROM orders o
    LEFT JOIN order_items oi
        ON o.order_id = oi.order_id
    LEFT JOIN delivery_orders do
        ON o.order_id = do.order_id
    LEFT JOIN stock_levels sl
        ON oi.product_id = sl.product_id
    LEFT JOIN products p
        ON oi.product_id = p.product_id
    LEFT JOIN price_list pl
        ON pl.sku_id = p.sku AND pl.status = 'ACTIVE'
    LEFT JOIN demand_forecasts df
        ON df.product_id = oi.product_id
    LEFT JOIN subsystem_exceptions se
        ON se.subsystem_name = 'REPORTING'
    LEFT JOIN commission_sales cs
        ON cs.sale_id = o.order_id;

SET FOREIGN_KEY_CHECKS = 1;

