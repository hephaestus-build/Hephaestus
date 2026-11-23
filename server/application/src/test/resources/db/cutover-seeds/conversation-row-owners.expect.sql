DO $$
BEGIN
    IF (SELECT array_agg(account_id ORDER BY id) FROM public.conversation_entry
        WHERE workspace_id = 979124 AND id IN ('97912400-0000-4000-8000-000000000021', '97912400-0000-4000-8000-000000000022'))
       IS DISTINCT FROM ARRAY[979125, 979126]::bigint[] THEN
        RAISE EXCEPTION 'The seeded conversation entries did not keep their owners';
    END IF;
    IF (SELECT array_agg(account_id ORDER BY id) FROM public.conversation_turn
        WHERE workspace_id = 979124 AND id IN ('97912400-0000-4000-8000-000000000011', '97912400-0000-4000-8000-000000000012'))
       IS DISTINCT FROM ARRAY[979125, 979126]::bigint[] THEN
        RAISE EXCEPTION 'The seeded conversation turns did not keep their owners';
    END IF;
END
$$;
