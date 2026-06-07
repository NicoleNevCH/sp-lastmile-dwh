/*
    Companion to assert_no_pii_leak: the columns that replaced the PII must be
    proper one-way tokens — exactly 64 lowercase hex characters (SHA-256). A
    clean hex token of this length cannot be a CPF or a house number, so this
    confirms the masking actually ran. Must return zero rows.
*/

select
    event_id,
    driver_cpf_hash,
    recipient_key,
    house_number_hash
from {{ ref('stg_deliveries') }}
where driver_cpf_hash     !~ '^[0-9a-f]{64}$'
   or recipient_key       !~ '^[0-9a-f]{64}$'
   or house_number_hash   !~ '^[0-9a-f]{64}$'
