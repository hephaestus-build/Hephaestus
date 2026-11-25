-- The upgrade gives each seeded version its identity: the catalog key for both versions of the adopted practice, and
-- the local key of its practice for the others, also for a catalog slug of another kind. This catalog declares no
-- classes, so the class is the version's own review digest.
DO $$
BEGIN
    -- ADR 0130: no version is left without an identity. The server requires one on every version, so a seed or a
    -- fixture that writes practice_version on a schema with the columns must set them too.
    IF EXISTS (SELECT 1 FROM practice_version WHERE identity_key IS NULL OR comparability_class IS NULL) THEN
        RAISE EXCEPTION 'practice versions without an identity after the upgrade: % (a seed or fixture that writes practice_version must set identity_key and comparability_class)',
            (SELECT string_agg(id::text, ', ' ORDER BY id) FROM practice_version
             WHERE identity_key IS NULL OR comparability_class IS NULL);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM practice_version WHERE id = 979003
            AND identity_key = 'software-engineering/scope-one-reviewable-change'
            AND comparability_class = review_digest) THEN
        RAISE EXCEPTION 'practice_version 979003 has no catalog identity after the upgrade';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM practice_version WHERE id = 979010
            AND identity_key = 'software-engineering/scope-one-reviewable-change'
            AND comparability_class = review_digest) THEN
        RAISE EXCEPTION 'practice_version 979010 has no catalog identity after the upgrade';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM practice_version WHERE id = 979008
            AND identity_key = 'local/979001/979007'
            AND comparability_class = review_digest) THEN
        RAISE EXCEPTION 'practice_version 979008 of another kind has no local identity after the upgrade';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM practice_version WHERE id = 979005
            AND identity_key = 'local/979001/979004'
            AND comparability_class = review_digest) THEN
        RAISE EXCEPTION 'practice_version 979005 has no local identity after the upgrade';
    END IF;
END $$;
