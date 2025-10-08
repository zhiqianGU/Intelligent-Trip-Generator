-- 建议先设定字符集
use thesis_project;
SET NAMES utf8mb4;
SET SESSION sql_mode = 'STRICT_ALL_TABLES';

-- 1) 用户主体
CREATE TABLE app_user (
                          id BIGINT PRIMARY KEY AUTO_INCREMENT,
                          display_name VARCHAR(64) NOT NULL,
                          created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                          updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 2) 用户密码（与身份标识分离，便于多标识登录）
CREATE TABLE user_credential (
                                 user_id BIGINT PRIMARY KEY,
                                 password VARCHAR(100) NOT NULL,       -- bcrypt 长度60，预留到100
                                 password_algo VARCHAR(16) NOT NULL DEFAULT 'bcrypt',
                                 updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                                 CONSTRAINT fk_cred_user FOREIGN KEY (user_id) REFERENCES app_user(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 3) 用户登录标识（邮箱/手机可多条；一对多；全局唯一）
CREATE TABLE user_identifier (
                                 id BIGINT PRIMARY KEY AUTO_INCREMENT,
                                 user_id BIGINT NOT NULL,
                                 id_type ENUM('EMAIL','PHONE') NOT NULL,
                                 identifier VARCHAR(191) NOT NULL,          -- 邮箱/手机号原文
                                 is_verified BOOLEAN NOT NULL DEFAULT FALSE,
                                 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                 UNIQUE KEY uk_identifier_type (id_type, identifier),
                                 KEY idx_id_user (user_id),
                                 CONSTRAINT fk_id_user FOREIGN KEY (user_id) REFERENCES app_user(id) ON DELETE CASCADE,
                                 CHECK ( (id_type <> 'EMAIL') OR (identifier LIKE '%@%') ),
                                 CHECK ( (id_type <> 'PHONE') OR (identifier REGEXP '^[0-9+\\-]{6,20}$') )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 4) 地点（Place）——含空间索引；latitude/longitude + 生成列 POINT
CREATE TABLE place (
                       id BIGINT PRIMARY KEY AUTO_INCREMENT,
                       name VARCHAR(191) NOT NULL,
                       address VARCHAR(255),
                       city VARCHAR(100),
                       district VARCHAR(100),
                       country VARCHAR(100),
                       latitude  DECIMAL(9,6) NOT NULL,
                       longitude DECIMAL(9,6) NOT NULL,
    -- 注意：这里不写 SRID 4326
                       location POINT
                           GENERATED ALWAYS AS (
                               ST_GeomFromText(CONCAT('POINT(', longitude, ' ', latitude, ')'), 4326)
                               ) STORED NOT NULL,
                       source ENUM('OSM','USER','AMAP','OTHER') DEFAULT 'OSM',
                       external_ref VARCHAR(255),
                       created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                       UNIQUE KEY uk_src_ext (source, external_ref),
                       SPATIAL INDEX sp_idx_place_location (location),
                       KEY idx_place_city (city)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 5) 旅行计划（Plan）
CREATE TABLE trip_plan (
                           id BIGINT PRIMARY KEY AUTO_INCREMENT,
                           user_id BIGINT NOT NULL,
                           title VARCHAR(128) DEFAULT NULL,        -- 可选：计划名称
                           city VARCHAR(100) NOT NULL,
                           days INT NOT NULL CHECK (days BETWEEN 1 AND 30),
                           budget_cents INT NULL CHECK (budget_cents IS NULL OR budget_cents >= 0),
                           party_adults TINYINT UNSIGNED NOT NULL DEFAULT 1,
                           party_kids   TINYINT UNSIGNED NOT NULL DEFAULT 0,
                           pace ENUM('relaxed','normal','rush') NOT NULL DEFAULT 'normal',
                           created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                           CONSTRAINT fk_plan_user FOREIGN KEY (user_id) REFERENCES app_user(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 5.1) 计划的风格标签（多选，3NF 用码表 + 关联）
CREATE TABLE style_tag (
                           id TINYINT UNSIGNED PRIMARY KEY,
                           code VARCHAR(32) NOT NULL UNIQUE,     -- 如 'food','museum','nature'
                           name_zh VARCHAR(32) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE trip_plan_style (
                                 plan_id BIGINT NOT NULL,
                                 style_id TINYINT UNSIGNED NOT NULL,
                                 PRIMARY KEY (plan_id, style_id),
                                 CONSTRAINT fk_ps_plan  FOREIGN KEY (plan_id)  REFERENCES trip_plan(id) ON DELETE CASCADE,
                                 CONSTRAINT fk_ps_style FOREIGN KEY (style_id) REFERENCES style_tag(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 6) 行程的“天”（Day）——酒店引用 place；(plan_id, day_index) 唯一
CREATE TABLE trip_day (
                          id BIGINT PRIMARY KEY AUTO_INCREMENT,
                          plan_id BIGINT NOT NULL,
                          day_index INT NOT NULL,
                          hotel_place_id BIGINT NULL,
                          note VARCHAR(255),
                          UNIQUE KEY uk_plan_day (plan_id, day_index),
                          KEY idx_day_plan (plan_id),
                          CONSTRAINT fk_day_plan   FOREIGN KEY (plan_id)        REFERENCES trip_plan(id) ON DELETE CASCADE,
                          CONSTRAINT fk_day_hotel  FOREIGN KEY (hotel_place_id) REFERENCES place(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 7) Day 的经停点（Stop）——按 seq 排序，一天 n 个
CREATE TABLE trip_day_stop (
                               id BIGINT PRIMARY KEY AUTO_INCREMENT,
                               day_id BIGINT NOT NULL,
                               seq INT NOT NULL,                         -- 1..n
                               place_id BIGINT NOT NULL,
                               dwell_minutes INT DEFAULT 60,
                               note VARCHAR(255),
                               UNIQUE KEY uk_day_seq (day_id, seq),
                               KEY idx_stop_day (day_id),
                               KEY idx_stop_place (place_id),
                               CONSTRAINT fk_stop_day   FOREIGN KEY (day_id)   REFERENCES trip_day(id) ON DELETE CASCADE,
                               CONSTRAINT fk_stop_place FOREIGN KEY (place_id) REFERENCES place(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 8) 独立路线段（Segment）——严格挂到某一天；from/to 对应 Stop（首段 from_stop_id 可为空表示“酒店→第1站”）
CREATE TABLE route_segment (
                               id BIGINT PRIMARY KEY AUTO_INCREMENT,
                               day_id BIGINT NOT NULL,
                               from_stop_id BIGINT NULL,
                               to_stop_id   BIGINT NOT NULL,
                               mode ENUM('walk','drive','bicycle','transit') NOT NULL,
                               distance_m INT NOT NULL,
                               duration_s INT NOT NULL,
                               path LINESTRING NULL,            -- 去掉 SRID 4326
                               polyline MEDIUMTEXT NULL,
                               KEY idx_seg_day (day_id),
                               CONSTRAINT fk_seg_day  FOREIGN KEY (day_id)      REFERENCES trip_day(id) ON DELETE CASCADE,
                               CONSTRAINT fk_seg_from FOREIGN KEY (from_stop_id) REFERENCES trip_day_stop(id) ON DELETE SET NULL,
                               CONSTRAINT fk_seg_to   FOREIGN KEY (to_stop_id)   REFERENCES trip_day_stop(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 9) 路线步骤（Step）——点击某段时展示详细步骤
CREATE TABLE route_step (
                            id BIGINT PRIMARY KEY AUTO_INCREMENT,
                            segment_id BIGINT NOT NULL,
                            seq INT NOT NULL,
                            instruction VARCHAR(255) NOT NULL,
                            distance_m INT NOT NULL,
                            duration_s INT NOT NULL,
                            subpath LINESTRING NULL,                  -- 去掉 SRID 关键字
                            UNIQUE KEY uk_step_seg_seq (segment_id, seq),
                            KEY idx_step_seg (segment_id),
                            CONSTRAINT fk_step_seg FOREIGN KEY (segment_id)
                                REFERENCES route_segment(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 10) 用户收藏地点（可选）
CREATE TABLE user_favorite_place (
                                     user_id BIGINT NOT NULL,
                                     place_id BIGINT NOT NULL,
                                     created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                     PRIMARY KEY (user_id, place_id),
                                     CONSTRAINT fk_fav_user  FOREIGN KEY (user_id)  REFERENCES app_user(id) ON DELETE CASCADE,
                                     CONSTRAINT fk_fav_place FOREIGN KEY (place_id) REFERENCES place(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
