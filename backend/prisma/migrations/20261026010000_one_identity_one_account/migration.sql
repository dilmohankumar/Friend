-- One verified real person = one account (hash supplied by the identity provider, never an ID number).
ALTER TABLE "users" ADD COLUMN "kyc_fingerprint" TEXT;
CREATE UNIQUE INDEX "users_kyc_fingerprint_key" ON "users"("kyc_fingerprint");
