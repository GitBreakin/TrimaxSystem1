-- =====================================================================================================
-- Rollback of 20261030_03_admin_duty.sql.
--
-- Refused (and nothing changes) once an Admin has recorded a Time in / Time out: those are attendance records.
-- Before that it drops the function, the index, the 2 checks and the 2 columns, and makes the photo required again.
-- =====================================================================================================

begin;

do $$
begin
    if exists (select 1 from information_schema.columns where table_schema = 'trimax_private'
                and table_name = 'duty_events' and column_name = 'recorded_by')
       and exists (select 1 from trimax_private.duty_events where recorded_by is not null or photo_path is null) then
        raise exception 'Not rolled back: an Admin already recorded a Time in / Time out. Nothing was changed.';
    end if;
end $$;

drop function if exists trimax_private.admin_duty_event(text, text, text, timestamptz, text);
drop index if exists trimax_private.duty_events_recorded_by_idx;
alter table trimax_private.duty_events drop constraint if exists duty_events_note_chk;
alter table trimax_private.duty_events drop constraint if exists duty_events_source_chk;
alter table trimax_private.duty_events drop column if exists note;
alter table trimax_private.duty_events drop column if exists recorded_by;
alter table trimax_private.duty_events alter column photo_path set not null;

commit;
