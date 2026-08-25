-- The environment tier the publisher ran in ('dev', 'prod', 'platform'), or null for an event
-- recorded before the platform knew tiers — which is why there is no backfill: null is the true
-- value for those rows, not a gap to paper over. Envelope data like parent_id, and like parent_id
-- it names a row of another context's store (qits-deployments' environments, whose name column is
-- varchar(64)) by value with no FK: an environment deleted after its events happened does not make
-- the events false.
alter table event add column environment varchar(64);

-- Unlike the payload filters, ?environment= is an equality on its own column, so an index answers
-- it — the same decision idx_event_parent_id made for the children walk.
create index idx_event_environment on event (environment);
