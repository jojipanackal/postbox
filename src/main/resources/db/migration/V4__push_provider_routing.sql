-- V2/V3 rows were all submitted through Expo; preserve their routing on upgrade.
ALTER TABLE push_deliveries ADD COLUMN provider VARCHAR(32) NOT NULL DEFAULT 'EXPO';
-- A provider without a receipt API may end at PROVIDER_ACCEPTED, not DELIVERED_TO_PUSH_SERVICE.
ALTER TABLE push_deliveries ADD COLUMN receipt_expected BOOLEAN NOT NULL DEFAULT TRUE;
