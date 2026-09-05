-- CodeHub — Push Notifications (clima) · tabla push_subs
-- Pega esto en el SQL Editor de Supabase y ejecuta.
-- (El backend también intenta crearla solo al arrancar vía exec_sql.)

create table if not exists public.push_subs (
  id bigint generated always as identity primary key,
  endpoint text not null unique,
  keys_p256dh text,
  keys_auth text,
  lat double precision,
  lon double precision,
  city text,
  country text,
  timezone text,
  user_agent text,
  alerts boolean default true,
  seismic_alerts boolean default true,
  seismic_mag numeric default 4.5,
  last_alert_condition text,
  last_alert_at timestamptz,
  last_brief_at timestamptz,
  weather_interval integer default 0,
  created_at timestamptz default now(),
  updated_at timestamptz default now()
);

create index if not exists push_subs_alerts_idx on public.push_subs (alerts);

-- RLS: permitir lecturas/escrituras desde el backend (usa la service key).
alter table public.push_subs enable row level security;
drop policy if exists push_subs_all on public.push_subs;
create policy push_subs_all on public.push_subs for all
  using (true) with check (true);
