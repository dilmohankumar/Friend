-- Discovery ordered-index scan: lets "ORDER BY last_seen DESC, created_at DESC LIMIT n" stop early
-- instead of sorting every candidate of a gender bucket (3 lakh users: ~100 ms/request -> ~3 ms).
-- Deliberately NOT a partial index: Prisma casts enum parameters, which Postgres can't match to a partial predicate.
CREATE INDEX "users_discover_idx" ON "users" ("gender", "interested_in", "last_seen" DESC NULLS LAST, "created_at" DESC);

-- Refresh-token families: reuse of a consumed token revokes every token from that login.
ALTER TABLE "refresh_tokens" ADD COLUMN "family_id" UUID NOT NULL DEFAULT gen_random_uuid();
ALTER TABLE "refresh_tokens" ADD COLUMN "used_at" TIMESTAMP(3);
CREATE INDEX "refresh_tokens_family_id_idx" ON "refresh_tokens"("family_id");
CREATE INDEX "refresh_tokens_expires_at_idx" ON "refresh_tokens"("expires_at");
CREATE INDEX "otps_expires_at_idx" ON "otps"("expires_at");
