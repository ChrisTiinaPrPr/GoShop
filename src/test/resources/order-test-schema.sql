CREATE TABLE user_address (
    id BIGINT NOT NULL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    receiver VARCHAR(64) NOT NULL,
    phone VARCHAR(32) NOT NULL,
    province VARCHAR(64) NOT NULL,
    city VARCHAR(64) NOT NULL,
    district VARCHAR(64) NOT NULL,
    detail VARCHAR(255) NOT NULL,
    is_default TINYINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
) ENGINE=InnoDB;

CREATE TABLE product_spu (
    id BIGINT NOT NULL PRIMARY KEY,
    merchant_id BIGINT NOT NULL,
    category_id BIGINT NULL,
    title VARCHAR(255) NOT NULL,
    description TEXT NULL,
    main_image VARCHAR(512) NULL,
    status TINYINT NOT NULL,
    sales_count BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    main_image_object_key VARCHAR(512) NULL
) ENGINE=InnoDB;

CREATE TABLE product_sku (
    id BIGINT NOT NULL PRIMARY KEY,
    spu_id BIGINT NOT NULL,
    specs_json JSON NULL,
    price_cent BIGINT NOT NULL,
    stock INT NOT NULL,
    locked_stock INT NOT NULL DEFAULT 0,
    version INT NOT NULL DEFAULT 0,
    status TINYINT NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    KEY idx_product_sku_spu (spu_id)
) ENGINE=InnoDB;

CREATE TABLE mall_order (
    id BIGINT NOT NULL PRIMARY KEY,
    order_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    user_id BIGINT NOT NULL,
    merchant_id BIGINT NOT NULL,
    status VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    total_amount_cent BIGINT NOT NULL,
    pay_amount_cent BIGINT NOT NULL,
    address_snapshot_json JSON NOT NULL,
    expire_at DATETIME(6) NULL,
    paid_at DATETIME(6) NULL,
    shipping_company VARCHAR(128) NULL,
    tracking_no VARCHAR(128) NULL,
    shipped_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_mall_order_no (order_no)
) ENGINE=InnoDB;

CREATE TABLE order_item (
    id BIGINT NOT NULL PRIMARY KEY,
    order_id BIGINT NOT NULL,
    spu_id BIGINT NOT NULL,
    sku_id BIGINT NOT NULL,
    product_title VARCHAR(255) NOT NULL,
    product_image VARCHAR(512) NULL,
    specs_json JSON NULL,
    unit_price_cent BIGINT NOT NULL,
    quantity INT NOT NULL,
    subtotal_cent BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    KEY idx_order_item_order (order_id)
) ENGINE=InnoDB;
