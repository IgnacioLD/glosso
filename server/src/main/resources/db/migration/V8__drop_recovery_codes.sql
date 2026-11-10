-- El "cambiar de movil" pasa a ser SOLO con Google (V7, glosso.linked_accounts).
-- Se retira la recuperacion por codigo de un solo uso, asi que la tabla que la
-- respaldaba (V6) ya no la necesita nadie.
--
-- V6 ya esta aplicada en produccion y no se edita ni se borra: se deja como
-- historia. Esta migracion nueva limpia el esquema. Las filas que hubiera eran
-- hashes HMAC de codigos de un uso que ya no se pueden canjear, asi que no se
-- pierde nada recuperable. Idempotente como el resto.
drop table if exists glosso.recovery_codes;
