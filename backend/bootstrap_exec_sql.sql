-- bootstrap_exec_sql.sql
-- Crea la RPC `exec_sql` en Supabase. Se ejecuta UNA sola vez a mano desde el
-- SQL Editor de Supabase (Dashboard → SQL Editor → New query → Run).
--
-- Sin esta función el backend NO puede auto-crear sus tablas al arrancar
-- (push_subs, fcm_tokens, visitor_logs, daily_stats, tool_stats,
-- download_stats, events, scan_logs) ni el admin-hub puede usar el
-- "DB Runner" contra Supabase (POST /api/admin/db/run con target 'supabase').
--
-- NOTA de seguridad: `security definer` ejecuta SQL arbitrario con privilegios
-- elevados. Se restringe el acceso a SOLO la clave service_role y se fija
-- search_path = public para evitar ataques de hijacking de esquemas.

create or replace function public.exec_sql(query text)
returns void
language plpgsql
security definer
set search_path = public
as $$
begin
  execute query;
end;
$$;

revoke all on function public.exec_sql(text) from public, anon, authenticated;
grant execute on function public.exec_sql(text) to service_role;