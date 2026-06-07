/*
    LGPD boundary, structural guarantee.

    The staging view is where personal data must be dropped or hashed. This test
    inspects the catalog and fails if any clear-text PII column ever leaks
    through with its original name. It must return zero rows.

    (Schemas resolve to clean names via the generate_schema_name macro, so the
    staging view really does live in schema 'staging'.)
*/

select column_name
from information_schema.columns
where table_schema = 'staging'
  and table_name   = 'stg_deliveries'
  and column_name in (
      'driver_cpf',
      'recipient_cpf',
      'recipient_name',
      'street',
      'house_number'
  )
