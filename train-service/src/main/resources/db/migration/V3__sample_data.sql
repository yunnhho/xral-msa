-- ============================================================
-- xrail_train — V3: 테스트용 샘플 데이터
-- ============================================================

INSERT INTO stations (name, created_at, updated_at) VALUES
('서울', NOW(6), NOW(6)),
('대전', NOW(6), NOW(6)),
('동대구', NOW(6), NOW(6)),
('부산', NOW(6), NOW(6));

INSERT INTO routes (name, created_at, updated_at) VALUES ('경부선', NOW(6), NOW(6));

INSERT INTO route_stations (route_id, station_id, station_sequence, cumulative_distance, created_at, updated_at) VALUES
(1, 1, 0,   0.0, NOW(6), NOW(6)),   -- 서울
(1, 2, 1, 167.0, NOW(6), NOW(6)),   -- 대전
(1, 3, 2, 294.0, NOW(6), NOW(6)),   -- 동대구
(1, 4, 3, 416.5, NOW(6), NOW(6));   -- 부산

INSERT INTO trains (train_number, train_type, created_at, updated_at) VALUES ('KTX-001', 'KTX', NOW(6), NOW(6));

INSERT INTO carriages (train_id, carriage_number, seat_count) VALUES
(1, 1, 40),
(1, 2, 40);

-- Carriage 1: seats 1~40 (1A-10D pattern)
INSERT INTO seats (carriage_id, seat_number) VALUES
(1,'1A'),(1,'1B'),(1,'1C'),(1,'1D'),
(1,'2A'),(1,'2B'),(1,'2C'),(1,'2D'),
(1,'3A'),(1,'3B'),(1,'3C'),(1,'3D'),
(1,'4A'),(1,'4B'),(1,'4C'),(1,'4D'),
(1,'5A'),(1,'5B'),(1,'5C'),(1,'5D'),
(1,'6A'),(1,'6B'),(1,'6C'),(1,'6D'),
(1,'7A'),(1,'7B'),(1,'7C'),(1,'7D'),
(1,'8A'),(1,'8B'),(1,'8C'),(1,'8D'),
(1,'9A'),(1,'9B'),(1,'9C'),(1,'9D'),
(1,'10A'),(1,'10B'),(1,'10C'),(1,'10D');

-- Carriage 2: seats 1~40
INSERT INTO seats (carriage_id, seat_number) VALUES
(2,'1A'),(2,'1B'),(2,'1C'),(2,'1D'),
(2,'2A'),(2,'2B'),(2,'2C'),(2,'2D'),
(2,'3A'),(2,'3B'),(2,'3C'),(2,'3D'),
(2,'4A'),(2,'4B'),(2,'4C'),(2,'4D'),
(2,'5A'),(2,'5B'),(2,'5C'),(2,'5D'),
(2,'6A'),(2,'6B'),(2,'6C'),(2,'6D'),
(2,'7A'),(2,'7B'),(2,'7C'),(2,'7D'),
(2,'8A'),(2,'8B'),(2,'8C'),(2,'8D'),
(2,'9A'),(2,'9B'),(2,'9C'),(2,'9D'),
(2,'10A'),(2,'10B'),(2,'10C'),(2,'10D');

-- Schedule: tomorrow 08:00 → 10:30
INSERT INTO schedules (route_id, train_id, departure_date, departure_time, arrival_time, created_at, updated_at)
VALUES (1, 1, DATE_ADD(CURDATE(), INTERVAL 1 DAY), '08:00:00', '10:30:00', NOW(6), NOW(6));
