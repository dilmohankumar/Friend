-- Moderation: suspend with a reason, and resolve reports.
ALTER TABLE "users" ADD COLUMN "suspended_at" TIMESTAMP(3);
ALTER TABLE "users" ADD COLUMN "suspend_reason" TEXT;
ALTER TABLE "reports" ADD COLUMN "resolved_at" TIMESTAMP(3);
CREATE INDEX "reports_reported_id_idx" ON "reports"("reported_id");
