-- A unique receipt distinguishes the transaction that inserted an event from concurrent retries.
ALTER TABLE intervention_events ADD COLUMN receipt_token TEXT;
