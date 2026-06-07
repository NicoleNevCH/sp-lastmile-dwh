{#
    LGPD masking helpers.

    mask_sha256(column): irreversible one-way hash (SHA-256, hex) of a PII
    column, salted with var('pii_salt') to frustrate rainbow-table attacks.
    The same input always maps to the same token, so masked keys still JOIN and
    de-duplicate correctly — but the original value can never be recovered.

    cep5(column): keeps only the 5-digit CEP prefix (regional grain), dropping
    the street-level suffix.
#}

{% macro mask_sha256(column) -%}
    encode(
        digest(
            '{{ var("pii_salt", "sp-lastmile-pepper") }}' || coalesce({{ column }}::text, ''),
            'sha256'
        ),
        'hex'
    )
{%- endmacro %}


{% macro cep5(column) -%}
    left(regexp_replace({{ column }}::text, '\D', '', 'g'), 5)
{%- endmacro %}
