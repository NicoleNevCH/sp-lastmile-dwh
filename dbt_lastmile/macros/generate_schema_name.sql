{#
    Use custom schema names verbatim instead of dbt's default
    "<target_schema>_<custom_schema>" prefixing. This makes models land in the
    exact schemas the infrastructure provisions (raw / staging / analytics /
    snapshots), keeping the warehouse layout clean and predictable.

    - no +schema on the model  -> the profile's target schema
    - +schema: staging         -> "staging"
    - +schema: analytics       -> "analytics"
#}

{% macro generate_schema_name(custom_schema_name, node) -%}
    {%- set default_schema = target.schema -%}
    {%- if custom_schema_name is none -%}
        {{ default_schema }}
    {%- else -%}
        {{ custom_schema_name | trim }}
    {%- endif -%}
{%- endmacro %}
