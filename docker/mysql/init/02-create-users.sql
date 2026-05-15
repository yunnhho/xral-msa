-- 서비스별 DB 계정 생성 + 권한 부여 (각 서비스가 자기 스키마에만 접근)
CREATE USER IF NOT EXISTS 'xrail_auth'@'%' IDENTIFIED BY '${AUTH_DB_PASSWORD}';
GRANT ALL PRIVILEGES ON xrail_auth.* TO 'xrail_auth'@'%';

CREATE USER IF NOT EXISTS 'xrail_train'@'%' IDENTIFIED BY '${TRAIN_DB_PASSWORD}';
GRANT ALL PRIVILEGES ON xrail_train.* TO 'xrail_train'@'%';

CREATE USER IF NOT EXISTS 'xrail_payment'@'%' IDENTIFIED BY '${PAYMENT_DB_PASSWORD}';
GRANT ALL PRIVILEGES ON xrail_payment.* TO 'xrail_payment'@'%';

CREATE USER IF NOT EXISTS 'xrail_notify'@'%' IDENTIFIED BY '${NOTIFY_DB_PASSWORD}';
GRANT ALL PRIVILEGES ON xrail_notify.* TO 'xrail_notify'@'%';

FLUSH PRIVILEGES;
